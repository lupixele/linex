package com.linex.vm.images

import android.os.StatFs
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.nio.ByteBuffer
import java.util.Properties
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

/** Foreground/background scheduling belongs to the app; this operation reports stages and honors cancellation. */
class VmImageInstaller(filesDirectory: File) {
    private val files = PrivateImageFiles(filesDirectory)

    suspend fun install(
        instanceId: String, image: PinnedVmImage, onProgress: (VmInstallProgress) -> Unit = {},
    ): InstalledVmImage = withContext(Dispatchers.IO) {
        checkedInstanceId(instanceId)
        files.locked("instance-$instanceId.lock") {
            readInstalledInternal(instanceId)?.let {
                if (it.imageId != image.imageId || it.revision != image.revision ||
                    it.kernelSha256 != image.kernel.sha256 || it.initramfsSha256 != image.initramfs.sha256 || it.diskBytes != image.diskBytes)
                    throw IOException("Existing instance belongs to a different image; preserving its disk")
                onProgress(VmInstallProgress(VmInstallStage.READY, it.diskBytes, it.diskBytes))
                return@locked it
            }
            val instances = files.directory(files.root(), "vm-instances")
            val target = files.directory(instances, instanceId, create = false)
            if (files.exists(target)) throw IOException("Existing instance data has no READY record; preserving its disk")
            onProgress(VmInstallProgress(VmInstallStage.PREPARE, 0, image.diskBytes))
            if (StatFs(instances.path).availableBytes < image.diskBytes + image.download.bytes + 8L * 1024 * 1024)
                throw IOException("Insufficient private storage for this VM image")
            val staging = files.directory(files.root(), "vm-image-staging")
            val stage = files.directory(staging, "$instanceId.${UUID.randomUUID().toString().replace("-", "")}")
            var failure: Throwable? = null
            try {
                val kernel = asset(image.kernel, VmInstallStage.DOWNLOAD_KERNEL, onProgress)
                val initramfs = asset(image.initramfs, VmInstallStage.DOWNLOAD_INITRAMFS, onProgress)
                val boot = publishBootFiles(image, kernel, initramfs)
                val compressed = asset(image.download, VmInstallStage.DOWNLOAD_DISK, onProgress)
                files.read(compressed, image.download.bytes).use { source ->
                    decodedStream(source, image.compression).use { decoded ->
                        files.create(File(stage, "disk.raw")).use { output ->
                            var offset = 0L
                            val context = coroutineContext
                            copyVerified(decoded, image.diskBytes, image.diskSha256, context,
                                { onProgress(VmInstallProgress(VmInstallStage.EXPAND_DISK, it, image.diskBytes)) }) { chunk, count ->
                                // Hash every byte, but avoid writing factory zeros as physical blocks.
                                if (containsData(chunk, count)) {
                                    output.channel.position(offset)
                                    val bytes = ByteBuffer.wrap(chunk, 0, count)
                                    while (bytes.hasRemaining()) output.channel.write(bytes)
                                }
                                offset += count
                            }
                            Os.ftruncate(output.fd, image.diskBytes)
                            output.fd.sync()
                        }
                    }
                }
                coroutineContext.ensureActive()
                onProgress(VmInstallProgress(VmInstallStage.VERIFY, image.diskBytes, image.diskBytes))
                files.regular(File(stage, "disk.raw"), image.diskBytes)
                val metadata = Properties().apply {
                    setProperty("schema", "1"); setProperty("instanceId", instanceId)
                    setProperty("imageId", image.imageId); setProperty("revision", image.revision)
                    setProperty("kernelSha256", image.kernel.sha256); setProperty("kernelBytes", image.kernel.bytes.toString())
                    setProperty("initramfsSha256", image.initramfs.sha256); setProperty("initramfsBytes", image.initramfs.bytes.toString())
                    setProperty("diskBytes", image.diskBytes.toString())
                }
                val encoded = ByteArrayOutputStream().also { metadata.store(it, "Linex verified VM installation") }.toByteArray()
                files.create(File(stage, "READY")).use { output -> output.write(encoded); output.fd.sync() }
                files.syncDirectory(stage)
                coroutineContext.ensureActive()
                if (files.exists(target)) throw IOException("Instance destination changed; preserving existing data")
                Os.rename(stage.path, target.path)
                files.syncDirectory(instances)
                val result = installed(instanceId, image.imageId, image.revision, target, boot,
                    image.kernel.sha256, image.initramfs.sha256, image.diskBytes)
                onProgress(VmInstallProgress(VmInstallStage.READY, image.diskBytes, image.diskBytes))
                result
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                try { files.removeFreshStage(stage) } catch (cleanup: Exception) {
                    if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
                }
            }
        }
    }

    /** Disk contents are mutable. Check its type/length, and verify immutable boot bytes only. */
    suspend fun readInstalled(instanceId: String): InstalledVmImage? = withContext(Dispatchers.IO) {
        checkedInstanceId(instanceId)
        files.locked("instance-$instanceId.lock") { readInstalledInternal(instanceId) }
    }

    /** A stopped mutable guest disk is copied under the same ownership lock as NativeVmService. */
    suspend fun clone(sourceId: String, newId: String, onProgress: (VmInstallProgress) -> Unit = {}): InstalledVmImage =
        withContext(Dispatchers.IO) {
            checkedInstanceId(sourceId); checkedInstanceId(newId)
            require(sourceId != newId) { "Clone requires a fresh instance identity" }
            val ordered = listOf(sourceId, newId).sorted()
            files.locked("instance-${ordered[0]}.lock") {
                files.locked("instance-${ordered[1]}.lock") {
                    val instances = files.directory(files.root(), "vm-instances", create = false)
                    val source = files.directory(instances, sourceId, create = false)
                    if (!files.exists(source)) throw IOException("Source VM instance is missing")
                    files.engineOwned(source) {
                        val original = readInstalledInternal(sourceId) ?: throw IOException("Source VM is not ready")
                        val target = files.directory(instances, newId, create = false)
                        if (files.exists(target)) throw IOException("Clone destination exists; preserving it")
                        if (StatFs(instances.path).availableBytes < original.diskBytes + 8L * 1024 * 1024)
                            throw IOException("Insufficient private storage for this VM copy")
                        val staging = files.directory(files.root(), "vm-image-staging")
                        val stage = files.directory(staging, "$newId.${UUID.randomUUID().toString().replace("-", "")}")
                        var failure: Throwable? = null
                        try {
                            onProgress(VmInstallProgress(VmInstallStage.CLONE_DISK, 0, original.diskBytes))
                            copyMutableDisk(original.diskFile, File(stage, "disk.raw"), original.diskBytes, onProgress)
                            val metadata = readMetadata(File(source, "READY")).apply { setProperty("instanceId", newId) }
                            writeMetadata(File(stage, "READY"), metadata)
                            files.syncDirectory(stage)
                            coroutineContext.ensureActive()
                            if (files.exists(target)) throw IOException("Clone destination changed; preserving it")
                            Os.rename(stage.path, target.path)
                            files.syncDirectory(instances)
                            val result = original.copy(instanceId = newId, diskFile = File(target, "disk.raw"))
                            onProgress(VmInstallProgress(VmInstallStage.READY, original.diskBytes, original.diskBytes))
                            result
                        } catch (error: Throwable) {
                            failure = error
                            throw error
                        } finally {
                            try { files.removeFreshStage(stage) } catch (cleanup: Exception) {
                                if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
                            }
                        }
                    }
                }
            }
        }

    /** Caller confirms deletion; active engines and unexpected data always fail closed. */
    suspend fun delete(instanceId: String, onProgress: (VmInstallProgress) -> Unit = {}): Unit =
        withContext(Dispatchers.IO) {
            checkedInstanceId(instanceId)
            files.locked("instance-$instanceId.lock") {
                val instances = files.directory(files.root(), "vm-instances", create = false)
                if (!files.exists(instances)) return@locked
                val instance = files.directory(instances, instanceId, create = false)
                if (!files.exists(instance)) return@locked
                files.engineOwned(instance) {
                    val installed = readInstalledInternal(instanceId) ?: throw IOException("VM is not ready; preserving its data")
                    files.checkedDeletableInstance(instance)
                    val staging = files.directory(files.root(), "vm-image-staging")
                    val retired = File(staging, "delete.$instanceId.${UUID.randomUUID().toString().replace("-", "")}")
                    onProgress(VmInstallProgress(VmInstallStage.DELETE, 0, installed.diskBytes))
                    coroutineContext.ensureActive()
                    // Remove the launch namespace atomically while the ownership inode is locked.
                    Os.rename(instance.path, retired.path)
                    files.syncDirectory(instances)
                    files.syncDirectory(staging)
                    try {
                        files.checkedDeletableInstance(retired)
                    } catch (error: Exception) {
                        // A newly discovered listener/file must preserve the original namespace too.
                        try {
                            if (files.exists(instance)) throw IOException("Cannot restore changed instance namespace")
                            Os.rename(retired.path, instance.path)
                            files.syncDirectory(instances)
                            files.syncDirectory(staging)
                        } catch (restore: Exception) { error.addSuppressed(restore) }
                        throw error
                    }
                    // Commit passed: synchronous bounded unlink cleanup completes despite cancellation.
                    files.removeOwnedDeletedStage(retired)
                    onProgress(VmInstallProgress(VmInstallStage.DELETE, installed.diskBytes, installed.diskBytes))
                }
            }
        }

    private suspend fun copyMutableDisk(source: File, target: File, bytes: Long, progress: (VmInstallProgress) -> Unit) {
        files.read(source, bytes).use { input ->
            files.create(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                var completed = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0 || count > bytes - completed) throw IOException("Mutable disk length changed during copy")
                    if (containsData(buffer, count)) {
                        output.channel.position(completed)
                        val chunk = ByteBuffer.wrap(buffer, 0, count)
                        while (chunk.hasRemaining()) output.channel.write(chunk)
                    }
                    completed += count
                    progress(VmInstallProgress(VmInstallStage.CLONE_DISK, completed, bytes))
                }
                if (completed != bytes) throw IOException("Mutable disk was truncated during copy")
                Os.ftruncate(output.fd, bytes)
                output.fd.sync()
            }
        }
    }

    private fun readMetadata(ready: File): Properties = files.read(ready).use { input ->
        val bytes = ByteArray(8193)
        var count = 0
        while (count < bytes.size) {
            val read = input.read(bytes, count, bytes.size - count)
            if (read < 0) break
            if (read == 0) throw IOException("Installation metadata made no progress")
            count += read
        }
        if (count > 8192) throw IOException("Installation metadata exceeds bounds")
        Properties().apply { load(ByteArrayInputStream(bytes, 0, count)) }
    }

    private fun writeMetadata(target: File, metadata: Properties) {
        val encoded = ByteArrayOutputStream().also { metadata.store(it, "Linex verified VM installation") }.toByteArray()
        files.create(target).use { output -> output.write(encoded); output.fd.sync() }
    }

    private suspend fun readInstalledInternal(instanceId: String): InstalledVmImage? {
        val instances = files.directory(files.root(), "vm-instances", create = false)
        if (!files.exists(instances)) return null
        val target = files.directory(instances, instanceId, create = false)
        if (!files.exists(target)) return null
        val ready = File(target, "READY")
        if (!files.exists(ready)) return null
        val metadata = readMetadata(ready)
        val keys = setOf("schema", "instanceId", "imageId", "revision", "kernelSha256", "kernelBytes", "initramfsSha256", "initramfsBytes", "diskBytes")
        if (metadata.stringPropertyNames() != keys || metadata.getProperty("schema") != "1" ||
            metadata.getProperty("instanceId") != instanceId) throw IOException("Invalid installed VM metadata")
        val kernel = VmImageAsset("kernel", metadata.getProperty("kernelSha256"), metadata.getProperty("kernelBytes").toLong(), VmAssetSource.PrivateFile("vm-image-fixtures/unused"))
        val initramfs = VmImageAsset("initramfs", metadata.getProperty("initramfsSha256"), metadata.getProperty("initramfsBytes").toLong(), VmAssetSource.PrivateFile("vm-image-fixtures/unused"))
        require(kernel.bytes <= 128L * 1024 * 1024 && initramfs.bytes <= 128L * 1024 * 1024)
        val diskBytes = metadata.getProperty("diskBytes").toLong()
        require(diskBytes in 1..32L * 1024 * 1024 * 1024)
        require(metadata.getProperty("imageId").matches(Regex("[a-z0-9][a-z0-9-]{0,63}")))
        require(metadata.getProperty("revision").matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")))
        val boot = bootDirectory(metadata.getProperty("imageId"), metadata.getProperty("revision"), create = false)
        if (!files.exists(boot)) throw IOException("Installed immutable VM boot files are missing")
        checkAsset(File(boot, "kernel"), kernel)
        checkAsset(File(boot, "initramfs"), initramfs)
        files.regular(File(target, "disk.raw"), diskBytes)
        return installed(instanceId, metadata.getProperty("imageId"), metadata.getProperty("revision"), target, boot,
            kernel.sha256, initramfs.sha256, diskBytes)
    }

    private fun installed(instanceId: String, imageId: String, revision: String, target: File, boot: File,
                          kernelSha: String, initramfsSha: String, diskBytes: Long) = InstalledVmImage(
        instanceId, imageId, revision, File(boot, "kernel"), kernelSha, File(boot, "initramfs"), initramfsSha,
        File(target, "disk.raw"), diskBytes)

    private fun bootDirectory(imageId: String, revision: String, create: Boolean): File {
        val images = files.directory(files.root(), "vm-images", create)
        val image = files.directory(images, imageId, create)
        return files.directory(image, revision, create = false)
    }

    /** Boot files are shared immutable bytes; only a fresh verified directory may be published. */
    private suspend fun publishBootFiles(image: PinnedVmImage, kernel: File, initramfs: File): File =
        files.locked("boot-${image.imageId}-${image.revision}.lock") {
            val target = bootDirectory(image.imageId, image.revision, create = true)
            if (files.exists(target)) {
                checkAsset(File(target, "kernel"), image.kernel)
                checkAsset(File(target, "initramfs"), image.initramfs)
                return@locked target
            }
            val staging = files.directory(files.root(), "vm-image-staging")
            val stage = files.directory(staging, "boot.${UUID.randomUUID().toString().replace("-", "")}")
            var failure: Throwable? = null
            try {
                copyAsset(kernel, File(stage, "kernel"), image.kernel)
                copyAsset(initramfs, File(stage, "initramfs"), image.initramfs)
                files.syncDirectory(stage)
                coroutineContext.ensureActive()
                if (files.exists(target)) throw IOException("Immutable boot destination changed")
                Os.rename(stage.path, target.path)
                files.syncDirectory(target.parentFile!!)
                target
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                try { files.removeFreshStage(stage) } catch (cleanup: Exception) {
                    if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
                }
            }
        }

    private suspend fun checkAsset(path: File, asset: VmImageAsset) {
        files.read(path, asset.bytes).use { source ->
            copyVerified(source, asset.bytes, asset.sha256, coroutineContext, {}, { _, _ -> })
        }
    }

    private suspend fun copyAsset(source: File, destination: File, asset: VmImageAsset) {
        files.read(source, asset.bytes).use { input ->
            files.create(destination).use { output ->
                copyVerified(input, asset.bytes, asset.sha256, coroutineContext, {}, { chunk, count -> output.write(chunk, 0, count) })
                output.fd.sync()
            }
        }
    }

    private suspend fun asset(asset: VmImageAsset, stage: VmInstallStage, progress: (VmInstallProgress) -> Unit): File =
        files.locked("asset-${asset.sha256}.lock") {
            val cache = files.directory(files.root(), "vm-image-cache")
            val target = File(cache, asset.sha256)
            if (files.exists(target)) {
                checkAsset(target, asset)
                progress(VmInstallProgress(stage, asset.bytes, asset.bytes))
                return@locked target
            }
            val partial = File(cache, "${asset.sha256}.${UUID.randomUUID().toString().replace("-", "")}")
            try {
                openSource(asset).use { input ->
                    files.create(partial).use { output ->
                        copyVerified(input, asset.bytes, asset.sha256, coroutineContext,
                            { progress(VmInstallProgress(stage, it, asset.bytes)) }, { chunk, count -> output.write(chunk, 0, count) })
                        output.fd.sync()
                    }
                }
                coroutineContext.ensureActive()
                if (files.exists(target)) throw IOException("Immutable asset destination changed")
                Os.rename(partial.path, target.path)
                files.syncDirectory(cache)
                target
            } finally {
                if (files.exists(partial)) { files.regular(partial); if (!partial.delete()) throw IOException("Cannot remove partial asset") }
            }
        }

    private fun openSource(asset: VmImageAsset): InputStream = when (val source = asset.source) {
        is VmAssetSource.PrivateFile -> {
            var directory = files.root()
            val components = source.relativePath.split('/')
            for (component in components.dropLast(1)) directory = files.directory(directory, component, create = false)
            files.read(File(directory, components.last()), asset.bytes)
        }
        is VmAssetSource.Https -> {
            var address = checkedHttpsUrl(source.url).toURL()
            var stream: InputStream? = null
            for (redirect in 0..5) {
                val connection = address.openConnection() as HttpsURLConnection
                connection.connectTimeout = 15000; connection.readTimeout = 15000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "Linex-VM-Image/1")
                try {
                    val status = connection.responseCode
                    if (status in listOf(301, 302, 303, 307, 308)) {
                        val location = connection.getHeaderField("Location") ?: throw IOException("Image redirect is missing")
                        address = checkedHttpsUrl(URL(address, location).toString()).toURL()
                        connection.disconnect()
                        continue
                    }
                    if (status != 200) throw IOException("Image HTTP response $status")
                    val declared = connection.contentLengthLong
                    if (declared != -1L && declared != asset.bytes) throw IOException("Image HTTP length differs from pin")
                    stream = object : java.io.FilterInputStream(connection.inputStream) {
                        override fun close() { try { super.close() } finally { connection.disconnect() } }
                    }
                    break
                } catch (error: Exception) { connection.disconnect(); throw error }
            }
            stream ?: throw IOException("Too many image HTTPS redirects")
        }
    }
}
