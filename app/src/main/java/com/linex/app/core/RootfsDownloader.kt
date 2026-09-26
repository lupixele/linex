package com.linex.app.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class RootfsDownloader(private val storageEngine: StorageEngine, private val client: OkHttpClient = defaultClient) {
    companion object {
        private const val TAG = "RootfsDownloader"
        private val installationLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()
        }
    }

    suspend fun download(url: String, destFile: File, onProgress: (Float) -> Unit,
                         instanceId: String? = null): Boolean = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        fun digest(file: File): String {
            val sha = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    context.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    sha.update(buffer, 0, count)
                }
            }
            return sha.digest().joinToString("") { "%02x".format(it) }
        }
        if (DownloadReceipt.matches(destFile, url, ::digest)) {
            AppLogger.log(TAG, "Reusing verified download (${destFile.length()} bytes)", instanceId)
            onProgress(1f)
            return@withContext true
        }
        val parent = destFile.parentFile ?: throw IOException("Download directory missing")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create download directory")
        val partial = File(destFile.path + ".download")
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", "Linex/${com.linex.app.BuildConfig.VERSION_NAME}").build())
        AppLogger.log(TAG, "Downloading $url", instanceId)
        try {
            coroutineScope {
                // Closing the HTTP call unblocks a socket read immediately when setup is cancelled.
                val cancelWatcher = launch(Dispatchers.IO) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    call.execute().use { response ->
                        AppLogger.log(TAG, "HTTP ${response.code}", instanceId)
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${response.message}")
                        val body = response.body ?: throw IOException("Server returned an empty response")
                        val length = body.contentLength()
                        var received = 0L
                        body.byteStream().use { input ->
                            partial.outputStream().buffered().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    context.ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                    received += count
                                    onProgress(if (length > 0) (received.toFloat() / length).coerceIn(0f, 1f) else -1f)
                                }
                            }
                        }
                        if (received == 0L || (length >= 0 && received != length)) throw IOException("Incomplete download: $received of $length bytes")
                    }
                } finally { cancelWatcher.cancel() }
            }
            context.ensureActive()
            val hash = digest(partial)
            java.nio.file.Files.move(partial.toPath(), destFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            DownloadReceipt.write(destFile, url, hash)
            AppLogger.log(TAG, "Download complete (${destFile.length()} bytes); unpacking next", instanceId)
            onProgress(1f)
            true
        } catch (e: Exception) {
            partial.delete()
            context.ensureActive()
            if (e is CancellationException) throw e
            AppLogger.log(TAG, "Download failed: ${e.message}", instanceId)
            throw e
        }
    }

    /** Download occupies 0..0.89; unpack/configuration 0.90..0.99; only a ready rootfs emits 1. */
    fun download(instanceId: String, url: String): Flow<Float> = channelFlow {
      installationLocks.getOrPut(storageEngine.getInstanceDirectory(instanceId).absolutePath) { Mutex() }.withLock {
        if (!android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
            throw IOException("These Linux images require an ARM64 Android device. No download was started.")
        }
        if (storageEngine.isInstanceInitialized(instanceId)) {
            AppLogger.log(TAG, "Instance is already installed", instanceId)
            send(1f)
            return@withLock
        }
        val archive = File(storageEngine.getInstanceDirectory(instanceId), "rootfs.tar.gz")
        var checkpoint = -1
        download(url, archive, { progress ->
            val percent = (progress * 100).toInt()
            if (percent >= 0 && percent / 10 != checkpoint) {
                checkpoint = percent / 10
                AppLogger.log(TAG, "Download $percent%", instanceId)
            }
            trySend(if (progress < 0) -1f else progress * 0.89f)
        }, instanceId)
        send(0.90f)
        storageEngine.extractRootfs(archive, storageEngine.getRootfsDirectory(instanceId)) { progress, _ ->
            trySend(0.90f + progress * 0.09f)
        }
        if (!storageEngine.isInstanceInitialized(instanceId)) throw IOException("Setup finished without a valid root filesystem")
        if (archive.delete()) File(archive.path + ".complete").delete()
        AppLogger.log(TAG, "Installation complete; ready to start", instanceId)
        send(1f)
      }
    }.flowOn(Dispatchers.IO)

    suspend fun downloadAndExtract(instanceId: String, url: String, onProgress: (Float) -> Unit = {}): Boolean {
        download(instanceId, url).collect(onProgress)
        return true
    }
}
