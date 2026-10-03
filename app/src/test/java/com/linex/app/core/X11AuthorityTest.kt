package com.linex.app.core

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class X11AuthorityTest {
    @Test fun serializesStandardAuthorityRecord() {
        val cookie = ByteArray(16) { it.toByte() }
        val input = DataInputStream(ByteArrayInputStream(X11Authority.encode(cookie)))
        assertEquals(65535, input.readUnsignedShort())
        fun field(): ByteArray = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
        assertArrayEquals(byteArrayOf(), field())
        assertEquals("0", field().toString(Charsets.US_ASCII))
        assertEquals("MIT-MAGIC-COOKIE-1", field().toString(Charsets.US_ASCII))
        assertArrayEquals(cookie, field())
        assertEquals(-1, input.read())
    }
}
