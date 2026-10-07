package com.linex.app.core

import org.junit.Assert.*
import org.junit.Test

class VmImageCatalogueTest {
    private fun manifest(extra: String = "", ready: Boolean = true, file: String = "kernel", base: String = "https://github.com/lupixele/linex/releases/download/v0.6.0-dev/") = """
        {"schema":1,"architecture":"aarch64","releaseReady":$ready,"runtimeProofPassed":true,
        "imageId":"debian-trixie-desktop","revision":"verified1","downloadBase":"$base",
        "kernel":{"file":"$file","sha256":"${"a".repeat(64)}","bytes":10},
        "initramfs":{"file":"desktop.cpio.gz","sha256":"${"b".repeat(64)}","bytes":20},
        "download":{"file":"factory.raw.xz","compression":"xz","sha256":"${"c".repeat(64)}","bytes":30},
        "disk":{"format":"raw","filesystem":"ext4","sha256":"${"d".repeat(64)}","bytes":4294967296}$extra}
    """.trimIndent()
    @Test fun acceptsOnlyAnExternallyVerifiedPinnedReleaseDescriptor() {
        val image = VmImageCatalogue.parse(manifest())
        assertEquals("debian-trixie-desktop", image.imageId)
        assertEquals(4294967296L, image.diskBytes)
        assertEquals(30L, image.download.bytes)
        assertThrows(IllegalArgumentException::class.java) { VmImageCatalogue.parse(manifest(ready = false)) }
        assertThrows(IllegalArgumentException::class.java) { VmImageCatalogue.parse(manifest().replace("\"runtimeProofPassed\":true", "\"runtimeProofPassed\":false")) }
    }
    @Test fun rejectsForeignDownloadsTraversalAndUnboundedMetadata() {
        for (base in listOf("http://github.com/lupixele/linex/releases/download/v1/", "https://github.com/other/repo/releases/download/v1/")) {
            assertThrows(IllegalArgumentException::class.java) { VmImageCatalogue.parse(manifest(base = base)) }
        }
        assertThrows(IllegalArgumentException::class.java) { VmImageCatalogue.parse(manifest(file = "../kernel")) }
        assertThrows(IllegalArgumentException::class.java) { VmImageCatalogue.parse(" ".repeat(16385)) }
        assertThrows(IllegalArgumentException::class.java) { VmImageCatalogue.parse(manifest().replace("4294967296", "4294967297")) }
    }
}
