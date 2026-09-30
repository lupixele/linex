package com.linex.app.core

/** Measures new desktop frames drawn, rather than the configured limit or panel refresh. */
class PresentedFrameRate {
    private var previousCount: Long? = null
    private var previousNanos: Long? = null

    fun sample(frameCount: Long, nowNanos: Long): Double? {
        val count = previousCount
        val time = previousNanos
        previousCount = frameCount
        previousNanos = nowNanos
        if (count == null || time == null || frameCount < count || nowNanos <= time) return null
        return (frameCount - count).toDouble() * 1_000_000_000.0 / (nowNanos - time)
    }
}
