package com.linex.app.core

import org.junit.Assert.*
import org.junit.Test

class GpuCapabilitiesTest {
    private fun gpu(renderer: String = "Adreno (TM) 618", verified: Boolean = true, limit: Int = 4096) =
        GpuCapabilities("Qualcomm", renderer, "OpenGL ES 3.2", limit, verified)

    @Test fun verifiedHardwareWorksWithoutVendorAllowlist() {
        for (renderer in listOf("Adreno (TM) 618", "Mali-G78", "PowerVR Rogue", "Unknown GPU")) {
            assertEquals(GpuPresentationBackend.HOST_GLES, GpuPresentationPolicy.select(gpu(renderer), 1920, 1080).backend)
        }
    }

    @Test fun softwareDriverDoesNotClaimHardwareAcceleration() {
        for (renderer in listOf("llvmpipe (LLVM 17)", "SwiftShader Device", "softpipe", "Software Rasterizer")) {
            assertEquals(GpuPresentationBackend.SOFTWARE, GpuPresentationPolicy.select(gpu(renderer), 1280, 720).backend)
        }
    }

    @Test fun drawFailureAndMissingDriverFallBack() {
        assertEquals(GpuPresentationBackend.SOFTWARE, GpuPresentationPolicy.select(gpu(verified = false), 1280, 720).backend)
        assertEquals(GpuPresentationBackend.SOFTWARE, GpuPresentationPolicy.select(gpu(renderer = ""), 1280, 720).backend)
    }

    @Test fun textureLimitAndInvalidDimensionsFallBack() {
        assertEquals(GpuPresentationBackend.HOST_GLES, GpuPresentationPolicy.select(gpu(), 4096, 4096).backend)
        assertEquals(GpuPresentationBackend.SOFTWARE, GpuPresentationPolicy.select(gpu(), 4097, 720).backend)
        assertEquals(GpuPresentationBackend.SOFTWARE, GpuPresentationPolicy.select(gpu(), 0, 720).backend)
    }
}
