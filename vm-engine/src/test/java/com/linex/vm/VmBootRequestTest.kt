package com.linex.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class VmBootRequestTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun request(root: File): VmBootRequest {
        val kernel = File(root, "kernel").apply { writeText("verified kernel") }
        val initramfs = File(root, "initramfs").apply { writeText("verified initramfs") }
        fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        return VmBootRequest("0123456789abcdef0123456789abcdef", "fixture",
            kernel.absolutePath, digest(kernel), initramfs.absolutePath, digest(initramfs),
            File(root, "serial.sock").absolutePath, 256, 1)
    }

    @Test fun acceptsVerifiedPrivateFixture() {
        val root = temporary.newFolder()
        val request = request(root)
        assertEquals(request, request.validated(listOf(root)))
    }

    @Test fun rejectsModifiedAssets() {
        val root = temporary.newFolder()
        val request = request(root)
        File(request.kernelPath).appendText("corruption")
        assertThrows(IllegalArgumentException::class.java) { request.validated(listOf(root)) }
    }

    @Test fun rejectsAssetsOutsidePrivateRoots() {
        val root = temporary.newFolder()
        val request = request(root)
        assertThrows(IllegalArgumentException::class.java) {
            request.validated(listOf(temporary.newFolder()))
        }
    }

    @Test fun rejectsTraversalAndInvalidLimits() {
        val root = temporary.newFolder()
        val request = request(root)
        for (invalid in listOf(request.copy(instanceId = "../fixture"), request.copy(memoryMiB = 255),
            request.copy(memoryMiB = 1025), request.copy(vcpuCount = 0), request.copy(vcpuCount = 3),
            request.copy(sessionToken = "short"), request.copy(kernelSha256 = "bad"))) {
            assertThrows(IllegalArgumentException::class.java) { invalid.validated(listOf(root)) }
        }
    }

    @Test fun rejectsDirectoriesAndEmptyAssets() {
        val root = temporary.newFolder()
        val request = request(root)
        assertThrows(IllegalArgumentException::class.java) {
            request.copy(kernelPath = root.absolutePath).validated(listOf(root))
        }
        File(request.kernelPath).writeBytes(byteArrayOf())
        assertThrows(IllegalArgumentException::class.java) { request.validated(listOf(root)) }
    }

    @Test fun rejectsSerialEndpointOutsidePrivateStorage() {
        val root = temporary.newFolder()
        val request = request(root).copy(serialPath = File(temporary.newFolder(), "serial.sock").absolutePath)
        assertThrows(IllegalArgumentException::class.java) { request.validated(listOf(root)) }
    }

    @Test fun rejectsSiblingWithSamePathPrefix() {
        val root = temporary.newFolder("private")
        val sibling = temporary.newFolder("private-extra")
        val request = request(sibling)
        assertThrows(IllegalArgumentException::class.java) { request.validated(listOf(root)) }
    }
}
