package com.linex.app.core

object DesktopFrameRate {
    val options = listOf(15, 30, 60, 90, 120, 144)
    fun normalized(fps: Int): Int = if (fps in options) fps else 15
    fun intervalNanos(fps: Int): Long = 1_000_000_000L / normalized(fps)
    fun preferredRefresh(fps: Int, supported: List<Float>): Float {
        val rates = supported.filter { it.isFinite() && it > 0 }.sorted()
        return rates.firstOrNull { it >= normalized(fps) - 0.5f } ?: rates.lastOrNull() ?: 0f
    }
}
