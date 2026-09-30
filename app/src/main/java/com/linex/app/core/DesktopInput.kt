package com.linex.app.core

/** Protocol mappings kept independent of Android so key and scroll behavior can be tested. */
object DesktopInput {
    fun keypadDigit(digit: Int, numeric: Boolean): Int {
        if (digit !in 0..9) return 0
        return if (numeric) 0xffb0 + digit else intArrayOf(0xff9e, 0xff9c, 0xff99, 0xff9b, 0xff96, 0xff9d, 0xff98, 0xff95, 0xff97, 0xff9a)[digit]
    }
    fun keypadDecimal(numeric: Boolean): Int = if (numeric) 0xffae else 0xff9f
    /** Android marks a dead accent with bit31 and stores its spacing accent underneath. */
    fun characterSymbol(value: Int): Int {
        if (value and Int.MIN_VALUE == 0) return unicodeSymbol(value)
        return when (value and Int.MAX_VALUE) {
            0x60, 0x300 -> 0xfe50 // grave
            0xb4, 0x301 -> 0xfe51 // acute
            0x5e, 0x302 -> 0xfe52 // circumflex
            0x7e, 0x303 -> 0xfe53 // tilde
            0xaf, 0x304 -> 0xfe54 // macron
            0x2d8, 0x306 -> 0xfe55 // breve
            0x2d9, 0x307 -> 0xfe56 // above dot
            0xa8, 0x308 -> 0xfe57 // diaeresis
            0x2da, 0x30a -> 0xfe58 // above ring
            0x2dd, 0x30b -> 0xfe59 // double acute
            0x2c7, 0x30c -> 0xfe5a // caron
            0xb8, 0x327 -> 0xfe5b // cedilla
            0x2db, 0x328 -> 0xfe5c // ogonek
            else -> unicodeSymbol(value and Int.MAX_VALUE)
        }
    }
    fun unicodeSymbol(value: Int): Int = when {
        value <= 0 || value > 0x10ffff || value in 0xd800..0xdfff -> 0
        value <= 255 -> value
        else -> 0x01000000 or value
    }

    fun buttonMask(button: Int): Int = if (button in 1..3) 1 shl (button - 1) else 0

    // Positive Android wheel axes mean up/right; RFB buttons 4..7 represent wheels.
    fun wheelMask(horizontal: Boolean, positive: Boolean): Int =
        1 shl (if (horizontal) { if (positive) 6 else 5 } else { if (positive) 3 else 4 })
}

/** Small gesture accumulator: thresholds suppress taps independently of wheel step size. */
class DesktopGesture(private val slop: Float) {
    private var distance = 0f
    var moved = false
        private set
    private var remainderX = 0f
    private var remainderY = 0f
    fun reset() { distance = 0f; moved = false; remainderX = 0f; remainderY = 0f }
    fun motion(dx: Float, dy: Float) {
        distance += kotlin.math.abs(dx) + kotlin.math.abs(dy)
        if (distance > slop) moved = true
    }
    fun tapButton(twoFingers: Boolean, cancelled: Boolean, held: Boolean, duration: Long, timeout: Long): Int =
        if (moved || cancelled || held || duration >= timeout) 0 else if (twoFingers) 3 else 1

    fun relative(x: Int, y: Int, dx: Float, dy: Float, maxX: Int, maxY: Int): Pair<Int, Int> {
        val nextX = (x + remainderX + dx).coerceIn(0f, maxX.toFloat())
        val nextY = (y + remainderY + dy).coerceIn(0f, maxY.toFloat())
        val roundedX = kotlin.math.round(nextX).toInt()
        val roundedY = kotlin.math.round(nextY).toInt()
        remainderX = nextX - roundedX; remainderY = nextY - roundedY
        return roundedX to roundedY
    }
}
