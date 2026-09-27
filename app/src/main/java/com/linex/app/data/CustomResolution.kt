package com.linex.app.data

/** Matches the framebuffer allocation limits enforced by the display client. */
object CustomResolution {
    fun error(width: String, height: String): String? {
        val w = width.toIntOrNull()
        val h = height.toIntOrNull()
        if (w == null || h == null || w !in 1..4096 || h !in 1..4096)
            return "Enter whole numbers from 1 to 4096 for width and height."
        if (w.toLong() * h > 8_000_000)
            return "Use at most 8 million pixels total. Try 2560 × 1440."
        return null
    }
}
