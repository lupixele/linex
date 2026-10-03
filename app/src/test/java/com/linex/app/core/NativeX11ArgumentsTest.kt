package com.linex.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NativeX11ArgumentsTest {
    @Test fun disablesNetworkListenerAndLeavesGeometryToSurface() {
        val args = NativeX11Arguments.build(120, "/private/.auth").toList()
        assertEquals(listOf(":0", "-nolisten", "tcp", "-auth", "/private/.auth", "-dpi", "120",
            "-extension", "MIT-SHM", "-fp", "built-ins"), args)
        assertFalse(args.contains("-screen"))
        assertFalse(args.contains("-ac"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidDpi() { NativeX11Arguments.build(0, "/private/.auth") }
}
