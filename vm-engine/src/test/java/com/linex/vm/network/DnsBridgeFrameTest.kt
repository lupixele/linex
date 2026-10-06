package com.linex.vm.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DnsBridgeFrameTest {
    @Test fun roundTripsIdentityAndUsesDocumentedBigEndianOffsets() {
        val wire = DnsBridgeFrame.request(0x0102030405060708, 9, DnsTransport.UDP, DnsWireTest.question()).encode()
        assertArrayEquals(byteArrayOf(76, 88, 68, 78, 1, 1, 1, 0), wire.copyOfRange(0, 8))
        val header = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x0102030405060708, header.getLong(8))
        assertEquals(9, header.getLong(16))
        assertEquals(DnsWireTest.question().size, header.getInt(24))
        assertEquals(0, header.getInt(28))
        val frame = DnsBridgeFrame.decode(wire)
        assertEquals(9, frame.requestId)
        assertArrayEquals(DnsWireTest.question(), frame.payload())
    }

    @Test fun rejectsUnknownProtocolReservedBytesAndTruncatedDatagrams() {
        val wire = DnsBridgeFrame.request(1, 1, DnsTransport.TCP, DnsWireTest.question()).encode()
        for (offset in listOf(0, 4, 5, 6, 7, 28)) {
            assertInvalid(wire.copyOf().apply { this[offset] = 99 })
        }
        assertInvalid(wire.copyOf(31))
        assertInvalid(wire.copyOf(wire.size - 1))
        assertInvalid(wire + byteArrayOf(0))
        assertInvalid(wire.copyOf().apply { ByteBuffer.wrap(this).putInt(24, -1) })
        assertInvalid(wire.copyOf().apply { ByteBuffer.wrap(this).putLong(16, 0) })
    }

    @Test fun boundsRequestAnswerAndErrorPayloadsBeforeAcceptingFrames() {
        assertThrows(IllegalArgumentException::class.java) {
            DnsBridgeFrame.request(1, 1, DnsTransport.UDP, ByteArray(1233))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DnsBridgeFrame.answer(1, 1, DnsTransport.TCP, ByteArray(8193))
        }
        val failure = DnsBridgeFrame.error(1, 1, DnsTransport.UDP, DnsBridgeError.TIMEOUT)
        assertEquals(DnsBridgeError.TIMEOUT, DnsBridgeFrame.decode(failure.encode()).error)
        assertEquals(0, failure.payload().size)
        val malformed = failure.encode() + byteArrayOf(0)
        ByteBuffer.wrap(malformed).putInt(24, 1)
        assertInvalid(malformed)
    }

    @Test fun freezesCallerAndReturnedPayloads() {
        val source = DnsWireTest.question()
        val frame = DnsBridgeFrame.request(1, 1, DnsTransport.UDP, source)
        source.fill(0)
        frame.payload().fill(0)
        assertArrayEquals(DnsWireTest.question(), frame.payload())
    }

    @Test fun distinguishesOneWayCancellationFromResolverFailures() {
        val cancel = DnsBridgeFrame.cancel(1, 2, DnsTransport.TCP, DnsBridgeError.STOPPED)
        val parsed = DnsBridgeFrame.decode(cancel.encode())
        assertEquals(4, parsed.kind)
        assertEquals(DnsBridgeError.STOPPED, parsed.error)
        assertEquals(0, parsed.payloadSize)
        assertThrows(IllegalArgumentException::class.java) {
            DnsBridgeFrame.cancel(1, 2, DnsTransport.TCP, DnsBridgeError.MALFORMED)
        }
    }

    private fun assertInvalid(packet: ByteArray) {
        assertThrows(IllegalArgumentException::class.java) { DnsBridgeFrame.decode(packet) }
    }
}
