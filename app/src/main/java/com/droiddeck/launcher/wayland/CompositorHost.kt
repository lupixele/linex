package com.droiddeck.launcher.wayland

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Choreographer
import android.view.Surface
import java.io.File

object CompositorHost {
    @Volatile
    private var started = false

    @Volatile
    private var vsyncRunning = false

    private val vsyncThread: HandlerThread by lazy {
        HandlerThread("linex-vsync", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    }
    private val vsyncHandler: Handler by lazy { Handler(vsyncThread.looper) }

    @Volatile
    private var vsyncGeneration = 0

    private var attached: Surface? = null

    private fun vsyncCallback(generation: Int) = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (generation != vsyncGeneration) return
            WaylandCompositor.nativeVsync(frameTimeNanos)
            if (vsyncRunning) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    @Synchronized
    fun startOrAttach(
        context: Context,
        surface: Surface,
        xdgRuntimeDir: String,
        outputWidth: Int,
        outputHeight: Int,
        refreshHz: Float = 60f,
        fpsLimit: Int = 60
    ): Boolean {
        File(xdgRuntimeDir).mkdirs()
        WaylandCompositor.nativeSetRenderOnCommit(true)
        pace(refreshHz, fpsLimit)
        WaylandCompositor.nativeSetOutputSize(outputWidth, outputHeight)

        if (started) {
            attached = surface
            WaylandCompositor.nativeSetSurface(surface)
            resumeVsync()
            return false
        }

        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        WaylandCompositor.nativeStartWithSurface(
            surface,
            xdgRuntimeDir,
            null,
            null,
            nativeLibDir,
            true
        )
        attached = surface
        started = true
        resumeVsync()
        return true
    }

    private fun pace(refreshHz: Float, fpsLimit: Int) {
        WaylandCompositor.nativeSetFpsLimit(fpsLimit)
        WaylandCompositor.nativeSetLayerFrameRate(if (fpsLimit > 0) fpsLimit.toFloat() else refreshHz)
        WaylandCompositor.nativeSetOutputRefreshRate(refreshHz)
    }

    @Synchronized
    fun updatePacing(refreshHz: Float, fpsLimit: Int) {
        if (started) pace(refreshHz, fpsLimit)
    }

    @Synchronized
    fun resize(surface: Surface) {
        if (!started) return
        WaylandCompositor.nativeSetSurface(null)
        attached = surface
        WaylandCompositor.nativeSetSurface(surface)
    }

    @Synchronized
    fun detach(surface: Surface?) {
        if (!started) return
        if (surface != null && attached != null && attached !== surface) return
        attached = null
        pauseVsync()
        WaylandCompositor.nativeSetSurface(null)
    }

    private fun resumeVsync() {
        if (vsyncRunning) return
        val callback = vsyncCallback(++vsyncGeneration)
        vsyncRunning = true
        vsyncHandler.post { Choreographer.getInstance().postFrameCallback(callback) }
    }

    private fun pauseVsync() {
        vsyncRunning = false
    }

    val isStarted: Boolean get() = started
}
