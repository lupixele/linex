package com.linex.app.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.*

/**
 * StorageEngine manages instance rootfs hierarchies, temporary directories,
 * runtime script staging, and rootfs decompression/bootstrap.
 */
class StorageEngine(
    private val context: Context,
    private val processController: ProcessController = ProcessController()
) {

    companion object {
        private const val TAG = "StorageEngine"
    }

    val baseInstancesDir: File
        get() = File(context.filesDir, "instances").apply { if (!exists()) mkdirs() }

    val runtimeDir: File
        get() = File(context.filesDir, "runtime").apply { if (!exists()) mkdirs() }

    val scriptsDir: File
        get() = File(runtimeDir, "scripts").apply { if (!exists()) mkdirs() }

    val configDir: File
        get() = File(runtimeDir, "config").apply { if (!exists()) mkdirs() }

    fun getInstanceDirectory(instanceId: String): File {
        require(instanceId.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid instance ID" }
        return File(baseInstancesDir, instanceId).apply { if (!exists()) mkdirs() }
    }

    fun getRootfsDirectory(instanceId: String): File {
        val root = File(getInstanceDirectory(instanceId), "rootfs")
        val previous = File(root.parentFile, "rootfs.previous")
        if (!root.exists() && previous.isDirectory) {
            java.nio.file.Files.move(previous.toPath(), root.toPath())
        }
        return root
    }

    fun getTmpDirectory(instanceId: String): File {
        return File(getInstanceDirectory(instanceId), "tmp").apply { if (!exists()) mkdirs() }
    }

    /**
     * Deploys all bundled scripts, configs, and runtime tools from APK assets to the app's internal executable storage.
     */
    suspend fun deployAssets(overwrite: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Deploying Linex assets from APK to ${runtimeDir.absolutePath}...")
            copyAssetFolder("scripts", scriptsDir, overwrite, makeExecutable = true)
            copyAssetFolder("config", configDir, overwrite, makeExecutable = false)

            // Specifically ensure in_container scripts have +x
            val inContainerDir = File(scriptsDir, "in_container")
            if (inContainerDir.exists()) {
                inContainerDir.walkTopDown().forEach { file ->
                    if (file.isFile && file.name.endsWith(".sh")) {
                        file.setExecutable(true, false)
                        processController.setFilePermissions(file.absolutePath, 0b111101101) // 0755
                    }
                }
            }

            Log.i(TAG, "Asset deployment completed successfully.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Asset deployment failed", e)
            false
        }
    }

    private fun copyAssetFolder(assetPath: String, targetDir: File, overwrite: Boolean, makeExecutable: Boolean) {
        val assetManager = context.assets
        val list = assetManager.list(assetPath) ?: return

        if (!targetDir.exists()) targetDir.mkdirs()

        for (item in list) {
            val subAssetPath = if (assetPath.isEmpty()) item else "$assetPath/$item"
            val subItems = assetManager.list(subAssetPath)

            if (subItems != null && subItems.isNotEmpty()) {
                // Subdirectory
                val subTargetDir = File(targetDir, item)
                copyAssetFolder(subAssetPath, subTargetDir, overwrite, makeExecutable)
            } else {
                // File
                val targetFile = File(targetDir, item)
                if (!targetFile.exists() || overwrite) {
                    try {
                        assetManager.open(subAssetPath).use { input ->
                            FileOutputStream(targetFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (makeExecutable) {
                            targetFile.setReadable(true, false)
                            targetFile.setWritable(true, true)
                            targetFile.setExecutable(true, false)
                            processController.setFilePermissions(targetFile.absolutePath, 0b111101101) // 0755
                        }
                    } catch (e: Exception) {
                        throw IOException("Failed to copy asset $subAssetPath", e)
                    }
                }
            }
        }
    }

    /** A marker and a usable shell are both required; partial extraction is never ready. */
    fun isInstanceInitialized(instanceId: String): Boolean =
        RootfsArchive.isReady(getRootfsDirectory(instanceId))

    suspend fun extractRootfs(
        archiveFile: File,
        targetRootfs: File,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        val instanceId = targetRootfs.parentFile?.name
        val stage = File(targetRootfs.parentFile, "rootfs.installing")
        val previous = File(targetRootfs.parentFile, "rootfs.previous")
        fun report(progress: Float, message: String) {
            AppLogger.log(TAG, message, instanceId)
            onProgress(progress, message)
        }
        try {
            if (!archiveFile.isFile) throw IOException("Downloaded archive is missing")
            if (!targetRootfs.exists() && previous.exists()) {
                java.nio.file.Files.move(previous.toPath(), targetRootfs.toPath())
            }
            removeTree(stage)
            report(0.05f, "Unpacking archive; completed download is kept for retry")
            val coroutineContext = kotlinx.coroutines.currentCoroutineContext()
            RootfsArchive.extract(archiveFile, stage,
                checkCancelled = { coroutineContext.ensureActive() },
                permissions = { file, mode -> android.system.Os.chmod(file.absolutePath, mode and 0x1ff) },
                progress = { count -> report(0.65f, "Unpacked $count entries") })
            if (!RootfsArchive.hasShell(stage)) throw IOException("Archive has no usable Linux shell")
            report(0.85f, "Configuring network and shell")
            if (!runFirstBootSetup(stage)) throw IOException("First-boot configuration failed")
            coroutineContext.ensureActive()
            val marker = File(stage, ".linex_initialized")
            java.nio.file.Files.deleteIfExists(marker.toPath())
            marker.writeText("VERSION=2\nSTATUS=READY\n")
            removeTree(previous)
            if (targetRootfs.exists()) java.nio.file.Files.move(targetRootfs.toPath(), previous.toPath())
            try {
                java.nio.file.Files.move(stage.toPath(), targetRootfs.toPath())
            } catch (e: Exception) {
                if (previous.exists()) java.nio.file.Files.move(previous.toPath(), targetRootfs.toPath())
                throw e
            }
            // Cleanup failure must not turn an already committed installation into a failed setup.
            try { removeTree(previous) } catch (e: IOException) {
                report(0.99f, "Installed; old partial files could not be cleaned: ${e.message}")
            }
            report(1f, "Root filesystem ready")
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            report(0f, "Setup cancelled; downloaded archive retained")
            throw e
        } catch (e: Exception) {
            report(0f, "Extraction failed: ${e.message}. Download retained for retry.")
            throw IOException("Root filesystem setup failed: ${e.message}", e)
        }
    }

    /** Apply bootstrap files without depending on Android shell tools or following host symlinks. */
    suspend fun runFirstBootSetup(rootfsDir: File): Boolean = withContext(Dispatchers.IO) {
        val root = rootfsDir.canonicalFile.toPath()
        fun directory(name: String): File {
            val file = File(rootfsDir, name)
            if (!file.canonicalFile.toPath().startsWith(root)) throw IOException("Unsafe bootstrap directory: $name")
            java.nio.file.Files.createDirectories(file.toPath())
            return file
        }
        fun write(name: String, text: String) {
            val file = File(rootfsDir, name)
            directory(file.parentFile!!.relativeTo(rootfsDir).path)
            // resolv.conf often points at systemd's absent /run state; replace the link itself.
            java.nio.file.Files.deleteIfExists(file.toPath())
            file.writeText(text)
        }
        listOf("tmp", "dev/shm", "proc", "sys", "root", "sdcard", "etc/profile.d").forEach(::directory)
        android.system.Os.chmod(File(rootfsDir, "tmp").absolutePath, 0x3ff)
        write("etc/resolv.conf", "nameserver 1.1.1.1\nnameserver 8.8.8.8\noptions timeout:2 attempts:3\n")
        write("etc/hosts", "127.0.0.1 localhost linex\n::1 localhost ip6-localhost\n")
        write("etc/apt/apt.conf.d/99linex", "APT::Sandbox::User \"root\";\nAcquire::Languages \"none\";\n")
        write("etc/dpkg/dpkg.cfg.d/01_linex_nodoc", "path-exclude /usr/share/doc/*\npath-include /usr/share/doc/*/copyright\npath-exclude /usr/share/man/*\n")
        write("etc/profile.d/linex.sh", "export TERM=xterm-256color\nexport PULSE_SERVER=tcp:127.0.0.1:4713\n")
        write("etc/asound.conf", "pcm.!default { type pulse fallback \"sysdefault\" }\nctl.!default { type pulse }\n")
        true
    }

    private fun removeTree(dir: File) {
        val path = dir.toPath()
        if (!java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        java.nio.file.Files.walkFileTree(path, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                java.nio.file.Files.delete(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: java.nio.file.Path, error: IOException?): java.nio.file.FileVisitResult {
                if (error != null) throw error
                java.nio.file.Files.delete(dir)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }

    suspend fun cloneInstance(sourceId: String, newId: String, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        require(sourceId != newId) { "Source and destination must differ" }
        val source = getInstanceDirectory(sourceId).toPath()
        val destination = getInstanceDirectory(newId).toPath()
        require(destination.toFile().listFiles().isNullOrEmpty()) { "Clone destination is not empty" }
        val coroutineContext = kotlinx.coroutines.currentCoroutineContext()
        try {
            java.nio.file.Files.walkFileTree(source, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun preVisitDirectory(dir: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    coroutineContext.ensureActive()
                    java.nio.file.Files.createDirectories(destination.resolve(source.relativize(dir)))
                    return java.nio.file.FileVisitResult.CONTINUE
                }
                override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    coroutineContext.ensureActive()
                    java.nio.file.Files.copy(file, destination.resolve(source.relativize(file)), java.nio.file.LinkOption.NOFOLLOW_LINKS, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            })
            onProgress(1f)
        } catch (e: Exception) {
            try { removeTree(destination.toFile()) } catch (_: IOException) { }
            throw e
        }
    }

    suspend fun deleteInstance(instanceId: String) = withContext(Dispatchers.IO) {
        removeTree(getInstanceDirectory(instanceId))
    }
}
