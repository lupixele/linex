package com.linex.app.core

import java.io.File
import java.io.RandomAccessFile

/** Reads a bounded tail after Xorg's process-level exit; Binder cannot report it then. */
object NativeX11DiagnosticTail {
    fun read(file: File, maxBytes: Int = 16 * 1024, maxLines: Int = 40): List<String> {
        require(maxBytes > 0 && maxLines > 0)
        if (!file.isFile) return emptyList()
        return RandomAccessFile(file, "r").use { input ->
            val offset = (input.length() - maxBytes).coerceAtLeast(0)
            input.seek(offset)
            val bytes = ByteArray((input.length() - offset).coerceAtMost(maxBytes.toLong()).toInt())
            input.readFully(bytes)
            val text = bytes.toString(Charsets.UTF_8)
            // A capped read may begin halfway through a line or UTF-8 character.
            val complete = if (offset > 0) text.substringAfter('\n', "") else text
            complete.lineSequence().filter { it.isNotBlank() }.toList().takeLast(maxLines)
        }
    }
}
