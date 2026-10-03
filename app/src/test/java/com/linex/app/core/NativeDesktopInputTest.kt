package com.linex.app.core

import org.junit.Assert.*
import org.junit.Test

class NativeDesktopInputTest {
    @Test fun wheelBitsNeverBecomeNativeButtons() {
        assertEquals(emptyList<NativePointerButton>(), NativeDesktopInput.buttonChanges(0, 8 or 16 or 32 or 64))
        assertEquals(listOf(NativePointerButton(1, true), NativePointerButton(3, true)), NativeDesktopInput.buttonChanges(0, 1 or 4 or 8))
    }

    @Test fun releasesHeldButtonsAndOnlySendsChanges() {
        assertEquals(listOf(NativePointerButton(2, false)), NativeDesktopInput.buttonChanges(7, 5))
        assertEquals(emptyList<NativePointerButton>(), NativeDesktopInput.buttonChanges(5, 5))
    }

    @Test fun wheelUsesSeparateNativeDeltaAxes() {
        assertEquals(0f to -120f, NativeDesktopInput.wheelDelta(false, true))
        assertEquals(0f to 120f, NativeDesktopInput.wheelDelta(false, false))
        assertEquals(120f to 0f, NativeDesktopInput.wheelDelta(true, true))
        assertEquals(-120f to 0f, NativeDesktopInput.wheelDelta(true, false))
    }

    @Test fun androidNavigationAndMediaStayOnHost() {
        for (code in listOf(3, 4, 24, 25, 26, 164, 187)) assertFalse(NativeDesktopInput.shouldForwardKey(code))
        for (code in listOf(29, 57, 59, 66, 111, 113, 114, 117, 118, 131, 143)) assertTrue(NativeDesktopInput.shouldForwardKey(code))
    }
}
