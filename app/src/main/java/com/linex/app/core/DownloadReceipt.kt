package com.linex.app.core

import java.io.File
import java.io.IOException

/** A receipt is written only after the response body has been received completely. */
internal object DownloadReceipt {
    fun matches(archive: File, url: String, sha256: (File) -> String): Boolean {
        val receipt = File(archive.path + ".complete")
        val expected = try {
            if (receipt.isFile && receipt.length() < 16384) receipt.readLines() else emptyList()
        } catch (_: IOException) { emptyList() }
        return archive.isFile && archive.length() > 0 && expected.size == 3 &&
            expected[0] == url && expected[1].toLongOrNull() == archive.length() &&
            expected[2] == sha256(archive)
    }

    fun write(archive: File, url: String, sha256: String) {
        File(archive.path + ".complete").writeText("$url\n${archive.length()}\n$sha256\n")
    }
}
