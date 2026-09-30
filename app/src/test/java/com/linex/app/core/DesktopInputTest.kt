package com.linex.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopInputTest {
    @Test fun keypadDigitsRespectNumLockAndNavigation() {
        assertEquals(0xffb0, DesktopInput.keypadDigit(0, true))
        assertEquals(0xffb9, DesktopInput.keypadDigit(9, true))
        assertEquals(0xff9e, DesktopInput.keypadDigit(0, false))
        assertEquals(0xff95, DesktopInput.keypadDigit(7, false))
        assertEquals(0xff99, DesktopInput.keypadDigit(2, false))
        assertEquals(0xffae, DesktopInput.keypadDecimal(true))
        assertEquals(0xff9f, DesktopInput.keypadDecimal(false))
        assertEquals(0, DesktopInput.keypadDigit(10, true))
    }
    @Test fun deadAccentsAreForwardedForGuestComposition() {
        assertEquals(0xfe51, DesktopInput.characterSymbol(Int.MIN_VALUE or 0xb4))
        assertEquals(0xfe50, DesktopInput.characterSymbol(Int.MIN_VALUE or 0x60))
        assertEquals(0xfe57, DesktopInput.characterSymbol(Int.MIN_VALUE or 0xa8))
        assertEquals(0x61, DesktopInput.characterSymbol(0x61))
        assertEquals(0, DesktopInput.characterSymbol(0))
    }
    @Test fun unicodePreservesLatinAndEncodesSupplementaryCharacters() {
        assertEquals(0x41, DesktopInput.unicodeSymbol(0x41))
        assertEquals(0x0101f600, DesktopInput.unicodeSymbol(0x1f600))
        assertEquals(0, DesktopInput.unicodeSymbol(0xd800))
        assertEquals(0, DesktopInput.unicodeSymbol(Int.MIN_VALUE))
    }
    @Test fun buttonsAndWheelUseRfbOrder() {
        assertEquals(1, DesktopInput.buttonMask(1))
        assertEquals(2, DesktopInput.buttonMask(2))
        assertEquals(4, DesktopInput.buttonMask(3))
        assertEquals(0, DesktopInput.buttonMask(4))
        assertEquals(8, DesktopInput.wheelMask(false, true))
        assertEquals(16, DesktopInput.wheelMask(false, false))
        assertEquals(64, DesktopInput.wheelMask(true, true))
        assertEquals(32, DesktopInput.wheelMask(true, false))
    }
    @Test fun smallScrollSuppressesRightTapBeforeWheelStep() {
        val gesture = DesktopGesture(8f)
        gesture.motion(0f, 10f) // Below the 24dp wheel step, above touch slop.
        assertEquals(0, gesture.tapButton(true, false, false, 100, 500))
        gesture.reset()
        assertEquals(3, gesture.tapButton(true, false, false, 100, 500))
    }
    @Test fun fractionalMotionAccumulatesAndEdgeDoesNotStoreOvershoot() {
        val gesture = DesktopGesture(8f)
        var position = 10 to 10
        repeat(4) { position = gesture.relative(position.first, position.second, 0.25f, -0.25f, 20, 20) }
        assertEquals(11 to 9, position)
        assertEquals(20 to 0, gesture.relative(20, 0, 100f, -100f, 20, 20))
        assertEquals(19 to 1, gesture.relative(20, 0, -1f, 1f, 20, 20))
    }
    @Test fun cancelledHeldAndExpiredGesturesNeverClick() {
        val gesture = DesktopGesture(8f)
        assertEquals(0, gesture.tapButton(false, true, false, 100, 500))
        assertEquals(0, gesture.tapButton(false, false, true, 100, 500))
        assertEquals(0, gesture.tapButton(false, false, false, 500, 500))
        assertEquals(1, gesture.tapButton(false, false, false, 100, 500))
    }
}
