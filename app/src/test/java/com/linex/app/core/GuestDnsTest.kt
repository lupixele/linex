package com.linex.app.core

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class GuestDnsTest {
    @Test fun usesNetworkResolversAndRemovesDuplicates() {
        val value = GuestDns.configuration(listOf("192.168.1.1", "192.168.1.1", "2001:4860:4860::8888").map { InetAddress.getByName(it) })
        assertTrue(value.startsWith("nameserver 192.168.1.1\n"))
        assertEquals(2, value.lines().count { it.startsWith("nameserver") })
    }
    @Test fun rejectsUnusableResolversWithoutInventingPublicFallback() {
        assertThrows(IllegalArgumentException::class.java) {
            GuestDns.configuration(listOf(InetAddress.getByName("127.0.0.1")))
        }
    }
}
