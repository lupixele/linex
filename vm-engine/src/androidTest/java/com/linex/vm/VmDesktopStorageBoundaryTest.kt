package com.linex.vm

import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

/** Real Android symlinks must never let mutable disks or immutable assets cross instance boundaries. */
class VmDesktopStorageBoundaryTest {
    @Test fun acceptsCanonicalInstallerPathsFromContextRootButRejectsNestedAliases() {
        val files = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        // API33 app-private Context and canonical paths reproduce the installer
        // boundary. This must exercise two actual root views, never a mock path.
        assertNotEquals("Expected Android private-storage root alias", files.absolutePath, files.canonicalPath)
        val identity = "alias" + UUID.randomUUID().toString().replace("-", "").take(8)
        val images = File(files, "vm-images/$identity").apply { check(mkdirs()) }
        val instance = File(files, "vm-instances/$identity").apply { check(mkdirs()); Os.chmod(absolutePath, 448) }
        try {
            val kernel = File(images, "kernel").apply { writeText("canonical kernel bytes") }
            val initramfs = File(images, "initramfs").apply { writeText("canonical initramfs bytes") }
            val disk = File(instance, "disk")
            RandomAccessFile(disk, "rw").use { it.setLength(128L * 1024 * 1024) }
            fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
            val request = VmDesktopBootRequest("0123456789abcdef0123456789abcdef", identity,
                kernel.canonicalPath, digest(kernel), initramfs.canonicalPath, digest(initramfs),
                disk.canonicalPath, disk.length(), File(instance.canonicalFile, "s").path, 256, 1)
            val budget = 8L * 1024 * 1024 * 1024
            assertEquals(request, request.validated(files, budget))
            assertEquals(request, request.validated(files.canonicalFile, budget))
            // Preserve the original trusted Context spelling, too.
            assertEquals(request.copy(kernelPath = kernel.absolutePath, diskPath = disk.absolutePath),
                request.copy(kernelPath = kernel.absolutePath, diskPath = disk.absolutePath).validated(files, budget))
            val alias = File(images, "nested")
            Files.createSymbolicLink(alias.toPath(), images.canonicalFile.toPath())
            try {
                for (root in listOf(files, files.canonicalFile)) {
                    val linked = File(images.canonicalFile, "nested/kernel")
                    assertThrows(IllegalArgumentException::class.java) {
                        request.copy(kernelPath = linked.absolutePath).validated(root, budget)
                    }
                }
                assertThrows(IllegalArgumentException::class.java) {
                    request.copy(kernelPath = File(alias, "kernel").absolutePath).validated(files, budget)
                }
            } finally { Files.delete(alias.toPath()) }
        } finally {
            for (directory in listOf(instance, images)) {
                directory.listFiles()?.forEach { it.delete() }
                directory.delete()
            }
        }
    }

    @Test fun rejectsSymlinkLeavesAndAncestorsEvenWhenTheyResolveInsideAllowedStorage() {
        val files = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        val identity = "boundary" + UUID.randomUUID().toString().replace("-", "").take(8)
        val images = File(files, "vm-images/$identity").apply { mkdirs() }
        val instance = File(files, "vm-instances/$identity").apply { mkdirs(); Os.chmod(absolutePath, 448) }
        try {
            val kernel = File(images, "kernel").apply { writeText("kernel bytes") }
            val initramfs = File(images, "initramfs").apply { writeText("initramfs bytes") }
            val disk = File(instance, "disk")
            RandomAccessFile(disk, "rw").use { it.setLength(128L * 1024 * 1024) }
            fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
            val request = VmDesktopBootRequest("0123456789abcdef0123456789abcdef", identity,
                kernel.absolutePath, digest(kernel), initramfs.absolutePath, digest(initramfs),
                disk.absolutePath, disk.length(), File(instance, "s").absolutePath, 256, 1)
            val budget = 8L * 1024 * 1024 * 1024
            assertEquals(request, request.validated(files, budget))
            val diskLink = File(instance, "disk-link")
            Files.createSymbolicLink(diskLink.toPath(), disk.toPath())
            assertThrows(IllegalArgumentException::class.java) { request.copy(diskPath = diskLink.absolutePath).validated(files, budget) }
            val kernelLink = File(images, "kernel-link")
            Files.createSymbolicLink(kernelLink.toPath(), kernel.toPath())
            assertThrows(IllegalArgumentException::class.java) { request.copy(kernelPath = kernelLink.absolutePath).validated(files, budget) }
            val directoryLink = File(images, "alias")
            Files.createSymbolicLink(directoryLink.toPath(), images.toPath())
            assertThrows(IllegalArgumentException::class.java) {
                request.copy(kernelPath = File(directoryLink, "kernel").absolutePath).validated(files, budget)
            }
            // Explicitly unlink guest-style links before deleting owned regular test files.
            Files.delete(directoryLink.toPath()); Files.delete(kernelLink.toPath()); Files.delete(diskLink.toPath())
        } finally {
            for (directory in listOf(instance, images)) {
                directory.listFiles()?.forEach { it.delete() }
                directory.delete()
            }
        }
    }
}
