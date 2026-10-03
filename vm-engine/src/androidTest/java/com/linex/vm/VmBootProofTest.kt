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
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Genuine kernel/JNI proof. Missing native engine or fixture is a failure, never a skip. */
class VmBootProofTest {
    @Test fun bootGuestForksControlAndFreshProcessRestart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.filesDir, "vm-proof").apply { mkdirs() }
        Os.chmod(root.absolutePath, 448)
        val manifest = Json.parseToJsonElement(instrumentation.context.assets.open("manifest.json")
            .bufferedReader().use { it.readText() }).jsonObject
        for (name in listOf("kernel", "boot-proof.cpio.gz")) {
            instrumentation.context.assets.open(name).use { source ->
                File(root, name).outputStream().use { source.copyTo(it) }
            }
        }
        val pids = mutableListOf<Int>()
        repeat(2) { launchIndex ->
            val token = UUID.randomUUID().toString().replace("-", "").take(16)
            val serialFile = File(root, "s-$token")
            val listenerSocket = LocalSocket()
            listenerSocket.bind(LocalSocketAddress(serialFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            Os.chmod(serialFile.absolutePath, 384)
            val listener = LocalServerSocket(listenerSocket.fileDescriptor)
            val executor = Executors.newSingleThreadExecutor()
            val accepted = executor.submit<LocalSocket> { listener.accept() }
            val ready = CountDownLatch(1)
            var binder: IBinder? = null
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) { binder = service; ready.countDown() }
                override fun onServiceDisconnected(name: ComponentName) = Unit
                override fun onNullBinding(name: ComponentName) { ready.countDown() }
            }
            val intent = Intent(context, NativeVmService::class.java)
            var client: VmEngineClient? = null
            var serial: LocalSocket? = null
            var capture: Capture? = null
            var bound = false
            try {
                context.startForegroundService(intent)
                bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                assertTrue("VM service bind failed", bound)
                assertTrue("VM service bind timed out", ready.await(10, TimeUnit.SECONDS))
                client = VmEngineClient(requireNotNull(binder), root)
                val baseline = client.observeHost()
                assertTrue(baseline.processes.complete)
                assertEquals(0, baseline.processes.childCount)
                val request = VmBootRequest(token, "boot-proof", File(root, "kernel").absolutePath,
                    manifest["kernel"]!!.jsonObject["sha256"]!!.jsonPrimitive.content,
                    File(root, "boot-proof.cpio.gz").absolutePath,
                    manifest["initramfs"]!!.jsonObject["sha256"]!!.jsonPrimitive.content,
                    serialFile.absolutePath, 256, 1)
                val launch = client.start(request)
                assertNotEquals("VM must be an isolated managed process", Process.myPid(), launch.pid)
                if (pids.isNotEmpty()) assertNotEquals("Restart must use a fresh PID", pids.last(), launch.pid)
                pids.add(launch.pid)
                serial = accepted.get(15, TimeUnit.SECONDS)
                assertEquals(Process.myUid(), serial.peerCredentials.uid)
                capture = Capture(serial)
                assertTrue("Linux boot marker missing", capture.boot.await(180, TimeUnit.SECONDS))
                assertEquals(64, capture.children)
                assertEquals(64, capture.guestPids.size)
                assertEquals(64, capture.guestPids.toSet().size)
                val status = client.status()
                assertEquals(launch.pid, status.pid)
                assertTrue("Host process observation incomplete", status.observationComplete)
                assertEquals("Guest forks created host children", 0, status.hostChildren)
                assertTrue("Host thread count invalid", status.hostThreads in 1..512)
                QmpClient.connect(launch.qmpSocket, root).use { qmp ->
                    qmp.negotiate()
                    qmp.pause()
                    assertEquals("paused", qmp.status())
                    qmp.resume()
                    assertEquals("running", qmp.status())
                }
                serial.outputStream.write("status\n".toByteArray())
                serial.outputStream.flush()
                Thread.sleep(500)
                repeat(5) {
                    val sample = client.status()
                    assertTrue(sample.observationComplete)
                    assertEquals(0, sample.hostChildren)
                    assertEquals(launch.pid, sample.pid)
                    Thread.sleep(100)
                }
                serial.outputStream.write("clear\n".toByteArray()); serial.outputStream.flush()
                val clearDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (capture.children != 0 && System.nanoTime() < clearDeadline) Thread.sleep(50)
                assertEquals("Guest children did not exit", 0, capture.children)
                val after = client.status()
                assertTrue(after.observationComplete)
                assertEquals(0, after.hostChildren)
                val evidence = buildJsonObject {
                    put("androidManaged", true); put("pid", launch.pid)
                    put("guestChildrenDuring", 64); put("guestChildrenAfter", capture.children)
                    put("distinctGuestPids", capture.guestPids.toSet().size)
                    put("hostChildrenBefore", baseline.processes.childCount)
                    put("hostChildrenDuring", status.hostChildren); put("hostChildrenAfter", after.hostChildren)
                    put("hostThreads", status.hostThreads); put("bootMarker", true)
                    put("pauseResume", true); put("androidSdk", android.os.Build.VERSION.SDK_INT)
                    put("abi", android.os.Build.SUPPORTED_ABIS.first())
                }
                File(context.filesDir, "vm-proof-$launchIndex.log").writeText(capture.snapshot())
                serial.outputStream.write("stop\n".toByteArray()); serial.outputStream.flush()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (client.isAlive() && System.nanoTime() < deadline) Thread.sleep(50)
                assertFalse("VM service survived clean guest shutdown", client.isAlive())
                File(context.filesDir, "vm-proof-$launchIndex.json").writeText(evidence.toString())
            } finally {
                capture?.let { File(context.filesDir, "vm-proof-$launchIndex.log").writeText(it.snapshot()) }
                client?.let {
                    if (it.isAlive()) runCatching { it.forceStop(); it.awaitExit(5000) }
                    it.close()
                }
                serial?.close(); listener.close(); listenerSocket.close()
                accepted.cancel(true); executor.shutdownNow()
                if (bound) context.unbindService(connection)
                context.stopService(intent)
                serialFile.delete()
            }
        }
    }

    private class Capture(socket: LocalSocket) {
        val boot = CountDownLatch(1)
        @Volatile var children = 0
        @Volatile var guestPids: List<Int> = emptyList()
        private val lines = ArrayDeque<String>()
        init {
            Thread({
                runCatching {
                    val input = socket.inputStream.buffered()
                    val line = StringBuilder()
                    while (true) {
                        val next = input.read()
                        if (next < 0) break
                        if (next == 10) {
                            val text = line.toString().trimEnd('\r')
                            synchronized(lines) { lines.add(text); while (lines.size > 1000) lines.removeFirst() }
                            if (text.startsWith("LINEX_VM_GUEST_CHILDREN count=")) children = text.substringAfter('=').toIntOrNull() ?: -1
                            if (text.startsWith("LINEX_VM_GUEST_PIDS ")) guestPids = text.substringAfter(' ')
                                .split(' ').filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull()?.takeIf { pid -> pid >= 2 } }
                            if (text == "LINEX_VM_BOOT_OK") boot.countDown()
                            line.setLength(0)
                        } else if (line.length < 1024) line.append(next.toChar())
                    }
                }
            }, "vm-proof-serial").apply { isDaemon = true; start() }
        }
        fun snapshot(): String = synchronized(lines) { lines.joinToString("\n") }
    }
}
