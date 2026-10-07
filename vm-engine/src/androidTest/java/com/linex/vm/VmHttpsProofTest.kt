package com.linex.vm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** Full guest packets, system DNS and chain/hostname verified TLS; no root settings change. */
class VmHttpsProofTest {
    @Test fun guestDnsAndVerifiedHttpsUseTheManagedNetwork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val assets = instrumentation.context.assets
        val expectedPrivateDns = InstrumentationRegistry.getArguments().getString("expectedPrivateDns")
        val expectedPrivateDnsHostname = InstrumentationRegistry.getArguments().getString("expectedPrivateDnsHostname")
        require(expectedPrivateDns == null || expectedPrivateDns in listOf("off", "strict"))
        require(expectedPrivateDns != "strict" || !expectedPrivateDnsHostname.isNullOrBlank())
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        fun privateDnsState(): Pair<Boolean, Boolean> {
            if (Build.VERSION.SDK_INT < 28) return false to false
            val properties = requireNotNull(connectivity.getLinkProperties(requireNotNull(connectivity.activeNetwork)))
            return properties.isPrivateDnsActive to !properties.privateDnsServerName.isNullOrEmpty()
        }
        fun checkPrivateDnsState(): Pair<Boolean, Boolean> {
            if (expectedPrivateDns == null) return privateDnsState()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (System.nanoTime() < deadline) {
                val active = connectivity.activeNetwork
                val properties = active?.let(connectivity::getLinkProperties)
                val validated = active?.let(connectivity::getNetworkCapabilities)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                if (validated && properties != null) {
                    val matches = if (expectedPrivateDns == "off") {
                        !properties.isPrivateDnsActive && properties.privateDnsServerName.isNullOrEmpty()
                    } else {
                        properties.isPrivateDnsActive && properties.privateDnsServerName == expectedPrivateDnsHostname
                    }
                    if (matches) return properties.isPrivateDnsActive to !properties.privateDnsServerName.isNullOrEmpty()
                }
                Thread.sleep(250)
            }
            error("System network did not validate Private DNS mode $expectedPrivateDns within 60 seconds")
        }
        val privateDnsBefore = checkPrivateDnsState()
        val root = File(context.filesDir, "vm-proof").apply { mkdirs(); Os.chmod(absolutePath, 448) }
        val manifest = Json.parseToJsonElement(assets.open("https/manifest.json").bufferedReader().use { it.readText() }).jsonObject
        val token = UUID.randomUUID().toString().replace("-", "").take(16)
        val kernel = File(root, "https-k-$token")
        val initramfs = File(root, "https-i-$token")
        for ((asset, target) in listOf("https/kernel" to kernel, "https/https-proof.initramfs" to initramfs)) {
            assets.open(asset).use { input -> target.outputStream().use { input.copyTo(it) } }
            Os.chmod(target.absolutePath, 384)
        }
        val serialFile = File(root, "s-$token")
        val listening = LocalSocket()
        listening.bind(LocalSocketAddress(serialFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
        Os.chmod(serialFile.absolutePath, 384)
        val listener = LocalServerSocket(listening.fileDescriptor)
        val workers = Executors.newFixedThreadPool(4)
        val accepted = workers.submit<LocalSocket> { listener.accept() }
        val ready = CountDownLatch(1)
        var binder: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { binder = service; ready.countDown() }
            override fun onServiceDisconnected(name: ComponentName) = Unit
            override fun onNullBinding(name: ComponentName) { ready.countDown() }
        }
        val intent = Intent(context, NativeVmService::class.java)
        var bound = false
        var client: VmEngineClient? = null
        var serial: LocalSocket? = null
        var tlsServer: SSLServerSocket? = null
        var blackhole: ServerSocket? = null
        val exit = VmExitEvidence(context, 3)
        val transcript = StringBuilder()
        val lines = java.util.concurrent.LinkedBlockingQueue<String>(2048)
        try {
            context.startForegroundService(intent)
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            assertTrue(bound)
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            client = VmEngineClient(requireNotNull(binder), root)
            val before = client.observeHost()
            assertTrue("Initial host observation incomplete", before.processes.complete)
            assertEquals(0, before.processes.childCount)
            val launch = client.start(VmBootRequest(token, "https-proof", kernel.absolutePath,
                manifest["kernel"]!!.jsonObject["sha256"]!!.jsonPrimitive.content,
                initramfs.absolutePath, manifest["initramfs"]!!.jsonObject["sha256"]!!.jsonPrimitive.content,
                serialFile.absolutePath, 256, 1, network = VmNetworkMode.SLIRP_V4))
            exit.pid = launch.pid
            exit.phase = "https_guest_boot"
            assertNotEquals(Process.myPid(), launch.pid)
            serial = accepted.get(15, TimeUnit.SECONDS)
            assertEquals(Process.myUid(), serial.peerCredentials.uid)
            val connectedSerial = serial
            workers.submit {
                try {
                    connectedSerial.inputStream.buffered().use { input ->
                        var bytes = 0
                        val current = StringBuilder()
                        while (true) {
                            val next = input.read()
                            if (next == -1) break
                            bytes++
                            if (current.length > 8192 || bytes > 512 * 1024) {
                                lines.offer("LINEX_PROOF_CAPTURE_LIMIT")
                                break
                            }
                            if (next != 10) { current.append(next.toChar()); continue }
                            val line = current.toString().trimEnd('\r')
                            current.setLength(0)
                            synchronized(transcript) { transcript.append(line).append('\n') }
                            if (!lines.offer(line)) break
                        }
                    }
                } catch (_: Exception) { }
            }
            fun await(marker: String, seconds: Long = 35) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
                while (System.nanoTime() < deadline) {
                    val line = lines.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    check(!line.contains("FAILED") && !line.contains("ACCEPTED") && line != "LINEX_PROOF_CAPTURE_LIMIT") { line }
                    if (line == marker) return
                }
                fail("Guest did not emit $marker; last phase ${exit.phase}")
            }
            fun command(value: String) { connectedSerial.outputStream.write((value + "\n").toByteArray()); connectedSerial.outputStream.flush() }
            await("LINEX_VM_NETWORK_READY", 180)
            command("clock ${System.currentTimeMillis() / 1000}")
            await("LINEX_VM_CLOCK_SET", 10)
            exit.phase = "guest_dns"
            command("dns")
            await("LINEX_VM_DNS_OK", 30)
            val afterDns = client.status()
            assertTrue("DNS host observation incomplete", afterDns.observationComplete)
            assertEquals(0, afterDns.hostChildren)
            exit.phase = "guest_public_https"
            command("https")
            await("LINEX_VM_HTTPS_VERIFIED", 30)

            val certificateFactory = CertificateFactory.getInstance("X.509")
            val certificate = assets.open("https/server.pem").use { certificateFactory.generateCertificate(it) as X509Certificate }
            val ca = assets.open("https/ca.pem").use { certificateFactory.generateCertificate(it) as X509Certificate }
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(assets.open("https/server-key.pk8").use { it.readBytes() }))
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null); setKeyEntry("test-only", key, "fixture".toCharArray(), arrayOf(certificate, ca))
            }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "fixture".toCharArray()) }
            val ssl = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
            tlsServer = ssl.serverSocketFactory.createServerSocket(0, 4, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
            tlsServer.soTimeout = 35000
            val server = tlsServer
            val tlsResult = workers.submit<Pair<Int, Int>> {
                var verified = 0; var rejected = 0
                repeat(3) {
                    (server.accept() as SSLSocket).use { socket ->
                        socket.soTimeout = 10000
                        try {
                            socket.startHandshake()
                            val reader = socket.inputStream.bufferedReader()
                            var count = 0
                            while (true) {
                                val line = reader.readLine() ?: error("No fixture request")
                                if (line.isEmpty()) break
                                check(++count <= 16 && line.length <= 2048)
                            }
                            socket.outputStream.write("LINEX_VM_TLS_RESPONSE\n".toByteArray())
                            socket.outputStream.flush()
                            verified++
                        } catch (_: SSLException) { rejected++ }
                    }
                }
                verified to rejected
            }
            exit.phase = "guest_tls_validation"
            command("tls ${server.localPort}")
            await("LINEX_VM_TLS_VALID", 30)
            await("LINEX_VM_TLS_WRONG_HOST_REJECTED", 30)
            await("LINEX_VM_TLS_UNTRUSTED_REJECTED", 30)
            assertEquals(1 to 2, tlsResult.get(10, TimeUnit.SECONDS))
            blackhole = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 10000 }
            val blackholeServer = blackhole
            val stalled = workers.submit {
                blackholeServer.accept().use { socket ->
                    socket.soTimeout = 6000
                    while (socket.inputStream.read() != -1) { }
                }
            }
            exit.phase = "guest_tls_deadline"
            command("tls-timeout ${blackholeServer.localPort}")
            await("LINEX_VM_TLS_TIMEOUT_OK", 10)
            stalled.get(10, TimeUnit.SECONDS)
            val after = client.status()
            assertTrue("Final host observation incomplete", after.observationComplete)
            assertEquals(0, after.hostChildren)
            val privateDnsAfter = checkPrivateDnsState()
            command("clear")
            await("LINEX_VM_GUEST_CHILDREN count=0", 10)
            command("stop")
            await("LINEX_VM_SHUTDOWN", 10)
            assertTrue("Managed HTTPS VM did not exit", client.awaitExit(10000))
            exit.phase = "https_clean_exit"
            File(context.filesDir, "vm-https-proof.json").writeText(buildJsonObject {
                put("guestDns", true); put("sameDnsTransactionId", 4660); put("udpAndTcp", true)
                put("verifiedPublicHttps", true); put("validControlledTls", true)
                put("wrongHostnameRejected", true); put("untrustedCaRejected", true); put("tlsDeadline", true)
                put("hostChildrenAfter", after.hostChildren); put("hostObservationComplete", after.observationComplete)
                put("privateDnsMatrixVerified", false)
                put("expectedPrivateDnsMode", expectedPrivateDns ?: "unspecified")
                put("expectedPrivateDnsHostname", expectedPrivateDnsHostname ?: "unspecified")
                put("privateDnsActiveBefore", privateDnsBefore.first); put("privateDnsStrictBefore", privateDnsBefore.second)
                put("privateDnsActiveAfter", privateDnsAfter.first); put("privateDnsStrictAfter", privateDnsAfter.second)
            }.toString())
        } catch (error: Throwable) {
            exit.failureClass = error.javaClass.name
            throw error
        } finally {
            File(context.filesDir, "vm-https-proof.log").writeText(synchronized(transcript) { transcript.toString() })
            client?.let { active ->
                exit.binderAliveBeforeCleanup = active.isAlive()
                if (active.isAlive()) runCatching { active.forceStop(); active.awaitExit(5000) }
                    .onFailure { exit.cleanupError("https_force_stop", it) }
                exit.binderAliveAfterCleanup = active.isAlive()
                runCatching { active.close() }
            }
            try { serial?.close() } catch (_: Exception) { }
            try { listener.close() } catch (_: Exception) { }
            try { listening.close() } catch (_: Exception) { }
            try { tlsServer?.close() } catch (_: Exception) { }
            try { blackhole?.close() } catch (_: Exception) { }
            workers.shutdownNow()
            if (bound) context.unbindService(connection)
            context.stopService(intent)
            kernel.delete(); initramfs.delete(); serialFile.delete()
            exit.write()
        }
    }
}
