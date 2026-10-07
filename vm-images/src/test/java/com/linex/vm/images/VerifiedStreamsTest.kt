package com.linex.vm.images

import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.MemoryLimitException
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.EmptyCoroutineContext

class VerifiedStreamsTest {
    private val raw = "verified guest image bytes".toByteArray()
    private fun hash(bytes: ByteArray) = digestHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    @Test fun verifiesExactBytesAndRejectsTruncatedOversizedAndWrongHashes() {
        val output = ByteArrayOutputStream()
        copyVerified(ByteArrayInputStream(raw), raw.size.toLong(), hash(raw), EmptyCoroutineContext, {},
            { chunk, count -> output.write(chunk, 0, count) })
        assertArrayEquals(raw, output.toByteArray())
        for (bad in listOf(raw.dropLast(1).toByteArray(), raw + byteArrayOf(1)))
            assertThrows(IOException::class.java) { copyVerified(ByteArrayInputStream(bad), raw.size.toLong(), hash(raw), EmptyCoroutineContext, {}, { _, _ -> }) }
        assertThrows(IOException::class.java) { copyVerified(ByteArrayInputStream(raw), raw.size.toLong(), "0".repeat(64), EmptyCoroutineContext, {}, { _, _ -> }) }
    }
    @Test fun verifiesGzipAndXzTrailersAndRejectsOversizedDictionary() {
        val gzip = ByteArrayOutputStream().also { target -> GZIPOutputStream(target).use { it.write(raw) } }.toByteArray()
        val xz = ByteArrayOutputStream().also { target -> XZOutputStream(target, LZMA2Options(3)).use { it.write(raw) } }.toByteArray()
        for ((compression, encoded) in listOf(VmImageCompression.GZIP to gzip, VmImageCompression.XZ to xz)) {
            decodedStream(ByteArrayInputStream(encoded), compression).use {
                copyVerified(it, raw.size.toLong(), hash(raw), EmptyCoroutineContext, {}, { _, _ -> })
            }
            assertThrows(IOException::class.java) {
                decodedStream(ByteArrayInputStream(encoded.dropLast(5).toByteArray()), compression).use {
                    copyVerified(it, raw.size.toLong(), hash(raw), EmptyCoroutineContext, {}, { _, _ -> })
                }
            }
        }
        // Reuse the genuine small-dictionary stream. Only increase the decoded
        // LZMA2 dictionary advertisement and repair its XZ block-header CRC.
        // A 128 MiB encoder would allocate far more than the unit-test heap.
        val excessive = xz.copyOf()
        val blockOffset = 12 // XZ stream header has a fixed 12-byte size.
        val blockBytes = ((excessive[blockOffset].toInt() and 255) + 1) * 4
        assertEquals(0, excessive[blockOffset + 1].toInt()) // One filter, no encoded sizes.
        assertEquals(0x21, excessive[blockOffset + 2].toInt()) // LZMA2 filter ID.
        assertEquals(1, excessive[blockOffset + 3].toInt()) // One-byte dictionary property.
        excessive[blockOffset + 4] = 30 // (2 | (30 & 1)) << (30 / 2 + 11) = 128 MiB.
        val crc = CRC32().apply { update(excessive, blockOffset, blockBytes - 4) }
        ByteBuffer.wrap(excessive, blockOffset + blockBytes - 4, 4)
            .order(ByteOrder.LITTLE_ENDIAN).putInt(crc.value.toInt())
        assertThrows(MemoryLimitException::class.java) {
            decodedStream(ByteArrayInputStream(excessive), VmImageCompression.XZ).use { it.read() }
        }
    }
    @Test fun cancellationStopsBeforeAnyBytesAreWrittenAndZeroDetectionHonorsLength() {
        val cancelled = Job().apply { cancel() }
        var wrote = false
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            copyVerified(ByteArrayInputStream(raw), raw.size.toLong(), hash(raw), cancelled, {}, { _, _ -> wrote = true })
        }
        assertFalse(wrote)
        assertFalse(containsData(byteArrayOf(0, 0, 1), 2))
        assertTrue(containsData(byteArrayOf(0, 0, 1), 3))
    }
    @Test fun pinsRejectPathEscapePlaintextAndCredentialUrls() {
        for (path in listOf("vm-image-fixtures/../secret", "vm-image-fixtures//file", "outside/file"))
            assertThrows(IllegalArgumentException::class.java) { VmAssetSource.PrivateFile(path) }
        for (url in listOf("http://example.com/a", "https://user:secret@example.com/a", "https://example.com:80/a"))
            assertThrows(IllegalArgumentException::class.java) { VmAssetSource.Https(url) }
        assertThrows(IllegalArgumentException::class.java) { checkedInstanceId("../instance") }
        assertEquals(1f, VmInstallProgress(VmInstallStage.EXPAND_DISK, 200, 100).fraction)
    }
}
