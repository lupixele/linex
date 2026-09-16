package com.linex.app.core

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * RootfsDownloader handles downloading rootfs distribution archives over HTTP/HTTPS
 * with progress tracking via Flow or callbacks, saving to the instance directory
 * as rootfs.tar.gz, and initiating extraction via StorageEngine.
 */
class RootfsDownloader(
    private val storageEngine: StorageEngine,
    private val client: OkHttpClient = defaultClient
) {
    companion object {
        private const val TAG = "RootfsDownloader"
        private const val BUFFER_SIZE = 16384 // 16KB buffer for efficient network I/O

        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }

    /**
     * Downloads an archive file from [url] directly to [destFile].
     * Invokes [onProgress] with values from 0.0f to 1.0f (or -1.0f if content-length is indeterminate).
     */
    suspend fun download(
        url: String,
        destFile: File,
        onProgress: (Float) -> Unit,
        instanceId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val parentDir = destFile.parentFile ?: File(".")
        if (!parentDir.exists()) {
            parentDir.mkdirs()
        }

        // Optimization: If archive is already fully downloaded on disk, verify and reuse!
        if (destFile.exists() && destFile.length() > 10 * 1024 * 1024L) {
            AppLogger.log(TAG, "Archive already downloaded (${destFile.length() / (1024 * 1024)} MB). Reusing existing file: ${destFile.name}", instanceId)
            onProgress(1.0f)
            return@withContext true
        }

        val tempFile = File(parentDir, "${destFile.name}.download")
        if (tempFile.exists()) {
            tempFile.delete()
        }

        AppLogger.log(TAG, "Initiating HTTP GET for: $url", instanceId)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Linex-RootfsDownloader/1.0")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                AppLogger.log(TAG, "HTTP response status: ${response.code} for $url", instanceId)
                if (!response.isSuccessful) {
                    throw IOException("HTTP error ${response.code}: ${response.message}")
                }

                val body = response.body ?: throw IOException("Empty response body from $url")
                val contentLength = body.contentLength()
                AppLogger.log(TAG, "Download started, size: ${contentLength / (1024 * 1024)} MB", instanceId)

                body.byteStream().use { inputStream ->
                    FileOutputStream(tempFile).use { outputStream ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        var totalRead = 0L

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (contentLength > 0L) {
                                val progress = (totalRead.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f)
                                onProgress(progress)
                            } else {
                                onProgress(-1f)
                            }
                        }
                        outputStream.flush()
                    }
                }
            }

            if (destFile.exists()) {
                destFile.delete()
            }
            if (!tempFile.renameTo(destFile)) {
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()
            }

            onProgress(1.0f)
            AppLogger.log(TAG, "Download finished: ${destFile.name} (${destFile.length()} bytes)", null)
            Log.i(TAG, "Download completed successfully: ${destFile.absolutePath} (${destFile.length()} bytes)")
            true
        } catch (e: Exception) {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            AppLogger.log(TAG, "Download EXCEPTION for $url: ${e.message}", null)
            if (e is CancellationException) {
                Log.w(TAG, "Download cancelled for $url")
                throw e
            }
            Log.e(TAG, "Failed to download $url: ${e.message}", e)
            throw e
        }
    }

    /**
     * Downloads the rootfs archive for [instanceId] to storageEngine.getInstanceDirectory(id)/rootfs.tar.gz.
     * Reports progress via Flow<Float>.
     * After download completes, calls StorageEngine.extractRootfs().
     */
    fun download(instanceId: String, url: String): Flow<Float> = channelFlow {
        val destFile = File(storageEngine.getInstanceDirectory(instanceId), "rootfs.tar.gz")
        val targetRootfs = storageEngine.getRootfsDirectory(instanceId)

        AppLogger.log(TAG, "Starting rootfs download pipeline for instance $instanceId from $url", instanceId)
        Log.i(TAG, "Starting rootfs download for instance $instanceId from $url")
        // Stream download progress (0.0 to 1.0)
        var lastEmitted = -1
        download(url, destFile, { progress: Float ->
            val p = (progress * 100).toInt()
            if (p != lastEmitted && (p % 10 == 0 || p == 100)) {
                lastEmitted = p
                AppLogger.log(TAG, "Download progress: $p%", instanceId)
            }
            trySend(progress)
        }, instanceId)

        AppLogger.log(TAG, "Download complete. Extracting archive to ${targetRootfs.absolutePath}...", instanceId)
        Log.i(TAG, "Download complete for $instanceId. Extracting archive to ${targetRootfs.absolutePath}...")
        trySend(0.99f)
        val extractSuccess = storageEngine.extractRootfs(destFile, targetRootfs) { extractProgress, status ->
            AppLogger.log(TAG, "[Extract ${(extractProgress * 100).toInt()}%] $status", instanceId)
            Log.d(TAG, "Extract progress: ${(extractProgress * 100).toInt()}% - $status")
        }

        if (!extractSuccess) {
            AppLogger.log(TAG, "ERROR: Failed to extract rootfs archive for instance $instanceId", instanceId)
            throw IOException("Failed to extract rootfs archive for instance $instanceId")
        }

        // Delete downloaded archive after successful extraction to reclaim storage
        try {
            if (destFile.exists()) {
                destFile.delete()
                AppLogger.log(TAG, "Deleted downloaded archive to free disk space", instanceId)
                Log.i(TAG, "Deleted downloaded archive ${destFile.name} to free disk space")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove downloaded archive: ${e.message}")
        }

        send(1.0f)
        AppLogger.log(TAG, "SUCCESS: Rootfs download and extraction completed for $instanceId", instanceId)
        Log.i(TAG, "Rootfs download and extraction completed successfully for $instanceId")
    }.flowOn(Dispatchers.IO)

    /**
     * Convenience suspend function to download and extract for an instance.
     */
    suspend fun downloadAndExtract(
        instanceId: String,
        url: String,
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val destFile = File(storageEngine.getInstanceDirectory(instanceId), "rootfs.tar.gz")
        val targetRootfs = storageEngine.getRootfsDirectory(instanceId)

        val downloadOk = download(url, destFile, onProgress)
        if (!downloadOk) return@withContext false

        storageEngine.extractRootfs(destFile, targetRootfs)
    }
}
