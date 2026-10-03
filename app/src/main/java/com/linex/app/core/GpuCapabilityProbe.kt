package com.linex.app.core

import android.opengl.EGL14
import android.opengl.GLES20
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/** An isolated offscreen probe; call before selecting a host presentation backend. */
object GpuCapabilityProbe {
    suspend fun probe(): GpuCapabilities = withContext(Dispatchers.IO) { probeOffscreen() }

    private fun probeOffscreen(): GpuCapabilities {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return GpuCapabilities(failureReason = "No EGL display")
        var initialized = false
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        // Restore a caller context if this worker thread already has one.
        val previousDisplay = EGL14.eglGetCurrentDisplay()
        val previousContext = EGL14.eglGetCurrentContext()
        val previousDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val previousRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        var capabilities = GpuCapabilities()
        return try {
            check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0)) { "EGL initialization failed" }
            initialized = true
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            val attributes = intArrayOf(
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE
            )
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "No GLES2 pbuffer configuration" }
            val config = checkNotNull(configs[0])
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "GLES2 context creation failed" }
            surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            check(surface != EGL14.EGL_NO_SURFACE) { "EGL pbuffer creation failed" }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "EGL context activation failed" }
            val size = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, size, 0)
            capabilities = GpuCapabilities(
                vendor = GLES20.glGetString(GLES20.GL_VENDOR).orEmpty(),
                renderer = GLES20.glGetString(GLES20.GL_RENDERER).orEmpty(),
                glVersion = GLES20.glGetString(GLES20.GL_VERSION).orEmpty(),
                maxTextureSize = size[0]
            )
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "GLES capability query failed" }
            GLES20.glViewport(0, 0, 1, 1)
            GLES20.glClearColor(0.25f, 0.5f, 0.75f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val pixel = ByteBuffer.allocateDirect(4)
            GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixel)
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "GLES draw/readback failed" }
            check(kotlin.math.abs((pixel.get(0).toInt() and 255) - 64) <= 2 &&
                kotlin.math.abs((pixel.get(1).toInt() and 255) - 128) <= 2 &&
                kotlin.math.abs((pixel.get(2).toInt() and 255) - 191) <= 2) { "GLES draw/readback mismatch" }
            capabilities.copy(drawVerified = true)
        } catch (failure: RuntimeException) {
            capabilities.copy(failureReason = failure.message ?: failure.javaClass.simpleName)
        } finally {
            if (initialized) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                if (previousDisplay != EGL14.EGL_NO_DISPLAY && previousContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglMakeCurrent(previousDisplay, previousDraw, previousRead, previousContext)
                }
                // The default display is process-shared with Android UI/native rendering.
                // Destroy only our resources; terminating it can invalidate other contexts.
            }
        }
    }
}
