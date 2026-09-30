package com.linex.app.core

import org.junit.Assert.*
import org.junit.Test

class PresentedFrameRateTest {
    @Test fun measuresPresentedFramesAcrossActualElapsedTimeAndIdle() {
        val rate = PresentedFrameRate()
        assertNull(rate.sample(10, 1_000_000_000))
        assertEquals(30.0, rate.sample(70, 3_000_000_000)!!, 0.001)
        assertEquals(0.0, rate.sample(70, 4_000_000_000)!!, 0.001)
    }

    @Test fun reconnectOrNonAdvancingClockRequiresANewBaseline() {
        val rate = PresentedFrameRate()
        rate.sample(100, 1_000_000_000)
        assertNull(rate.sample(0, 2_000_000_000))
        assertEquals(15.0, rate.sample(15, 3_000_000_000)!!, 0.001)
        assertNull(rate.sample(20, 3_000_000_000))
    }
}
