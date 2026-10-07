package com.linex.vm

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/** Private mutable raw disk plus verified immutable assets. No caller supplies QEMU options. */
data class VmDesktopBootRequest(
    val sessionToken: String,
    val instanceId: String,
    val kernelPath: String,
    val kernelSha256: String,
    val initramfsPath: String,
    val initramfsSha256: String,
    val diskPath: String,
    val diskBytes: Long,
    val serialPath: String,
    val memoryMiB: Int,
    val vcpuCount: Int,
) {
    /** Rechecks real boot bytes and disk metadata; never hashes a mutable multi-GiB disk. */
    fun validated(filesRoot: File, availableMemoryBytes: Long, lowMemory: Boolean = false): VmDesktopBootRequest {
        require(sessionToken.matches(Regex("[a-f0-9]{32}"))) { "Invalid desktop session token" }
        require(instanceId.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_-]{0,63}"))) { "Invalid desktop instance ID" }
        require(memoryMiB in 256..4096 && !lowMemory && memoryMiB <= availableGuestMemoryMiB(availableMemoryBytes)) {
            "Requested guest RAM exceeds the available device budget"
        }
        require(vcpuCount in 1..2) { "Desktop CPU count must be 1 or 2" }
        verifiedAsset(kernelPath, kernelSha256, filesRoot, "vm-images", 128L * MIB)
        verifiedAsset(initramfsPath, initramfsSha256, filesRoot, "vm-images", 256L * MIB)
        require(diskBytes in 128L * MIB..32L * 1024 * MIB && diskBytes % 4096 == 0L) { "Invalid raw disk length" }
        val disk = privatePath(diskPath, filesRoot, "vm-instances/$instanceId")
        require(Files.isRegularFile(disk.toPath(), LinkOption.NOFOLLOW_LINKS) && disk.canRead() && disk.canWrite()) {
            "Instance disk must be a readable writable regular file"
        }
        require(disk.length() == diskBytes) { "Instance disk length differs from prepared metadata" }
        privatePath(serialPath, filesRoot, "vm-instances/$instanceId")
        require(serialPath.toByteArray(Charsets.UTF_8).size <= 100) { "Serial socket path is too long" }
        return this
    }

    private fun verifiedAsset(path: String, digest: String, root: File, directory: String, maximum: Long) {
        require(digest.matches(Regex("[a-f0-9]{64}"))) { "Invalid boot asset SHA-256" }
        val file = privatePath(path, root, directory)
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.length() in 1..maximum) {
            "Boot asset must be a bounded regular file"
        }
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(65536)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= maximum) { "Boot asset exceeds its size limit" }
                hash.update(buffer, 0, count)
            }
        }
        require(hash.digest().joinToString("") { "%02x".format(it) } == digest) { "Boot asset SHA-256 mismatch" }
    }

    /** Service-only serialization. Disk filenames are JSON data, never comma-delimited options. */
    internal fun qemuArguments(console: VmConsoleEndpoint): Array<String> {
        val block = buildJsonObject {
            put("driver", "raw"); put("node-name", "linexdisk")
            put("file", buildJsonObject { put("driver", "file"); put("filename", File(diskPath).canonicalPath) })
        }.toString()
        return arrayOf("linex-qemu", "-no-user-config", "-nodefaults", "-machine", "virt", "-cpu", "cortex-a53",
            "-accel", "tcg,thread=multi", "-smp", vcpuCount.toString(), "-m", memoryMiB.toString(),
            "-kernel", File(kernelPath).canonicalPath, "-initrd", File(initramfsPath).canonicalPath,
            "-append", "console=ttyAMA0 rdinit=/init panic=-1", "-display", "none", "-monitor", "none",
            "-blockdev", block, "-device", "virtio-blk-device,drive=linexdisk",
            // Older engines reject this unknown typed option before boot; never silently omit it.
            "-netdev", "user,id=linexnet,ipv6=off,linex-host-loopback=off,hostfwd=unix:${console.socket.absolutePath}-10.0.2.15:5901",
            "-device", "virtio-net-device,netdev=linexnet", "-no-reboot",
            "-chardev", "socket,id=linexserial,path=${File(serialPath).canonicalPath.replace(",", ",,")},server=off",
            "-serial", "chardev:linexserial", "-qmp", "unix:${console.qmpSocket.absolutePath},server=on,wait=off")
    }

    companion object {
        private const val MIB = 1024L * 1024
        /** Keep one quarter of current free RAM plus 256MiB for Android and engine overhead. */
        fun availableGuestMemoryMiB(availableBytes: Long): Int {
            require(availableBytes >= 0) { "Invalid available memory measurement" }
            return ((availableBytes - availableBytes / 4 - 256L * MIB).coerceAtLeast(0) / MIB)
                .coerceAtMost(4096).toInt()
        }

        internal fun privatePath(path: String, filesRoot: File, directory: String): File {
            require(path.isNotBlank() && path.toByteArray(Charsets.UTF_8).size <= 4096 && '\u0000' !in path) { "Invalid private desktop path" }
            val candidate = File(path).toPath()
            require(candidate.isAbsolute && candidate == candidate.normalize()) { "Desktop path must be absolute without traversal" }
            val anchor = filesRoot.toPath().toAbsolutePath().normalize()
            val scope = anchor.resolve(directory).normalize()
            require(candidate.startsWith(scope) && candidate != scope) { "Desktop path outside its private storage scope" }
            var current = anchor
            for (component in anchor.relativize(candidate)) {
                current = current.resolve(component)
                require(!Files.isSymbolicLink(current)) { "Desktop storage cannot contain symlinks" }
            }
            val canonical = candidate.toFile().canonicalFile
            require(canonical.toPath().startsWith(File(filesRoot.canonicalFile, directory).toPath())) { "Desktop path escapes private storage" }
            return canonical
        }
    }
}

/** Service-generated generation; the caller cannot choose a forward destination or listener. */
data class VmConsoleEndpoint internal constructor(val generation: String, val socket: File, val qmpSocket: File) {
    companion object {
        internal fun create(filesRoot: File, generation: String): VmConsoleEndpoint {
            require(generation.matches(Regex("[a-f0-9]{32}"))) { "Invalid console generation" }
            val directory = File(filesRoot.canonicalFile, "vmc/$generation")
            val path = File(directory, "c").absolutePath
            require(path.none { it == '-' || it == ',' || it == '\u0000' || it == '\n' || it == '\r' }) {
                "Console path contains a QEMU rule separator"
            }
            require(path.toByteArray(Charsets.UTF_8).size <= 107) { "Console Unix socket path is too long" }
            return VmConsoleEndpoint(generation, File(path), File(directory, "q"))
        }
    }
}

data class VmDesktopLaunch(val pid: Int, val console: VmConsoleEndpoint)
