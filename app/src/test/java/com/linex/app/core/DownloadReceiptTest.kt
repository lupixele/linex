package com.linex.app.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class DownloadReceiptTest {
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test fun completedSmallArchiveCanBeReusedAfterExtractionFailure() {
        val dir = Files.createTempDirectory("linex-cache").toFile()
        try {
            val file = File(dir, "rootfs.tar.gz").apply { writeText("complete download") }
            DownloadReceipt.write(file, "https://example.test/rootfs", digest(file))
            File(dir, "rootfs.installing").mkdir()
            assertTrue(DownloadReceipt.matches(file, "https://example.test/rootfs", ::digest))
        } finally { dir.deleteRecursively() }
    }

    @Test fun interruptedDownloadWithoutReceiptCannotBeReused() {
        val dir = Files.createTempDirectory("linex-cache").toFile()
        try {
            val file = File(dir, "rootfs.tar.gz").apply { writeText("partial") }
            assertFalse(DownloadReceipt.matches(file, "url", ::digest))
        } finally { dir.deleteRecursively() }
    }

    @Test fun rejectsChangedSourceTruncationAndSameLengthCorruption() {
        val dir = Files.createTempDirectory("linex-cache").toFile()
        try {
            val file = File(dir, "rootfs.tar.gz").apply { writeText("original") }
            DownloadReceipt.write(file, "url", digest(file))
            assertFalse(DownloadReceipt.matches(file, "different-url", ::digest))
            file.writeText("altered!")
            assertFalse(DownloadReceipt.matches(file, "url", ::digest))
            file.writeText("cut")
            assertFalse(DownloadReceipt.matches(file, "url", ::digest))
        } finally { dir.deleteRecursively() }
    }
}
