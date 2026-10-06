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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Two independent remote Binder calls race for the same disposable engine process. */
class VmConcurrentStartTest {
    @Test fun simultaneousValidStartsHaveExactlyOneBootingOwner() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val storage = File(context.filesDir, "vm-proof").apply { mkdirs(); Os.chmod(absolutePath, 448) }
        val fixtureId = token()
        val kernel = File(storage, "race-k-$fixtureId")
        val initramfs = File(storage, "race-i-$fixtureId")
        val manifest = Json.parseToJsonElement(instrumentation.context.assets.open("manifest.json")
            .bufferedReader().use { it.readText() }).jsonObject
        val kernelHash = manifest["kernel"]!!.jsonObject["sha256"]!!.jsonPrimitive.content
        val initramfsHash = manifest["initramfs"]!!.jsonObject["sha256"]!!.jsonPrimitive.content
        val endpoints = mutableListOf<SerialEndpoint>()
        val clients = mutableListOf<VmEngineClient>()
        val workers = Executors.newFixedThreadPool(2)
        val serialWorkers = Executors.newFixedThreadPool(2)
        val launchGate = CountDownLatch(1)
        val callersReady = CountDownLatch(2)
        val serviceReady = CountDownLatch(1)
        var binder: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder = service
                serviceReady.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
            override fun onNullBinding(name: ComponentName) { serviceReady.countDown() }
        }
        val intent = Intent(context, NativeVmService::class.java)
        var bound = false
        var owner: VmEngineClient? = null
        var serial: LocalSocket? = null
        try {
            for ((name, file) in listOf("kernel" to kernel, "boot-proof.initramfs" to initramfs)) {
                instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
                Os.chmod(file.absolutePath, 384)
            }
            repeat(2) { endpoints += SerialEndpoint(File(storage, "s-${token()}")) }
            val requests = endpoints.map { endpoint ->
                VmBootRequest(token(), "concurrent-proof", kernel.absolutePath, kernelHash,
                    initramfs.absolutePath, initramfsHash, endpoint.file.absolutePath, 256, 1)
                    .validated(listOf(storage))
            }
            assertNotEquals(requests[0].sessionToken, requests[1].sessionToken)
            val accepted = endpoints.map { endpoint -> serialWorkers.submit<LocalSocket> { endpoint.listener.accept() } }

            context.startForegroundService(intent)
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            assertTrue("Managed VM service bind failed", bound)
            assertTrue("Managed VM service bind timed out", serviceReady.await(10, TimeUnit.SECONDS))
            val remote = requireNotNull(binder)
            repeat(2) { clients += VmEngineClient(remote, storage) }
            val before = clients[0].observeHost()
            assertNotEquals("Race must target a real remote service", Process.myPid(), before.pid)
            assertTrue("Initial host observation is incomplete", before.processes.complete)
            assertEquals(0, before.processes.childCount)

            val attempts = clients.mapIndexed { index, client ->
                workers.submit<Attempt> {
                    callersReady.countDown()
                    check(launchGate.await(10, TimeUnit.SECONDS)) { "Concurrent launch gate timed out" }
                    try {
                        Attempt(index, client.start(requests[index]), null)
                    } catch (error: Exception) {
                        Attempt(index, null, error)
                    }
                }
            }
            assertTrue("Both launch callers must be waiting together", callersReady.await(10, TimeUnit.SECONDS))
            launchGate.countDown()
            val results = attempts.map { it.get(30, TimeUnit.SECONDS) }
            val successful = results.filter { it.launch != null }
            assertEquals("Exactly one simultaneous launch must be accepted", 1, successful.size)
            val winner = successful.single()
            val winningClient = clients[winner.index]
            owner = winningClient
            val rejected = results.single { it.launch == null }
            val error = rejected.failure
            assertTrue("Losing start must return an explicit ownership rejection: $error", error is IllegalStateException)
            assertTrue("Unexpected concurrent rejection", error?.message.orEmpty().contains("one launch"))
            val launch = requireNotNull(winner.launch)
            assertEquals("Winning process changed during the race", before.pid, launch.pid)
            assertEquals(File(storage, "session-${requests[winner.index].sessionToken}/qmp.sock").canonicalFile,
                launch.qmpSocket.canonicalFile)
            assertFalse("Losing launch created a session directory",
                File(storage, "session-${requests[rejected.index].sessionToken}").exists())

            serial = accepted[winner.index].get(15, TimeUnit.SECONDS)
            val guest = requireNotNull(serial)
            assertEquals(Process.myUid(), guest.peerCredentials.uid)
            val boot = workers.submit<Boolean> { awaitGuestBoot(guest) }
            assertTrue("Winning VM must boot Linux with 64 persistent guest children", boot.get(180, TimeUnit.SECONDS))
            val running = winningClient.status()
            assertEquals(launch.pid, running.pid)
            assertEquals("EMULATING", running.state)
            assertTrue("Running host observation is incomplete", running.observationComplete)
            assertEquals("Guest forks became Android child processes", 0, running.hostChildren)
            assertTrue("Owned stop was rejected", winningClient.forceStop())
            assertTrue("Managed VM process remained after stop", winningClient.awaitExit(5000))
            assertFalse("Losing client still sees a live old process", clients[rejected.index].isAlive())
            winningClient.close()
            assertFalse("Stopped owner's private control directory remains", launch.qmpSocket.parentFile!!.exists())
        } finally {
            launchGate.countDown()
            owner?.let { if (it.isAlive()) runCatching { it.forceStop(); it.awaitExit(5000) } }
            if (bound) runCatching { context.unbindService(connection) }
            runCatching { context.stopService(intent) }
            clients.forEach { client ->
                if (client.isAlive()) runCatching { client.awaitExit(5000) }
                runCatching { client.close() }
            }
            runCatching { serial?.close() }
            endpoints.forEach { it.close() }
            workers.shutdownNow()
            serialWorkers.shutdownNow()
            kernel.delete(); initramfs.delete()
        }
    }

    private fun awaitGuestBoot(socket: LocalSocket): Boolean {
        val input = socket.inputStream.buffered()
        val line = StringBuilder()
        var children = -1
        var guestPids = emptyList<Int>()
        while (true) {
            val next = input.read()
            if (next < 0) return false
            if (next == 10) {
                val text = line.toString().trimEnd('\r')
                when {
                    text.startsWith("LINEX_VM_GUEST_CHILDREN count=") -> children = text.substringAfter('=').toIntOrNull() ?: -1
                    text.startsWith("LINEX_VM_GUEST_PIDS ") -> guestPids = text.substringAfter(' ')
                        .trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
                    text == "LINEX_VM_BOOT_OK" -> return children == 64 && guestPids.size == 64 &&
                        guestPids.distinct().size == 64 && guestPids.all { it >= 2 }
                }
                line.setLength(0)
            } else {
                check(line.length < 1024) { "Guest serial line exceeds proof limit" }
                line.append(next.toChar())
            }
        }
    }

    private class SerialEndpoint(val file: File) : AutoCloseable {
        private val socket = LocalSocket()
        val listener: LocalServerSocket
        init {
            try {
                socket.bind(LocalSocketAddress(file.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                Os.chmod(file.absolutePath, 384)
                listener = LocalServerSocket(socket.fileDescriptor)
            } catch (error: Exception) {
                runCatching { socket.close() }
                file.delete()
                throw error
            }
        }
        override fun close() {
            runCatching { listener.close() }
            runCatching { socket.close() }
            file.delete()
        }
    }

    private data class Attempt(val index: Int, val launch: VmEngineLaunch?, val failure: Exception?)
    private fun token() = UUID.randomUUID().toString().replace("-", "").take(16)
}
