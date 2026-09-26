package com.linex.app.core

import org.junit.Assert.*
import org.junit.Test
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import org.tukaani.xz.XZOutputStream
import org.tukaani.xz.LZMA2Options

class RootfsArchiveTest {
    @Test fun acceptsLiteralSystemdEscapesOnPosix() {
        RootfsArchive.validateArchiveName("/usr/lib/systemd/system/system-systemd\\x2dcryptsetup.slice", "/")
        RootfsArchive.validateArchiveName("../system-systemd\\x2dcryptsetup.slice", "/")
    }

    @Test fun rejectsBackslashSeparatorsOnWindows() {
        assertThrows(java.io.IOException::class.java) {
            RootfsArchive.validateArchiveName("..\\outside", "\\")
        }
    }

    @Test fun rejectsNulOnEveryFilesystem() {
        for (separator in listOf("/", "\\")) {
            assertThrows(java.io.IOException::class.java) {
                RootfsArchive.validateArchiveName("bad\u0000name", separator)
            }
        }
    }

    @Test fun preservesDotTargetForX11DirectorySymlink() {
        val parent = java.nio.file.Paths.get("root", "usr", "bin").toAbsolutePath()
        val target = parent.resolve(".").normalize()
        assertEquals(".", RootfsArchive.relativeSymlinkTarget(parent, target).toString())
    }

    @Test fun preservesNormalRelativeSymlinkTargets() {
        val parent = java.nio.file.Paths.get("root", "usr", "bin").toAbsolutePath()
        assertEquals("dash", RootfsArchive.relativeSymlinkTarget(parent, parent.resolve("dash")).toString())
    }

    @Test fun preservesLegacyInstalledRootfs() {
        val dir = Files.createTempDirectory("linex-legacy").toFile()
        try {
            File(dir, "usr/bin").mkdirs()
            File(dir, "usr/bin/sh").writeText("shell")
            File(dir, ".linex_initialized").writeText("VERSION=1.0.0\nSTATUS=READY\n")
            assertTrue(RootfsArchive.isReady(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun resolvesHardLinkWhoseTargetAppearsLater() {
        val dir = Files.createTempDirectory("linex-links").toFile()
        try {
            val input = File(dir, "rootfs.tar")
            TarArchiveOutputStream(input.outputStream()).use { tar ->
                tar.putArchiveEntry(TarArchiveEntry("usr/bin/sh", org.apache.commons.compress.archivers.tar.TarConstants.LF_LINK).apply {
                    linkName = "usr/bin/dash"
                })
                tar.closeArchiveEntry()
                tar.putArchiveEntry(TarArchiveEntry("usr/bin/dash").apply { size = 5 })
                tar.write("shell".toByteArray())
                tar.closeArchiveEntry()
            }
            val root = File(dir, "root")
            RootfsArchive.extract(input, root)
            assertEquals("shell", File(root, "usr/bin/sh").readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun rejectsSymlinkOutsideGuestRoot() {
        val dir = Files.createTempDirectory("linex-link-escape").toFile()
        try {
            val input = File(dir, "rootfs.tar")
            TarArchiveOutputStream(input.outputStream()).use { tar ->
                tar.putArchiveEntry(TarArchiveEntry("escape", org.apache.commons.compress.archivers.tar.TarConstants.LF_SYMLINK).apply {
                    linkName = "../outside"
                })
                tar.closeArchiveEntry()
            }
            assertThrows(java.io.IOException::class.java) { RootfsArchive.extract(input, File(dir, "root")) }
            assertFalse(File(dir, "outside").exists())
        } finally { dir.deleteRecursively() }
    }

    private fun archive(dir: File, entries: List<Pair<String, String>>): File {
        val file = File(dir, "rootfs.tar.gz")
        TarArchiveOutputStream(GZIPOutputStream(file.outputStream())).use { tar ->
            for ((name, contents) in entries) {
                val bytes = contents.toByteArray()
                val entry = TarArchiveEntry(name, true).apply { size = bytes.size.toLong() }
                tar.putArchiveEntry(entry); tar.write(bytes); tar.closeArchiveEntry()
            }
        }
        return file
    }

    @Test fun extractsSmallGzipArchiveDespiteMisleadingFileExtension() {
        val dir = Files.createTempDirectory("linex-test").toFile()
        try {
            val input = archive(dir, listOf("./usr/bin/sh" to "shell"))
            val root = File(dir, "root").apply { mkdir() }
            RootfsArchive.extract(input, root)
            assertEquals("shell", File(root, "usr/bin/sh").readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun rejectsTraversalWithoutWritingOutsideRoot() {
        val dir = Files.createTempDirectory("linex-test").toFile()
        try {
            val input = archive(dir, listOf("../escaped" to "bad"))
            val root = File(dir, "root").apply { mkdir() }
            assertThrows(java.io.IOException::class.java) { RootfsArchive.extract(input, root) }
            assertFalse(File(dir, "escaped").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun rejectsTruncatedArchive() {
        val dir = Files.createTempDirectory("linex-test").toFile()
        try {
            val input = archive(dir, listOf("usr/bin/sh" to "shell"))
            val data = input.readBytes(); input.writeBytes(data.copyOf(data.size / 2))
            assertThrows(java.io.IOException::class.java) { RootfsArchive.extract(input, File(dir, "root")) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun markerAloneCannotMarkPartialExtractionReady() {
        val dir = Files.createTempDirectory("linex-test").toFile()
        try {
            File(dir, ".linex_initialized").writeText("VERSION=2\nSTATUS=READY\n")
            assertFalse(RootfsArchive.isReady(dir))
            File(dir, "usr/bin").mkdirs()
            File(dir, "usr/bin/sh").writeText("shell")
            assertTrue(RootfsArchive.isReady(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun extractsXzContentEvenWhenDownloadIsNamedTarGz() {
        val dir = Files.createTempDirectory("linex-test").toFile()
        try {
            val input = File(dir, "rootfs.tar.gz")
            TarArchiveOutputStream(XZOutputStream(input.outputStream(), LZMA2Options())).use { tar ->
                val entry = TarArchiveEntry("usr/bin/sh").apply { size = 5 }
                tar.putArchiveEntry(entry); tar.write("shell".toByteArray()); tar.closeArchiveEntry()
            }
            val root = File(dir, "root")
            RootfsArchive.extract(input, root)
            assertEquals("shell", File(root, "usr/bin/sh").readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun absoluteGuestEntriesStayInsideDestination() {
        val dir = Files.createTempDirectory("linex-test").toFile()
        try {
            val input = archive(dir, listOf("/usr/bin/sh" to "shell"))
            val root = File(dir, "root")
            RootfsArchive.extract(input, root)
            assertEquals("shell", File(root, "usr/bin/sh").readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun cancellationDoesNotContinueUnpacking() {
        val dir = Files.createTempDirectory("linex-test").toFile()
        try {
            val input = archive(dir, listOf("usr/bin/sh" to "shell"))
            assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                RootfsArchive.extract(input, File(dir, "root"), checkCancelled = { throw kotlinx.coroutines.CancellationException() })
            }
            assertFalse(File(dir, "root/usr/bin/sh").exists())
        } finally { dir.deleteRecursively() }
    }
}
