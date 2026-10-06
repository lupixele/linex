package com.linex.vm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
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
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Genuine guest DHCP/route/controlled TCP proof. DNS/public internet/TLS remain separate gates. */
class VmNetworkProofTest {
    @Test fun guestDhcpRouteAndControlledTcpKeepForksInsideManagedVm() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.filesDir, "vm-proof").apply { mkdirs(); Os.chmod(absolutePath, 448) }
        val manifest = Json.parseToJsonElement(instrumentation.context.assets.open("network/manifest.json")
            .bufferedReader().use { it.readText() }).jsonObject
        val token = UUID.randomUUID().toString().replace("-", "").take(16)
        val kernel = File(root, "net-k-$token")
        val initramfs = File(root, "net-i-$token")
        for ((asset, target) in listOf("network/kernel" to kernel, "network/network-proof.initramfs" to initramfs)) {
            instrumentation.context.assets.open(asset).use { input -> target.outputStream().use { input.copyTo(it) } }
            Os.chmod(target.absolutePath, 384)
        }
        val serialFile = File(root, "s-$token")
        val listenerSocket = LocalSocket()
        listenerSocket.bind(LocalSocketAddress(serialFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
        Os.chmod(serialFile.absolutePath, 384)
        val listener = LocalServerSocket(listenerSocket.fileDescriptor)
        val workers = Executors.newFixedThreadPool(2)
        val accepted = workers.submit<LocalSocket> { listener.accept() }
        val boundReady = CountDownLatch(1)
        var binder: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { binder = service; boundReady.countDown() }
            override fun onServiceDisconnected(name: ComponentName) = Unit
            override fun onNullBinding(name: ComponentName) { boundReady.countDown() }
        }
        val intent = Intent(context, NativeVmService::class.java)
        val exitEvidence = VmExitEvidence(context, 2)
        var bound = false
        var client: VmEngineClient? = null
        var serial: LocalSocket? = null
        var capture: Capture? = null
        var challenge: ServerSocket? = null
        var failure: Throwable? = null
        try {
            exitEvidence.phase = "network_binding"
            context.startForegroundService(intent)
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            assertTrue("Network VM service bind failed", bound)
            assertTrue("Network VM bind timed out", boundReady.await(10, TimeUnit.SECONDS))
            client = VmEngineClient(requireNotNull(binder), root)
            val before = client.observeHost()
            exitEvidence.pid = before.pid
            assertTrue("Initial network host observation is incomplete: ${before.processes}", before.processes.complete)
            assertEquals(0, before.processes.childCount)
            val launch = client.start(VmBootRequest(token, "network-proof", kernel.absolutePath,
                manifest["kernel"]!!.jsonObject["sha256"]!!.jsonPrimitive.content,
                initramfs.absolutePath, manifest["initramfs"]!!.jsonObject["sha256"]!!.jsonPrimitive.content,
                serialFile.absolutePath, 256, 1, network = VmNetworkMode.SLIRP_V4))
            exitEvidence.pid = launch.pid
            exitEvidence.phase = "awaiting_network_serial"
            assertNotEquals(Process.myPid(), launch.pid)
            serial = accepted.get(15, TimeUnit.SECONDS)
            assertEquals(Process.myUid(), serial.peerCredentials.uid)
            capture = Capture(serial)
            exitEvidence.phase = "awaiting_guest_dhcp"
            assertTrue("Guest networking fixture did not become ready", capture.ready.await(180, TimeUnit.SECONDS))
            assertTrue("Guest did not prove DHCP advertisement", capture.has("LINEX_VM_DHCP ip=10.0.2.15 gateway=10.0.2.2 dns=10.0.2.3"))
            assertTrue("Guest did not prove installed address/default route", capture.has("LINEX_VM_NETWORK_ADDRESS address=10.0.2.15/24 route=10.0.2.2 dns=10.0.2.3"))
            assertTrue(capture.has("LINEX_VM_BOOT_OK"))
            assertEquals(64, capture.children)
            assertEquals(64, capture.guestPids.size)
            assertEquals(64, capture.guestPids.toSet().size)
            val during = client.status()
            assertTrue("Network host observation is incomplete: $during", during.observationComplete)
            assertEquals("Guest network/forks created host children", 0, during.hostChildren)
            assertEquals(launch.pid, during.pid)
            exitEvidence.lastVmState = during.state

            // Test-harness TCP endpoint only: no listener/host forwarding is
            // added to the production service or the Android DNS bridge.
            challenge = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
            challenge.soTimeout = 10000
            val server = requireNotNull(challenge)
            val verifiedRequest = workers.submit<Boolean> {
                server.accept().use { peer ->
                    peer.soTimeout = 5000
                    val input = peer.getInputStream()
                    val request = ArrayList<Byte>()
                    while (request.size < 129) {
                        val next = input.read()
                        if (next < 0) break
                        request += next.toByte()
                        if (next == 10) break
                    }
                    if (request.toByteArray().contentEquals("LINEX_VM_TCP_REQUEST\n".toByteArray())) {
                        peer.getOutputStream().write("LINEX_VM_TCP_RESPONSE\n".toByteArray())
                        peer.getOutputStream().flush()
                        true
                    } else false
                }
            }
            exitEvidence.phase = "guest_tcp_challenge"
            serial.outputStream.write("tcp ${server.localPort}\n".toByteArray()); serial.outputStream.flush()
            assertTrue("Guest TCP response marker missing", capture.tcp.await(10, TimeUnit.SECONDS))
            assertTrue("Host did not independently receive the guest TCP request", verifiedRequest.get(2, TimeUnit.SECONDS))
            repeat(5) {
                val sample = client.status()
                assertTrue("Host observation incomplete under TCP load: $sample", sample.observationComplete)
                assertEquals(0, sample.hostChildren)
                Thread.sleep(100)
            }
            serial.outputStream.write("clear\n".toByteArray()); serial.outputStream.flush()
            val clearDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (capture.children != 0 && System.nanoTime() < clearDeadline) Thread.sleep(50)
            assertEquals(0, capture.children)
            val after = client.status()
            assertTrue("Host observation incomplete after guest clear: $after", after.observationComplete)
            assertEquals(0, after.hostChildren)
            val evidence = buildJsonObject {
                put("proof", "Android-managed guest DHCP/default-route/controlled TCP; DNS/internet/HTTPS not yet proved")
                put("pid", launch.pid); put("androidSdk", android.os.Build.VERSION.SDK_INT)
                put("dhcp", true); put("installedAddress", "10.0.2.15/24"); put("defaultRoute", "10.0.2.2")
                put("advertisedDns", "10.0.2.3"); put("tcpChallenge", true)
                put("guestChildrenDuring", 64); put("guestChildrenAfter", 0)
                put("distinctGuestPids", capture.guestPids.toSet().size)
                put("hostChildrenBefore", before.processes.childCount)
                put("hostChildrenDuring", during.hostChildren); put("hostChildrenAfter", after.hostChildren)
                put("hostObservationBefore", before.processes.method.wireValue)
                put("hostObservationDuring", during.observationMethod.wireValue)
                put("hostObservationAfter", after.observationMethod.wireValue)
            }
            serial.outputStream.write("stop\n".toByteArray()); serial.outputStream.flush()
            exitEvidence.phase = "network_guest_stop_requested"
            assertTrue("Network-enabled managed VM survived clean shutdown", client.awaitExit(10000))
            File(context.filesDir, "vm-network-proof.json").writeText(evidence.toString())
            exitEvidence.phase = "network_clean_stop_observed"
        } catch (error: Throwable) {
            failure = error
            exitEvidence.failureClass = error.javaClass.name
            throw error
        } finally {
            runCatching { challenge?.close() }
            client?.let { active ->
                exitEvidence.binderAliveBeforeCleanup = active.isAlive()
                if (active.isAlive()) runCatching { active.forceStop(); active.awaitExit(5000) }
                    .onFailure { exitEvidence.cleanupError("network_force_stop", it) }
                exitEvidence.binderAliveAfterCleanup = active.isAlive()
                runCatching { active.close() }
            }
            if (bound) runCatching { context.unbindService(connection) }
            runCatching { context.stopService(intent) }
            runCatching { serial?.close() }; runCatching { listener.close() }; runCatching { listenerSocket.close() }
            workers.shutdownNow()
            serialFile.delete(); kernel.delete(); initramfs.delete()
            try {
                capture?.let { File(context.filesDir, "vm-network-proof.log").writeText(it.text()) }
            } catch (writeError: Throwable) {
                failure?.addSuppressed(writeError) ?: throw writeError
            }
            exitEvidence.write()
        }
    }

    private class Capture(socket: LocalSocket) {
        val ready = CountDownLatch(1)
        val tcp = CountDownLatch(1)
        @Volatile var children = -1
        @Volatile var guestPids = emptyList<Int>()
        private val lines = ArrayDeque<String>()
        init {
            Thread({
                socket.inputStream.buffered().use { input ->
                    val line = StringBuilder()
                    while (true) {
                        val next = input.read()
                        if (next < 0) break
                        if (next == 10) {
                            val text = line.toString().trimEnd('\r')
                            synchronized(lines) { lines += text; while (lines.size > 1000) lines.removeFirst() }
                            if (text.startsWith("LINEX_VM_GUEST_CHILDREN count=")) children = text.substringAfter('=').toIntOrNull() ?: -1
                            if (text.startsWith("LINEX_VM_GUEST_PIDS ")) guestPids = text.substringAfter(' ')
                                .split(' ').filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull()?.takeIf { pid -> pid >= 2 } }
                            if (text == "LINEX_VM_NETWORK_READY") ready.countDown()
                            if (text == "LINEX_VM_TCP_OK") tcp.countDown()
                            line.setLength(0)
                        } else if (line.length < 1024) line.append(next.toChar())
                    }
                }
            }, "vm-network-proof-serial").apply { isDaemon = true; start() }
        }
        fun has(line: String): Boolean = synchronized(lines) { line in lines }
        fun text(): String = synchronized(lines) { lines.joinToString("\n") }
    }
}
