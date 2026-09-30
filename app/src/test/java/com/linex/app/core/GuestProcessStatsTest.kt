package com.linex.app.core

import org.junit.Assert.*
import org.junit.Test

class GuestProcessStatsTest {
    @Test fun parsesGroupAfterParenthesizedCommand() {
        assertEquals(123, GuestProcessStats.processGroup("456 (browser (child)) S 42 123 456 0"))
    }
    @Test fun inaccessibleOrMalformedStatsRemainUnknown() {
        assertNull(GuestProcessStats.processGroup(""))
        assertNull(GuestProcessStats.processGroup("456 (x) S 42"))
    }
}
