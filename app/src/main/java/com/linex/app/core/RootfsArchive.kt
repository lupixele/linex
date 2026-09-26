package com.linex.app.core

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Android system tar lacks consistent compression support. Extract in process instead. */
internal object RootfsArchive {
    internal fun validateArchiveName(name: String, separator: String) {
        // Backslashes are literal filename characters on Android/Linux (including
        // systemd's escaped unit names). Reject them only on non-POSIX hosts.
        if (name.contains('\u0000') || (separator != "/" && name.contains('\\'))) {
            throw IOException("Invalid archive path: $name")
        }
    }

    internal fun relativeSymlinkTarget(parent: Path, target: Path): Path {
        val relative = parent.relativize(target)
        return if (relative.toString().isEmpty()) parent.fileSystem.getPath(".") else relative
    }

    private fun entryPath(root: Path, name: String): Path {
        validateArchiveName(name, root.fileSystem.separator)
        val relative = name.trimStart('/')
        if (relative.split('/').any { it == ".." }) throw IOException("Unsafe archive path: $name")
        val path = root.resolve(relative).normalize()
        if (!path.startsWith(root)) throw IOException("Archive path escapes root: $name")
        return path
    }

    private fun checked(root: Path, path: Path): Path {
        if (!path.toFile().canonicalFile.toPath().startsWith(root)) throw IOException("Archive link escapes root: $path")
        return path
    }

    fun extract(archive: File, destination: File, checkCancelled: () -> Unit = {},
                permissions: (File, Int) -> Unit = { _, _ -> }, progress: (Int) -> Unit = {}) {
        destination.mkdirs()
        val root = destination.canonicalFile.toPath()
        val hardLinks = mutableListOf<Pair<Path, Path>>()
        var entries = 0
        archive.inputStream().buffered().use { raw ->
            raw.mark(16)
            val signature = ByteArray(6)
            val count = raw.read(signature)
            raw.reset()
            val compressed = count >= 2 && (
                (signature[0] == 0x1f.toByte() && signature[1] == 0x8b.toByte()) ||
                (signature[0] == 0xfd.toByte() && signature[1] == 0x37.toByte()) ||
                (signature[0] == 'B'.code.toByte() && signature[1] == 'Z'.code.toByte()))
            val stream = if (compressed) CompressorStreamFactory(true, 128 * 1024).createCompressorInputStream(raw) else raw
            TarArchiveInputStream(stream).use { tar ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkCancelled()
                    val entry = tar.nextTarEntry ?: break
                    if (!tar.canReadEntryData(entry)) throw IOException("Unsupported archive entry: ${entry.name}")
                    val path = checked(root, entryPath(root, entry.name))
                    if (path == root) continue
                    Files.createDirectories(checked(root, path.parent))
                    when {
                        entry.isDirectory -> {
                            Files.createDirectories(path)
                            permissions(path.toFile(), entry.mode or 0x1c0)
                        }
                        entry.isSymbolicLink -> {
                            val link = entry.linkName
                            validateArchiveName(link, root.fileSystem.separator)
                            val target = if (link.startsWith('/')) root.resolve(link.trimStart('/')).normalize()
                                else path.parent.resolve(link).normalize()
                            if (!target.startsWith(root)) throw IOException("Link escapes root: ${entry.name}")
                            checked(root, target)
                            Files.deleteIfExists(path)
                            // relativize returns an empty path for X11 -> its own
                            // parent. POSIX symlink requires the literal "." instead.
                            val linkTarget = relativeSymlinkTarget(path.parent, target)
                            try {
                                Files.createSymbolicLink(path, linkTarget)
                            } catch (e: IOException) {
                                throw IOException("Cannot create archive symlink '${entry.name}' -> '$link' " +
                                    "(resolved target '$linkTarget'): ${e.javaClass.simpleName}: ${e.message}", e)
                            }
                        }
                        entry.isLink -> hardLinks.add(path to entryPath(root, entry.linkName))
                        entry.isFile -> {
                            if (Files.isSymbolicLink(path)) Files.delete(path)
                            Files.newOutputStream(checked(root, path)).use { output ->
                                while (true) {
                                    checkCancelled()
                                    val read = tar.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                }
                            }
                            permissions(path.toFile(), entry.mode or 0x180)
                        }
                        entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO -> Unit
                        else -> throw IOException("Unsupported entry: ${entry.name}")
                    }
                    entries++
                    if (entries % 500 == 0) progress(entries)
                }
                // Read compressor trailer too, so CRC/truncation errors fail the installation.
                while (stream.read(buffer) != -1) checkCancelled()
            }
        }
        while (hardLinks.isNotEmpty()) {
            checkCancelled()
            val before = hardLinks.size
            val iterator = hardLinks.iterator()
            while (iterator.hasNext()) {
                val (path, target) = iterator.next()
                if (Files.isRegularFile(checked(root, target), NOFOLLOW_LINKS)) {
                    checked(root, path.parent)
                    Files.deleteIfExists(path)
                    try {
                        Files.createLink(path, target)
                    } catch (_: java.nio.file.FileSystemException) {
                        // Some Android kernels/SELinux policies disallow hard links in app data.
                        Files.copy(target, path, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES)
                    }
                    iterator.remove()
                }
            }
            if (hardLinks.size == before) throw IOException("Archive has unresolved hard links")
        }
        if (entries == 0) throw IOException("Archive contains no rootfs files")
        progress(entries)
    }

    fun hasShell(root: File): Boolean = listOf("bin/sh", "usr/bin/sh", "bin/bash", "usr/bin/bash").any {
        val file = File(root, it)
        file.isFile && file.length() > 0 && file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())
    }

    fun isReady(root: File): Boolean = try {
        val marker = File(root, ".linex_initialized")
        val lines = if (marker.isFile) marker.readLines() else emptyList()
        // Preserve existing installed systems; old versions used VERSION=1.0.0.
        "STATUS=READY" in lines && hasShell(root)
    } catch (_: IOException) { false }
}
