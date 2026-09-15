package com.linex.app.core

import android.content.Context
import android.os.Build
import android.util.Log
import com.linex.app.data.DistroType
import kotlinx.coroutines.Dispatchers
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
        return File(baseInstancesDir, instanceId).apply { if (!exists()) mkdirs() }
    }

    fun getRootfsDirectory(instanceId: String): File {
        return File(getInstanceDirectory(instanceId), "rootfs").apply { if (!exists()) mkdirs() }
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
                        Log.w(TAG, "Failed to copy asset file $subAssetPath to ${targetFile.absolutePath}", e)
                    }
                }
            }
        }
    }

    /**
     * Checks if an instance has already completed rootfs extraction and first boot setup.
     */
    fun isInstanceInitialized(instanceId: String): Boolean {
        val rootfs = getRootfsDirectory(instanceId)
        val marker = File(rootfs, ".linex_initialized")
        val hasSh = File(rootfs, "bin/sh").exists() || File(rootfs, "usr/bin/sh").exists() || File(rootfs, "bin/bash").exists()
        return marker.exists() || hasSh
    }

    /**
     * Extracts an archive (.tar, .tar.gz, .tar.xz) into the instance's rootfs folder.
     */
    suspend fun extractRootfs(
        archiveFile: File,
        targetRootfs: File,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        if (!archiveFile.exists()) {
            onProgress(0f, "Error: Archive not found: ${archiveFile.absolutePath}")
            return@withContext false
        }

        if (!targetRootfs.exists()) targetRootfs.mkdirs()

        onProgress(0.05f, "Preparing extraction helper...")
        deployAssets(overwrite = false)

        val extractScript = File(scriptsDir, "rootfs_extract.sh")
        if (!extractScript.exists()) {
            deployAssets(overwrite = true)
        }

        if (extractScript.exists()) {
            extractScript.setExecutable(true, false)
            processController.setFilePermissions(extractScript.absolutePath, 0b111101101) // 0755
            onProgress(0.10f, "Executing rootfs_extract.sh...")

            try {
                val shBinary = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "sh"
                val pb = ProcessBuilder(
                    shBinary,
                    extractScript.absolutePath,
                    archiveFile.absolutePath,
                    targetRootfs.absolutePath
                )
                pb.directory(scriptsDir)
                pb.redirectErrorStream(true)
                val process = pb.start()

                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val currentLine = line ?: continue
                        Log.i(TAG, "[Extract] $currentLine")
                        val progressFraction = when {
                            currentLine.contains("Preparing target directory") -> 0.15f
                            currentLine.contains("Inspecting archive format") -> 0.25f
                            currentLine.contains("Detected Gzip compressed") -> 0.35f
                            currentLine.contains("Executing extraction") -> 0.50f
                            currentLine.contains("Rootfs successfully extracted") -> 0.85f
                            else -> 0.50f
                        }
                        onProgress(progressFraction, currentLine)
                    }
                }

                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    onProgress(0.90f, "Running first-boot customization...")
                    try {
                        runFirstBootSetup(targetRootfs)
                    } catch (e: Exception) {
                        Log.w(TAG, "First-boot setup warning: ${e.message}")
                    }
                    // Guarantee the initialization marker is present
                    val marker = File(targetRootfs, ".linex_initialized")
                    if (!marker.exists()) {
                        marker.writeText("VERSION=1.0.0\nSTATUS=READY\n")
                    }
                    onProgress(1.0f, "Extraction complete!")
                    return@withContext true
                } else {
                    // Even if the script returned non-zero (e.g. tar symlink/permission warnings),
                    // check if essential binaries were extracted!
                    val hasBin = File(targetRootfs, "bin").exists() || File(targetRootfs, "usr/bin").exists()
                    if (hasBin) {
                        Log.w(TAG, "Extraction script had non-zero exit ($exitCode) but /bin or /usr exists. Marking ready.")
                        val marker = File(targetRootfs, ".linex_initialized")
                        marker.writeText("VERSION=1.0.0\nSTATUS=RECOVERED\n")
                        onProgress(1.0f, "Extraction complete!")
                        return@withContext true
                    }
                    onProgress(0f, "Extraction script failed with exit code $exitCode")
                    return@withContext false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to run rootfs_extract.sh", e)
                onProgress(0f, "Extraction process failed: ${e.message}")
                return@withContext false
            }
        } else {
            onProgress(0f, "Extraction helper script missing.")
            return@withContext false
        }
    }

    /**
     * Executes first_boot_setup.sh on a freshly unpacked rootfs.
     */
    suspend fun runFirstBootSetup(rootfsDir: File): Boolean = withContext(Dispatchers.IO) {
        val setupScript = File(scriptsDir, "first_boot_setup.sh")
        if (!setupScript.exists()) {
            Log.w(TAG, "first_boot_setup.sh not found at ${setupScript.absolutePath}")
            return@withContext false
        }

        setupScript.setExecutable(true, false)
        processController.setFilePermissions(setupScript.absolutePath, 0b111101101)
        try {
            val shBinary = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "sh"
            val pb = ProcessBuilder(shBinary, setupScript.absolutePath, rootfsDir.absolutePath)
            pb.directory(scriptsDir)
            pb.redirectErrorStream(true)
            val process = pb.start()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    Log.i(TAG, "[FirstBoot] $line")
                }
            }
            val code = process.waitFor()
            code == 0
        } catch (e: Exception) {
            Log.e(TAG, "first_boot_setup execution error", e)
            false
        }
    }

    suspend fun cloneInstance(sourceId: String, newId: String, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val src = getInstanceDirectory(sourceId)
        val dst = getInstanceDirectory(newId)
        if (dst.exists()) dst.deleteRecursively()
        dst.mkdirs()

        copyDirectoryWithProgress(src, dst, onProgress)
    }

    suspend fun deleteInstance(instanceId: String) = withContext(Dispatchers.IO) {
        val dir = getInstanceDirectory(instanceId)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    private fun copyDirectoryWithProgress(source: File, target: File, onProgress: (Float) -> Unit) {
        val files = source.walkTopDown().toList()
        val total = files.size
        var current = 0
        for (file in files) {
            val relative = file.relativeTo(source)
            val destFile = File(target, relative.path)
            if (file.isDirectory) {
                destFile.mkdirs()
            } else {
                file.copyTo(destFile, overwrite = true)
            }
            current++
            if (current % 50 == 0 || current == total) {
                onProgress(current.toFloat() / total.toFloat())
            }
        }
    }
}
