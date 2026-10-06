package com.linex.vm

import org.junit.Assert.*
import org.junit.Test

class VmNetworkModeTest {
    @Test fun rejectsUnknownModesInsteadOfEnablingOtherHostBackends() {
        for (value in listOf(-1, 2, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { VmNetworkMode.fromWire(value) }
        }
        assertEquals(VmNetworkMode.DISABLED, VmNetworkMode.fromWire(0))
        assertEquals(VmNetworkMode.SLIRP_V4, VmNetworkMode.fromWire(1))
    }

    @Test fun fixedNetworkArgumentsCannotAcquireCallerAddedOptions() {
        val first = VmNetworkMode.SLIRP_V4.qemuArguments()
        first[1] = "tap,script=untrusted"
        val next = VmNetworkMode.SLIRP_V4.qemuArguments()
        assertEquals("user,id=linexnet,ipv6=off", next[1])
        assertArrayEquals(arrayOf("-nic", "none"), VmNetworkMode.DISABLED.qemuArguments())
    }
}
