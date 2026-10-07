package com.linex.app.core

import android.content.Context
import com.linex.vm.images.*
import kotlinx.serialization.json.*

/** This bundled catalogue is populated only after the exact factory image passes release gates. */
object VmImageCatalogue {
    fun current(context: Context): PinnedVmImage? {
        if (context.assets.list("vm")?.contains("image.json") != true) return null
        val encoded = context.assets.open("vm/image.json").use { input ->
            val bytes = ByteArray(16385)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                require(read > 0) { "VM catalogue stream made no progress" }
                count += read
            }
            require(count <= 16384) { "VM image catalogue exceeds its size limit" }
            bytes.copyOf(count).toString(Charsets.UTF_8)
        }
        return parse(encoded)
    }

    internal fun parse(encoded: String): PinnedVmImage {
        require(encoded.toByteArray().size <= 16384)
        val manifest = Json.parseToJsonElement(encoded).jsonObject
        require(manifest["schema"]?.jsonPrimitive?.int == 1 &&
            manifest["releaseReady"]?.jsonPrimitive?.boolean == true &&
            manifest["runtimeProofPassed"]?.jsonPrimitive?.boolean == true &&
            manifest["architecture"]?.jsonPrimitive?.content == "aarch64") { "VM image has not passed release validation" }
        val base = manifest["downloadBase"]!!.jsonPrimitive.content
        require(base.startsWith("https://github.com/lupixele/linex/releases/download/") &&
            base.matches(Regex("https://github.com/lupixele/linex/releases/download/[A-Za-z0-9._-]+/"))) { "Unexpected VM release location" }
        fun asset(key: String): VmImageAsset {
            val entry = manifest[key]!!.jsonObject
            val name = entry["file"]!!.jsonPrimitive.content
            return VmImageAsset(name, entry["sha256"]!!.jsonPrimitive.content,
                entry["bytes"]!!.jsonPrimitive.long, VmAssetSource.Https(base + name))
        }
        val disk = manifest["disk"]!!.jsonObject
        require(disk["format"]?.jsonPrimitive?.content == "raw" && disk["filesystem"]?.jsonPrimitive?.content == "ext4")
        val bytes = disk["bytes"]!!.jsonPrimitive.long
        require(bytes in 128L * 1024 * 1024..32L * 1024 * 1024 * 1024 && bytes % 4096 == 0L)
        require(manifest["download"]!!.jsonObject["compression"]?.jsonPrimitive?.content == "xz")
        return PinnedVmImage(manifest["imageId"]!!.jsonPrimitive.content,
            manifest["revision"]!!.jsonPrimitive.content, asset("kernel"), asset("initramfs"), asset("download"),
            bytes, disk["sha256"]!!.jsonPrimitive.content, VmImageCompression.XZ)
    }
}
