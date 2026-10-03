package com.linex.app.core

/** Host presentation capability only. This does not establish Linux guest GL or video decoding. */
data class GpuCapabilities(
    val vendor: String = "",
    val renderer: String = "",
    val glVersion: String = "",
    val maxTextureSize: Int = 0,
    val drawVerified: Boolean = false,
    val failureReason: String? = null
) {
    val softwareRenderer: Boolean
        get() = listOf("llvmpipe", "softpipe", "swiftshader", "software rasterizer", "swrast")
            .any { renderer.contains(it, ignoreCase = true) }
}

enum class GpuPresentationBackend { HOST_GLES, SOFTWARE }

data class GpuPresentationSelection(val backend: GpuPresentationBackend, val reason: String)

object GpuPresentationPolicy {
    fun select(capabilities: GpuCapabilities, width: Int, height: Int): GpuPresentationSelection {
        val fallback = when {
            !capabilities.drawVerified -> capabilities.failureReason ?: "Host GLES draw probe failed"
            capabilities.renderer.isBlank() -> "Host GPU renderer unavailable"
            capabilities.softwareRenderer -> "Host GLES uses software rendering"
            width <= 0 || height <= 0 -> "Invalid desktop dimensions"
            width > capabilities.maxTextureSize || height > capabilities.maxTextureSize -> "Desktop exceeds host texture limit"
            else -> null
        }
        return if (fallback != null) GpuPresentationSelection(GpuPresentationBackend.SOFTWARE, fallback)
        else GpuPresentationSelection(GpuPresentationBackend.HOST_GLES, "Verified host GLES presentation")
    }
}
