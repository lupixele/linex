package com.linex.vm.console

/** A bounded startup hint, not proof that XFCE or an application is healthy. */
internal object DesktopFrameContent {
    fun visible(pixels: IntArray, width: Int, height: Int): Boolean {
        require(width > 0 && height > 0 && pixels.size.toLong() == width.toLong() * height)
        val columns = minOf(width, 64)
        val rows = minOf(height, 36)
        val required = minOf(columns * rows, maxOf(2, (columns * rows + 99) / 100))
        var coloured = 0
        for (row in 0 until rows) {
            val y = ((2L * row + 1) * height / (2 * rows)).toInt()
            for (column in 0 until columns) {
                val x = ((2L * column + 1) * width / (2 * columns)).toInt()
                if (pixels[y * width + x] and 0x00ffffff != 0 && ++coloured >= required) return true
            }
        }
        return false
    }
}
