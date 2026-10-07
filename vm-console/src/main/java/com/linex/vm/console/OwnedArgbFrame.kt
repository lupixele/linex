package com.linex.vm.console

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Ownership transfers to the presenter; the producer must stop mutating pixels until release. */
class OwnedArgbFrame(
    val width: Int,
    val height: Int,
    internal val pixels: IntArray,
    private val recycle: (IntArray) -> Unit
) : AutoCloseable {
    private val released = AtomicBoolean()
    init {
        require(width in 1..4096 && height in 1..4096 && width.toLong() * height <= 8_000_000)
        require(pixels.size == width * height)
    }
    internal fun writeRgba(destination: ByteBuffer) {
        check(!released.get()) { "Frame released" }
        require(destination.capacity() >= pixels.size * 4)
        destination.clear()
        for (pixel in pixels) {
            destination.put((pixel ushr 16).toByte())
            destination.put((pixel ushr 8).toByte())
            destination.put(pixel.toByte())
            destination.put((pixel ushr 24).toByte())
        }
        destination.flip()
    }
    override fun close() {
        if (released.compareAndSet(false, true)) recycle(pixels)
    }
}

/** One pending frame, plus one frame checked out by the GL thread. No callback under the lock. */
internal class OwnedFrameMailbox : AutoCloseable {
    private var latest: OwnedArgbFrame? = null
    private var accepting = true
    private var closed = false
    private var transferring = false
    fun offer(frame: OwnedArgbFrame): Boolean {
        val dropped: OwnedArgbFrame?
        val accepted: Boolean
        synchronized(this) {
            accepted = accepting && !closed && !transferring
            dropped = if (accepted) latest else frame
            if (accepted) latest = frame
        }
        dropped?.close()
        return accepted
    }
    /** Reserve the pending frame while releasing the renderer's old ownership.
     * Offers during transfer are dropped, so callbacks stay outside the lock
     * without temporarily retaining old, replacement and newly pending frames.
     */
    fun takeReplacing(releaseCurrent: () -> Unit): OwnedArgbFrame? {
        synchronized(this) {
            if (latest == null || closed || transferring) return null
            transferring = true
        }
        try {
            releaseCurrent()
            return synchronized(this) { latest.also { latest = null } }
        } finally {
            synchronized(this) { transferring = false }
        }
    }
    fun setAccepting(value: Boolean) {
        val dropped = synchronized(this) {
            accepting = value
            if (!value) latest.also { latest = null } else null
        }
        dropped?.close()
    }
    override fun close() {
        val dropped = synchronized(this) {
            closed = true
            latest.also { latest = null }
        }
        dropped?.close()
    }
}
