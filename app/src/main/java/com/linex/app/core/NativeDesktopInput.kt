package com.linex.app.core

data class NativePointerButton(val button: Int, val down: Boolean)

/** Termux:X11 uses button 4 for scroll deltas, unlike RFB's wheel button pulses. */
object NativeDesktopInput {
    fun buttonChanges(previous: Int, next: Int): List<NativePointerButton> =
        (0..2).filter { (previous xor next) and (1 shl it) != 0 }
            .map { NativePointerButton(it + 1, next and (1 shl it) != 0) }

    /** X11 positive scroll moves down/right; one detent is 120 protocol units. */
    fun wheelDelta(horizontal: Boolean, positive: Boolean): Pair<Float, Float> =
        if (horizontal) (if (positive) 120f else -120f) to 0f
        else 0f to (if (positive) -120f else 120f)

    // Android constants kept numeric to make host-reserved key policy JVM-testable.
    fun shouldForwardKey(androidKeyCode: Int): Boolean =
        androidKeyCode > 0 && androidKeyCode !in setOf(3, 4, 24, 25, 26, 164, 187)
}
