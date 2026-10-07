package com.linex.vm.console

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLSurface
import android.opengl.GLES20
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class OwnedFrameRendererTest {
    @Test fun uploadsRealPixelsReusesStorageAndRestoresOwnedFrameAfterContextLoss() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertNotEquals(EGL14.EGL_NO_DISPLAY, display)
        assertTrue(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<EGLConfig>(1)
        assertTrue(EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE), 0, configs, 0, 1, IntArray(1), 0))
        val config = requireNotNull(configs[0])
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        fun openContext() {
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            surface = EGL14.eglCreatePbufferSurface(display, config,
                intArrayOf(EGL14.EGL_WIDTH, 4, EGL14.EGL_HEIGHT, 4, EGL14.EGL_NONE), 0)
            assertNotEquals(EGL14.EGL_NO_CONTEXT, context)
            assertNotEquals(EGL14.EGL_NO_SURFACE, surface)
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context))
        }
        fun destroyContext() {
            assertTrue(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT))
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        }
        fun assertPixel(x: Int, y: Int, color: Int) {
            val rgba = ByteBuffer.allocateDirect(4)
            GLES20.glReadPixels(x, y, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgba)
            val actual = (rgba.get(3).toInt() and 255 shl 24) or
                (rgba.get(0).toInt() and 255 shl 16) or (rgba.get(1).toInt() and 255 shl 8) or
                (rgba.get(2).toInt() and 255)
            assertEquals(color, actual)
        }
        val mailbox = OwnedFrameMailbox()
        val disposed = AtomicBoolean()
        var presented = 0
        var recycled = 0
        var failure: String? = null
        val renderer = OwnedFrameRenderer(mailbox, disposed, { _, _ -> presented++ }, { failure = it })
        try {
            openContext()
            renderer.onSurfaceCreated(null, null)
            renderer.onSurfaceChanged(null, 4, 4)
            mailbox.offer(OwnedArgbFrame(2, 2,
                intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0xffffffff.toInt())) { recycled++ })
            renderer.onDrawFrame(null)
            assertNull(failure)
            assertPixel(0, 3, 0xffff0000.toInt()); assertPixel(3, 3, 0xff00ff00.toInt())
            assertPixel(0, 0, 0xff0000ff.toInt()); assertPixel(3, 0, 0xffffffff.toInt())
            assertEquals(1, presented); assertEquals(0, recycled)
            mailbox.offer(OwnedArgbFrame(2, 2, IntArray(4) { 0xffff00ff.toInt() }) { recycled++ })
            renderer.onDrawFrame(null)
            assertPixel(1, 1, 0xffff00ff.toInt())
            assertEquals(2, presented); assertEquals(1, recycled)
            destroyContext(); openContext()
            renderer.onSurfaceCreated(null, null)
            renderer.onSurfaceChanged(null, 4, 4)
            renderer.onDrawFrame(null)
            assertPixel(2, 2, 0xffff00ff.toInt())
            assertEquals(2, presented); assertEquals(1, recycled)
            mailbox.offer(OwnedArgbFrame(1, 1, intArrayOf(0xff00ffff.toInt())) { recycled++ })
            renderer.onDrawFrame(null)
            assertPixel(2, 2, 0xff00ffff.toInt())
            assertEquals(3, presented); assertEquals(2, recycled)
            assertNull(failure)
        } finally {
            disposed.set(true)
            mailbox.close()
            renderer.releaseCpuFrames()
            renderer.deleteGlObjects()
            destroyContext()
            EGL14.eglTerminate(display)
        }
        assertEquals(3, recycled)
    }
}
