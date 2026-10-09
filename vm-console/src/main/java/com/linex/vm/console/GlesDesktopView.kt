package com.linex.vm.console

import android.content.Context
import android.opengl.GLSurfaceView
import java.util.concurrent.atomic.AtomicBoolean

/** Host GPU composition of complete RFB snapshots; this does not accelerate guest rendering. */
class GlesDesktopView(context: Context) : GLSurfaceView(context), AutoCloseable {
    private val frames = OwnedFrameMailbox()
    private val disposed = AtomicBoolean()
    private val cleanupStarted = AtomicBoolean()
    private val painter = OwnedFrameRenderer(frames, disposed, { width, height ->
        frameWidth = width
        frameHeight = height
        presentedFrameCount++
        onPresented(presentedFrameCount)
    }, { message -> onFailure(message) })
    @Volatile var frameWidth: Int = 0
        private set
    @Volatile var frameHeight: Int = 0
        private set
    @Volatile var presentedFrameCount: Long = 0
        private set
    /** Runs on the GL thread, after drawing a newly uploaded complete frame. */
    var onPresented: (Long) -> Unit = {}
    /** Runs on the GL thread; report via the instance UI rather than crashing the GL worker. */
    var onFailure: (String) -> Unit = {}
    /** Latches only after a frame containing more than a sparse cursor was drawn. */
    val hasPresentedContent: Boolean get() = painter.hasPresentedContent

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(painter)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Takes ownership even when hidden/closed: rejected and superseded frames are recycled. */
    fun offerFrame(frame: OwnedArgbFrame) {
        if (frames.offer(frame)) requestRender()
    }

    /** Call on the UI thread alongside the decoder's pauseUpdates. */
    fun setActive(active: Boolean) {
        if (disposed.get()) return
        frames.setAccepting(active)
        if (active) { onResume(); requestRender() } else onPause()
    }

    override fun close() {
        if (!cleanupStarted.compareAndSet(false, true)) return
        disposed.set(true)
        frames.close()
        synchronized(painter) { painter.releaseCpuFrames() }
        queueEvent { painter.deleteGlObjects() }
    }

}
