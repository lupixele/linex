package com.linex.vm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real Binder/service rejection checks. Valid-launch cases require genuine JNI and guest boot. */
class VmEngineRejectionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun token() = UUID.randomUUID().toString().replace("-", "").take(16)
    private fun root() = File(context.filesDir, "vm-proof").apply { mkdirs(); Os.chmod(absolutePath, 448) }
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    @Test fun serviceRejectsCorruptAssetAndTerminatesRejectedProcess() {
        val storage = root()
        val id = token()
        val kernel = File(storage, "negative-k-$id").apply { writeText("original kernel") }
        val initramfs = File(storage, "negative-i-$id").apply { writeText("original initramfs") }
        val request = VmBootRequest(id, "negative", kernel.absolutePath, digest(kernel),
            initramfs.absolutePath, digest(initramfs), File(storage, "negative-s-$id").absolutePath, 256, 1)
        try {
            kernel.appendText("corruption")
            withService { _, client ->
                val rejected = assertThrows(IllegalArgumentException::class.java) { client.start(request) }
                assertTrue("Wrong rejection reason", rejected.message.orEmpty().contains("SHA-256 mismatch"))
                assertTrue("Rejected VM service remained alive", client.awaitExit(5000))
            }
        } finally {
            kernel.delete(); initramfs.delete()
        }
    }

    @Test fun serviceRejectsInvalidLimitsOutsidePathsAndNonSocketEndpoint() {
        val storage = root()
        val id = token()
        val kernel = File(storage, "negative-k-$id").apply { writeText("kernel") }
        val initramfs = File(storage, "negative-i-$id").apply { writeText("initramfs") }
        val serial = File(storage, "negative-s-$id").apply { writeText("not a socket") }
        val request = VmBootRequest(id, "negative", kernel.absolutePath, digest(kernel),
            initramfs.absolutePath, digest(initramfs), serial.absolutePath, 256, 1)
        val outside = File(context.cacheDir, "negative-outside-$id").apply { writeText("kernel") }
        val cases = listOf(
            request.copy(memoryMiB = 255) to "memory",
            request.copy(vcpuCount = 3) to "CPU",
            request.copy(kernelPath = outside.absolutePath, kernelSha256 = digest(outside)) to "outside private",
            request to "private socket",
        )
        try {
            for ((invalid, reason) in cases) withService { _, client ->
                val rejected = assertThrows(IllegalArgumentException::class.java) { client.start(invalid) }
                assertTrue("Unexpected reason for $reason", rejected.message.orEmpty().contains(reason))
                assertTrue("Rejected VM service remained alive after $reason", client.awaitExit(5000))
            }
        } finally {
            kernel.delete(); initramfs.delete(); serial.delete(); outside.delete()
        }
    }

    @Test fun activeDuplicateAndStaleControlsCannotStopOwnedGuest() {
        val storage = root()
        val manifest = Json.parseToJsonElement(instrumentation.context.assets.open("manifest.json")
            .bufferedReader().use { it.readText() }).jsonObject
        for (name in listOf("kernel", "boot-proof.cpio.gz")) {
            instrumentation.context.assets.open(name).use { source ->
                File(storage, name).outputStream().use { source.copyTo(it) }
            }
        }
        val id = token()
        val serialFile = File(storage, "s-$id")
        val listenerSocket = LocalSocket()
        listenerSocket.bind(LocalSocketAddress(serialFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
        Os.chmod(serialFile.absolutePath, 384)
        val listener = LocalServerSocket(listenerSocket.fileDescriptor)
        val executor = Executors.newSingleThreadExecutor()
        val accepted = executor.submit<LocalSocket> { listener.accept() }
        var serial: LocalSocket? = null
        try {
            withService { binder, client ->
                val request = VmBootRequest(id, "negative-proof", File(storage, "kernel").absolutePath,
                    manifest["kernel"]!!.jsonObject["sha256"]!!.jsonPrimitive.content,
                    File(storage, "boot-proof.cpio.gz").absolutePath,
                    manifest["initramfs"]!!.jsonObject["sha256"]!!.jsonPrimitive.content,
                    serialFile.absolutePath, 256, 1)
                val launch = client.start(request)
                serial = accepted.get(15, TimeUnit.SECONDS)
                val socket = requireNotNull(serial)
                val boot = executor.submit<Boolean> { awaitGuestBoot(socket) }
                assertTrue("Real Linux boot and 64 guest children are required", boot.get(180, TimeUnit.SECONDS))
                VmEngineClient(binder, storage).use { duplicate ->
                    val rejected = assertThrows(IllegalStateException::class.java) {
                        duplicate.start(request.copy(sessionToken = token(), memoryMiB = 1))
                    }
                    assertTrue(rejected.message.orEmpty().contains("one launch"))
                }
                for (code in listOf(NativeVmService.STATUS, NativeVmService.FORCE_STOP)) {
                    val rejected = assertThrows(IllegalArgumentException::class.java) {
                        staleCommand(binder, code, token())
                    }
                    assertTrue(rejected.message.orEmpty().contains("Stale or unknown"))
                    assertTrue("Stale controls killed the owned VM", client.isAlive())
                    val status = client.status()
                    assertEquals(launch.pid, status.pid)
                    assertEquals("EMULATING", status.state)
                    assertTrue("Host process observation is incomplete", status.observationComplete)
                    assertEquals("Guest workloads leaked into host child processes", 0, status.hostChildren)
                }
                assertTrue("Owned force stop was rejected", client.forceStop())
                assertTrue("Owned process did not exit within deadline", client.awaitExit(5000))
                assertFalse(client.isAlive())
                assertThrows(IllegalStateException::class.java) { client.status() }
            }
        } finally {
            serial?.close(); listener.close(); listenerSocket.close()
            accepted.cancel(true); executor.shutdownNow()
            serialFile.delete()
        }
    }

    private fun awaitGuestBoot(socket: LocalSocket): Boolean {
        val line = StringBuilder()
        var childCount = 0
        val input = socket.inputStream.buffered()
        while (true) {
            val next = input.read()
            if (next < 0) return false
            if (next == 10) {
                val text = line.toString().trimEnd('\r')
                if (text.startsWith("LINEX_VM_GUEST_CHILDREN count=")) {
                    childCount = text.substringAfter('=').toIntOrNull() ?: -1
                }
                if (text == "LINEX_VM_BOOT_OK") return childCount == 64
                line.setLength(0)
            } else if (line.length < 1024) line.append(next.toChar())
        }
    }

    private fun staleCommand(binder: IBinder, code: Int, staleToken: String) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(NativeVmService.DESCRIPTOR)
            data.writeString(staleToken)
            assertTrue(binder.transact(code, data, reply, 0))
            reply.readException()
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    private fun withService(block: (IBinder, VmEngineClient) -> Unit) {
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
        try {
            context.startForegroundService(intent)
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            assertTrue("VM service bind failed", bound)
            assertTrue("VM service bind timed out", ready.await(10, TimeUnit.SECONDS))
            val service = requireNotNull(binder)
            val connected = VmEngineClient(service, root())
            client = connected
            assertNotEquals("Test must exercise the remote managed service", Process.myPid(), connected.observeHost().pid)
            block(service, connected)
        } finally {
            client?.let {
                if (it.isAlive()) runCatching { it.forceStop(); it.awaitExit(5000) }
            }
            if (bound) context.unbindService(connection)
            context.stopService(intent)
            client?.let { if (it.isAlive()) runCatching { it.awaitExit(5000) }; it.close() }
        }
    }
}
