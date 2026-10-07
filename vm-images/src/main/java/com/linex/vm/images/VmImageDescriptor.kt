package com.linex.vm.images

import java.io.File
import java.net.URI

/** The app's externally gated release catalogue supplies these pins; installation cannot approve a release. */
enum class VmImageCompression { XZ, GZIP }

sealed interface VmAssetSource {
    data class Https(val url: String) : VmAssetSource {
        init { checkedHttpsUrl(url) }
    }
    /** Only app-private vm-image-fixtures/... files, for candidate Android tests. */
    data class PrivateFile(val relativePath: String) : VmAssetSource {
        init {
            require(relativePath.length <= 256 && relativePath.startsWith("vm-image-fixtures/"))
            require(relativePath.split('/').all { it.matches(Regex("[A-Za-z0-9_.-]+")) && it != "." && it != ".." })
        }
    }
}

data class VmImageAsset(val fileName: String, val sha256: String, val bytes: Long, val source: VmAssetSource) {
    init {
        require(fileName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")))
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        require(bytes in 1..2L * 1024 * 1024 * 1024)
    }
}

data class PinnedVmImage(
    val imageId: String, val revision: String,
    val kernel: VmImageAsset, val initramfs: VmImageAsset, val download: VmImageAsset,
    val diskBytes: Long, val diskSha256: String, val compression: VmImageCompression,
) {
    init {
        require(imageId.matches(Regex("[a-z0-9][a-z0-9-]{0,63}")))
        require(revision.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")))
        require(kernel.bytes <= 128L * 1024 * 1024 && initramfs.bytes <= 128L * 1024 * 1024)
        require(diskBytes in 1..32L * 1024 * 1024 * 1024)
        require(diskSha256.matches(Regex("[a-f0-9]{64}")))
    }
}

data class InstalledVmImage(
    val instanceId: String, val imageId: String, val revision: String,
    val kernelFile: File, val kernelSha256: String,
    val initramfsFile: File, val initramfsSha256: String,
    val diskFile: File, val diskBytes: Long,
)

enum class VmInstallStage { PREPARE, DOWNLOAD_KERNEL, DOWNLOAD_INITRAMFS, DOWNLOAD_DISK, EXPAND_DISK, CLONE_DISK, DELETE, VERIFY, READY }
data class VmInstallProgress(val stage: VmInstallStage, val completedBytes: Long, val totalBytes: Long) {
    val fraction: Float get() = if (totalBytes > 0) (completedBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat() else 0f
}

internal fun checkedHttpsUrl(value: String): URI = URI(value).also {
    require(it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null && it.fragment == null)
    require(it.port == -1 || it.port == 443)
}

internal fun checkedInstanceId(value: String): String = value.also {
    require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")))
}
