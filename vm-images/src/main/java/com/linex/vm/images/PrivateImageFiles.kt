package com.linex.vm.images

import android.os.Process
import android.os.Build
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.OverlappingFileLockException
import java.nio.channels.FileLock
import kotlin.coroutines.coroutineContext

/** All paths are constructed below an Android-owned root; guest disks are never cleanup targets. */
internal class PrivateImageFiles(private val suppliedRoot: File) {
    private val android26OpenCloseOnExec = 0x80000
    fun root(): File {
        require(suppliedRoot.isAbsolute)
        val stat = Os.lstat(suppliedRoot.path)
        if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_uid != Process.myUid())
            throw IOException("Unsafe app image storage root")
        return suppliedRoot.canonicalFile
    }

    fun exists(file: File): Boolean = try { Os.lstat(file.path); true } catch (error: ErrnoException) {
        if (error.errno != OsConstants.ENOENT) throw IOException("Cannot inspect image path", error)
        false
    }

    fun directory(parent: File, name: String, create: Boolean = true): File {
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,139}")) && name != "." && name != "..")
        val file = File(parent, name)
        if (!exists(file)) {
            if (!create) return file
            if (!file.mkdir() && !exists(file)) throw IOException("Cannot create private image directory")
            Os.chmod(file.path, 448)
        }
        val stat = Os.lstat(file.path)
        if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_uid != Process.myUid() ||
            stat.st_mode and 511 != 448 || file.canonicalFile != file)
            throw IOException("Image directory must be private and owned")
        return file
    }

    fun regular(file: File, bytes: Long? = null) {
        val stat = Os.lstat(file.path)
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_uid != Process.myUid() ||
            stat.st_mode and 511 != 384 || stat.st_nlink != 1L || (bytes != null && stat.st_size != bytes))
            throw IOException("Image file must be regular, private and owned")
    }

    fun create(file: File): FileOutputStream = FileOutputStream(Os.open(file.path,
        OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 384))

    fun read(file: File, bytes: Long? = null): FileInputStream {
        regular(file, bytes)
        val descriptor = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        val stat = Os.fstat(descriptor)
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_uid != Process.myUid() ||
            stat.st_mode and 511 != 384 || stat.st_nlink != 1L || (bytes != null && stat.st_size != bytes)) {
            Os.close(descriptor)
            throw IOException("Image changed while opening")
        }
        return FileInputStream(descriptor)
    }

    fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try {
            if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) throw IOException("Image sync target is not a directory")
            Os.fsync(descriptor)
        } finally { Os.close(descriptor) }
    }

    suspend fun <T> locked(name: String, block: suspend () -> T): T {
        val locks = directory(root(), "vm-image-locks")
        val file = File(locks, name)
        val descriptor = Os.open(file.path, OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_NOFOLLOW, 384)
        val stat = Os.fstat(descriptor)
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_uid != Process.myUid() || stat.st_mode and 511 != 384 || stat.st_nlink != 1L) {
            Os.close(descriptor)
            throw IOException("Unsafe image operation lock")
        }
        return FileOutputStream(descriptor).use { stream ->
            var held: FileLock? = null
            while (held == null) {
                coroutineContext.ensureActive()
                held = try { stream.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (held == null) delay(100)
            }
            held.use { block() }
        }
    }

    /** NativeVmService holds this exact inode while QEMU owns the mutable disk. */
    suspend fun <T> engineOwned(instance: File, block: suspend () -> T): T {
        directory(instance.parentFile!!, instance.name, create = false)
        val path = File(instance, "engine.lock")
        // Public NDK asm-generic/fcntl.h defines O_CLOEXEC as 02000000 on both
        // supported 64-bit Android ABIs; its Java field appeared only in API27.
        val closeOnExec = if (Build.VERSION.SDK_INT >= 27) OsConstants.O_CLOEXEC else android26OpenCloseOnExec
        val descriptor = Os.open(path.path, OsConstants.O_RDWR or OsConstants.O_CREAT or
            closeOnExec or OsConstants.O_NOFOLLOW, 384)
        return FileOutputStream(descriptor).use { stream ->
            regular(path)
            val held = try { stream.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                ?: throw IOException("Stop this VM before copying or deleting its disk")
            held.use {
                val opened = Os.fstat(descriptor)
                val named = Os.lstat(path.path)
                if (!OsConstants.S_ISREG(opened.st_mode) || opened.st_uid != Process.myUid() ||
                    opened.st_nlink != 1L || opened.st_mode and 511 != 384 ||
                    opened.st_ino != named.st_ino || opened.st_dev != named.st_dev)
                    throw IOException("Instance ownership lock changed")
                block()
            }
        }
    }

    fun checkedDeletableInstance(instance: File): List<File> {
        directory(instance.parentFile!!, instance.name, create = false)
        val entries = instance.listFiles() ?: throw IOException("Cannot inspect private VM instance")
        for (entry in entries) {
            when {
                entry.name in setOf("disk.raw", "READY", "engine.lock") -> regular(entry)
                entry.name.matches(Regex("s[a-f0-9]{8}")) -> {
                    val metadata = Os.lstat(entry.path)
                    if (!OsConstants.S_ISSOCK(metadata.st_mode) || metadata.st_uid != Process.myUid() ||
                        metadata.st_mode and 511 != 384 || metadata.st_nlink != 1L)
                        throw IOException("Unexpected serial endpoint; preserving instance")
                    LocalSocket().use { probe ->
                        // soTimeout sets the supported Unix connect-send deadline too.
                        probe.outputStream
                        probe.soTimeout = 1000
                        try {
                            probe.connect(LocalSocketAddress(entry.path, LocalSocketAddress.Namespace.FILESYSTEM))
                        } catch (error: IOException) {
                            val reason = error.cause as? ErrnoException
                            if (reason?.errno == OsConstants.ECONNREFUSED) return@use
                            throw IOException("Cannot establish that the serial endpoint is stale", error)
                        }
                        throw IOException("Serial endpoint is active; stop this VM before deletion")
                    }
                }
                else -> throw IOException("Unexpected instance data; preserving it")
            }
        }
        return entries.toList()
    }

    /** Called only after a checked directory was moved out of the launch namespace under ownership lock. */
    fun removeOwnedDeletedStage(stage: File) {
        val base = directory(root(), "vm-image-staging", create = false)
        if (stage.parentFile != base || !stage.name.startsWith("delete."))
            throw IOException("Deletion cleanup escaped private staging")
        val entries = checkedDeletableInstance(stage)
        for (entry in entries) if (!entry.delete()) throw IOException("Cannot remove owned deleted VM file")
        if (!stage.delete()) throw IOException("Cannot remove owned deleted VM directory")
        syncDirectory(base)
    }

    fun removeFreshStage(stage: File) {
        if (!exists(stage)) return
        val base = directory(root(), "vm-image-staging", create = false)
        if (stage.parentFile != base) throw IOException("Stage cleanup escaped owned image staging")
        directory(base, stage.name, create = false)
        val files = stage.listFiles() ?: throw IOException("Cannot inspect owned stage")
        if (files.any { it.name !in setOf("kernel", "initramfs", "disk.raw", "READY") })
            throw IOException("Unexpected data in owned stage; preserving it")
        files.forEach { regular(it); if (!it.delete()) throw IOException("Cannot remove partial image asset") }
        if (!stage.delete()) throw IOException("Cannot remove owned image staging directory")
    }
}
