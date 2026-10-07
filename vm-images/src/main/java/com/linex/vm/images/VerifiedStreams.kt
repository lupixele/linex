package com.linex.vm.images

import kotlinx.coroutines.ensureActive
import org.tukaani.xz.XZInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.coroutines.CoroutineContext

internal const val XZ_MEMORY_LIMIT_KIB = 64 * 1024

internal fun digestHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

/** Reads EOF after exact bytes so decoder CRC/footer and trailing data are checked too. */
internal fun copyVerified(
    input: InputStream, expectedBytes: Long, expectedSha256: String, context: CoroutineContext,
    progress: (Long) -> Unit, write: (ByteArray, Int) -> Unit,
) {
    require(expectedBytes > 0)
    val hash = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var completed = 0L
    while (true) {
        context.ensureActive()
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) throw java.io.IOException("Image stream made no progress")
        if (read > expectedBytes - completed) throw java.io.IOException("Image stream exceeds pinned length")
        hash.update(buffer, 0, read)
        write(buffer, read)
        completed += read
        progress(completed)
    }
    if (completed != expectedBytes || digestHex(hash.digest()) != expectedSha256)
        throw java.io.IOException("Image stream length or SHA256 mismatch")
}

internal fun decodedStream(input: InputStream, compression: VmImageCompression): InputStream = when (compression) {
    VmImageCompression.XZ -> XZInputStream(input, XZ_MEMORY_LIMIT_KIB)
    VmImageCompression.GZIP -> GZIPInputStream(input, 64 * 1024)
}

/** A zero chunk may become a sparse hole; the caller must set the final logical length. */
internal fun containsData(bytes: ByteArray, length: Int): Boolean {
    for (index in 0 until length) if (bytes[index] != 0.toByte()) return true
    return false
}
