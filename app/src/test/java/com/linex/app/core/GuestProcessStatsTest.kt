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
    @Test fun extractsOnlyKernelNameWithoutCommandArguments() {
        assertEquals("browser__child_", GuestProcessStats.commandName("456 (browser (child)) S 42 123 456 0"))
        assertNull(GuestProcessStats.commandName("malformed"))
    }
    @Test fun nameSummaryIsBoundedAndDoesNotIncludeLogControlCharacters() {
        val names = listOf("firefox", "firefox", "dbus-daemon", "x\nInjected", "gvfsd", "gvfsd")
        assertEquals("firefox=2, gvfsd=2, dbus-daemon=1, other=1", GuestProcessStats.summarizeNames(names, 3))
        val summary = GuestProcessStats.summarizeNames(listOf("x\nInjected", "a".repeat(200)))
        assertFalse(summary.contains('\n'))
        assertTrue(summary.length < 100)
    }
}
