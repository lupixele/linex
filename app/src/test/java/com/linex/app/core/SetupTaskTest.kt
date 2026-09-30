package com.linex.app.core

import org.junit.Assert.*
import org.junit.Test

class SetupTaskTest {
    private fun task(fraction: Float) = SetupTask("id", "Ubuntu", fraction, "", "Extracting", 1L, 2L, SetupStatus.RUNNING)
    @Test fun unknownProgressRemainsIndeterminate() {
        assertNull(task(-1f).progressPercent)
        assertNull(task(Float.NaN).progressPercent)
    }
    @Test fun progressIsBoundedWithoutImplyingCompletion() {
        assertEquals(42, task(.42f).progressPercent)
        assertEquals(100, task(2f).progressPercent)
        assertEquals(SetupStatus.RUNNING, task(1f).status)
    }
}
