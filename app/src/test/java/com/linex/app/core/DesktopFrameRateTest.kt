package com.linex.app.core

import org.junit.Assert.*
import org.junit.Test

class DesktopFrameRateTest {
    @Test fun presetsChangePacingAndInvalidValuesStayBounded() {
        assertEquals(66_666_666L, DesktopFrameRate.intervalNanos(15))
        assertEquals(8_333_333L, DesktopFrameRate.intervalNanos(120))
        assertEquals(6_944_444L, DesktopFrameRate.intervalNanos(144))
        listOf(0, -1, Int.MAX_VALUE).forEach { assertEquals(15, DesktopFrameRate.normalized(it)) }
    }
    @Test fun refreshRequestUsesSupportedRateWithoutInventingHardwareCapability() {
        assertEquals(120f, DesktopFrameRate.preferredRefresh(90, listOf(60f, 120f)), 0f)
        assertEquals(60f, DesktopFrameRate.preferredRefresh(144, listOf(60f)), 0f)
        assertEquals(60f, DesktopFrameRate.preferredRefresh(15, listOf(60f, 120f)), 0f)
        assertEquals(0f, DesktopFrameRate.preferredRefresh(120, emptyList()), 0f)
    }
}
