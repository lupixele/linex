package com.linex.vm

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/** Typed fixture-only launch contract. Production disk/image configuration is separate. */
data class VmBootRequest(
    val sessionToken: String,
    val instanceId: String,
    val kernelPath: String,
    val kernelSha256: String,
    val initramfsPath: String,
    val initramfsSha256: String,
    val serialPath: String,
    val memoryMiB: Int,
    val vcpuCount: Int,
    val network: VmNetworkMode = VmNetworkMode.DISABLED,
) {
    /** Hashes actual private regular files; caller declarations alone are not verification. */
    fun validated(privateRoots: List<File>): VmBootRequest {
        require(sessionToken.matches(Regex("[A-Za-z0-9_-]{16,64}"))) { "Invalid VM session token" }
        require(instanceId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid VM instance ID" }
        require(memoryMiB in 256..1024) { "Fixture memory must be 256–1024 MiB" }
        require(vcpuCount in 1..2) { "Fixture CPU count must be 1 or 2" }
        verifiedAsset(kernelPath, kernelSha256, privateRoots, 128L * 1024 * 1024)
        verifiedAsset(initramfsPath, initramfsSha256, privateRoots, 256L * 1024 * 1024)
        privatePath(serialPath, privateRoots)
        return this
    }

    private fun verifiedAsset(path: String, digest: String, roots: List<File>, maximum: Long) {
        require(digest.matches(Regex("[a-f0-9]{64}"))) { "Invalid asset SHA-256" }
        val file = privatePath(path, roots)
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Boot asset must be a regular file" }
        require(!Files.isSymbolicLink(File(path).toPath())) { "Boot asset cannot be a symlink" }
        require(file.length() in 1..maximum) { "Boot asset is empty or exceeds fixture size limit" }
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(65536)
            var total = 0L
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                total += count
                require(total <= maximum) { "Boot asset exceeds fixture size limit" }
                hash.update(bytes, 0, count)
            }
        }
        val actual = hash.digest().joinToString("") { "%02x".format(it) }
        require(actual == digest) { "Boot asset SHA-256 mismatch" }
    }

    companion object {
        internal fun privatePath(path: String, roots: List<File>): File {
            require(path.isNotBlank() && '\u0000' !in path) { "Invalid private path" }
            val file = File(path)
            require(file.isAbsolute) { "VM paths must be absolute" }
            val canonical = file.canonicalFile
            require(roots.any { root ->
                canonical.toPath().startsWith(root.canonicalFile.toPath()) && canonical != root.canonicalFile
            }) { "VM path outside private engine storage" }
            return canonical
        }
    }
}
