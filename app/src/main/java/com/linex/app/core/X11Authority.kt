package com.linex.app.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Xauthority uses big-endian uint16 lengths; wildcard family works across PRoot hostnames. */
object X11Authority {
    fun encode(cookie: ByteArray): ByteArray {
        require(cookie.size == 16) { "MIT-MAGIC-COOKIE-1 requires a 128-bit cookie" }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { stream ->
            stream.writeShort(65535)
            for (field in listOf(byteArrayOf(), "0".toByteArray(Charsets.US_ASCII),
                "MIT-MAGIC-COOKIE-1".toByteArray(Charsets.US_ASCII), cookie)) {
                stream.writeShort(field.size)
                stream.write(field)
            }
        }
        return bytes.toByteArray()
    }
}
