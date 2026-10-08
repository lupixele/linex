package com.linex.vm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

class VmDesktopBootRequestTest {
    // Keep actual Unix endpoint limits meaningful even on Windows' long user-temp paths.
    @get:Rule val temporary = TemporaryFolder(File("build").apply { mkdirs() })
    private val token = "0123456789abcdef0123456789abcdef"
    private val available = 8L * 1024 * 1024 * 1024
    // The host checkout directory is vm-engine; its hyphen is deliberately invalid
    // in a QEMU Unix-forward rule. This pure path fixture models Android filesDir.
    private val consoleRoot = File(File.listRoots().first(), "data/user/0/com.linex.app/files")

    private fun request(root: File): VmDesktopBootRequest {
        val images = File(root, "vm-images/image/revision").apply { mkdirs() }
        val instance = File(root, "vm-instances/i").apply { mkdirs() }
        val kernel = File(images, "kernel").apply { writeText("kernel") }
        val initramfs = File(images, "initramfs").apply { writeText("initramfs") }
        val disk = File(instance, "root.raw")
        RandomAccessFile(disk, "rw").use { it.setLength(128L * 1024 * 1024) }
        fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        return VmDesktopBootRequest(token, "i", kernel.absolutePath, digest(kernel),
            initramfs.absolutePath, digest(initramfs), disk.absolutePath, disk.length(),
            File(instance, "s").absolutePath, 1024, 2)
    }

    @Test fun acceptsMutablePrivateDiskWithoutRequiringFactoryDigest() {
        val root = temporary.root
        val request = request(root)
        RandomAccessFile(request.diskPath, "rw").use { it.seek(8192); it.writeUTF("user data") }
        assertEquals(request, request.validated(root, available))
    }

    @Test fun acceptsCanonicalPreparedPathsAndReturnsOnlyCanonicalPrivateChildren() {
        val root = temporary.root
        val original = request(root)
        val canonical = original.copy(kernelPath = File(original.kernelPath).canonicalPath,
            initramfsPath = File(original.initramfsPath).canonicalPath,
            diskPath = File(original.diskPath).canonicalPath,
            serialPath = File(original.serialPath).canonicalPath)
        assertEquals(canonical, canonical.validated(root, available))
        assertEquals(File(canonical.diskPath), VmDesktopBootRequest.privatePath(
            original.diskPath, root, "vm-instances/i"))
        assertThrows(IllegalArgumentException::class.java) {
            VmDesktopBootRequest.privatePath(File(File(root, "vm-instances/irrelevant"), "disk").absolutePath,
                root, "vm-instances/i")
        }
    }

    @Test fun rejectsModifiedBootAssetsAndMismatchedDiskLength() {
        val root = temporary.root
        val request = request(root)
        assertThrows(IllegalArgumentException::class.java) {
            request.copy(diskBytes = request.diskBytes + 4096).validated(root, available)
        }
        File(request.kernelPath).appendText("tampered")
        assertThrows(IllegalArgumentException::class.java) { request.validated(root, available) }
    }

    @Test fun refusesAnotherInstancesDiskSerialAndAssetsOutsideImageStorage() {
        val root = temporary.root
        val request = request(root)
        val other = File(root, "vm-instances/other").apply { mkdirs() }
        val foreign = File(other, "disk").apply { writeText("other user data") }
        for (invalid in listOf(request.copy(diskPath = foreign.absolutePath),
            request.copy(serialPath = File(other, "serial").absolutePath),
            request.copy(kernelPath = foreign.absolutePath), request.copy(instanceId = "../other"))) {
            assertThrows(IllegalArgumentException::class.java) { invalid.validated(root, available) }
        }
    }

    @Test fun rejectsTraversalEvenWhenItsCanonicalDestinationIsPrivate() {
        val root = temporary.root
        val request = request(root)
        val traversed = File(File(request.diskPath).parentFile, "../i/root.raw").absolutePath
        assertThrows(IllegalArgumentException::class.java) { request.copy(diskPath = traversed).validated(root, available) }
    }

    @Test fun enforcesCpuMemoryAndRuntimeAvailableBudget() {
        val root = temporary.root
        val request = request(root)
        for (invalid in listOf(request.copy(memoryMiB = 255), request.copy(memoryMiB = 4097),
            request.copy(vcpuCount = 0), request.copy(vcpuCount = 3), request.copy(sessionToken = "../session"))) {
            assertThrows(IllegalArgumentException::class.java) { invalid.validated(root, available) }
        }
        assertThrows(IllegalArgumentException::class.java) { request.validated(root, 1024L * 1024 * 1024) }
        assertThrows(IllegalArgumentException::class.java) { request.validated(root, available, lowMemory = true) }
        assertEquals(512, VmDesktopBootRequest.availableGuestMemoryMiB(1024L * 1024 * 1024))
        assertEquals(4096, VmDesktopBootRequest.availableGuestMemoryMiB(available))
    }

    @Test fun encodesDiskPathAsJsonDataAndOwnsOnlyOneFixedConsoleForward() {
        val root = temporary.root
        val request = request(root)
        val dangerousName = File(File(request.diskPath).parentFile, "disk,driver=host_device.raw")
        File(request.diskPath).renameTo(dangerousName)
        val changed = request.copy(diskPath = dangerousName.absolutePath).validated(root, available)
        val console = VmConsoleEndpoint.create(consoleRoot, token)
        val argv = changed.qemuArguments(console)
        val block = Json.parseToJsonElement(argv[argv.indexOf("-blockdev") + 1]).jsonObject
        assertEquals("raw", block["driver"]!!.jsonPrimitive.content)
        assertEquals(dangerousName.canonicalPath, block["file"]!!.jsonObject["filename"]!!.jsonPrimitive.content)
        assertEquals(1, argv.count { it.startsWith("user,") })
        assertTrue(argv.single { it.startsWith("user,") }.endsWith("hostfwd=unix:${console.socket.absolutePath}-10.0.2.15:5901"))
        assertTrue(argv.single { it.startsWith("user,") }.contains("linex-host-loopback=off"))
    }

    @Test fun rejectsConsolePathParserSeparatorsAndOverlongUnixAddress() {
        for (bad in listOf(File(consoleRoot, "unsafe-root"), File(consoleRoot, "unsafe,root"), File(consoleRoot, "x".repeat(110)))) {
            assertThrows(IllegalArgumentException::class.java) { VmConsoleEndpoint.create(bad, token) }
        }
        assertThrows(IllegalArgumentException::class.java) { VmConsoleEndpoint.create(consoleRoot, token.uppercase()) }
    }
}
