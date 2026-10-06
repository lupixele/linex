package com.linex.vm.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DnsWireTest {
    @Test fun validatesOneOrdinaryQuestionAndDefaultUdpLimit() {
        val query = DnsWire.parseQuery(question())
        assertEquals(0x1234, query.transactionId)
        assertEquals(1, query.type)
        assertEquals(512, query.udpPayloadLimit)
        assertArrayEquals(question(), query.wire())
    }

    @Test fun acceptsEdnsButBoundsItsAdvertisedUdpSize() {
        val packet = question().copyOf(question().size + 11)
        packet[11] = 1
        val offset = question().size
        byteArrayOf(0, 0, 41, 0x10, 0, 0, 0, 0x80.toByte(), 0, 0, 0)
            .copyInto(packet, offset)
        assertEquals(1232, DnsWire.parseQuery(packet).udpPayloadLimit)
    }

    @Test fun rejectsOversizedQueryAndNonOrdinaryRequests() {
        assertInvalid(ByteArray(1233))
        assertInvalid(question().apply { this[2] = 0x81.toByte() })
        assertInvalid(question().apply { this[2] = 0x29 })
        assertInvalid(question().apply { this[5] = 2 })
        assertInvalid(question().apply { this[7] = 1 })
        assertInvalid(question().apply { this[2] = 5 })
        assertInvalid(question().apply { this[3] = 1 })
        assertThrows(UnsupportedDnsQueryException::class.java) {
            DnsWire.parseQuery(question(type = 252))
        }
    }

    @Test fun rejectsLoopsHeaderPointersAndTruncatedNames() {
        assertInvalid(question().apply { this[12] = 0xc0.toByte(); this[13] = 12 })
        assertInvalid(question().apply { this[12] = 0xc0.toByte(); this[13] = 0 })
        assertInvalid(question().apply { this[12] = 0xc0.toByte(); this[13] = 0x7f })
        assertInvalid(question().apply { this[12] = 64 })
        assertInvalid(question().copyOf(15))
        assertInvalid(question() + byteArrayOf(0))
    }

    @Test fun rejectsMalformedOptLengthAndMultipleAdditionalRecords() {
        val packet = question() + byteArrayOf(0, 0, 41, 4, -48, 0, 0, 0, 0, 0, 4, 0)
        packet[11] = 1
        assertInvalid(packet)
        assertInvalid(question().apply { this[11] = 2 })
    }

    @Test fun createsValidServfailAndTruncationWithoutPartialRecords() {
        val query = DnsWire.parseQuery(question())
        val failure = DnsWire.failure(query)
        assertEquals(0x1234, unsigned16(failure, 0))
        assertEquals(0x8182, unsigned16(failure, 2))
        assertArrayEquals(question().copyOfRange(12, question().size), failure.copyOfRange(12, failure.size))
        val answer = question().apply { this[2] = 0x81.toByte(); this[3] = 0x80.toByte() }
        assertArrayEquals(answer, DnsWire.answerFor(query, DnsTransport.TCP, answer))
        val large = answer.copyOf(600)
        // One complete opaque record is valid; it must never be cut halfway.
        large[7] = 1
        val offset = answer.size
        byteArrayOf(0xc0.toByte(), 12, 0, 16, 0, 1, 0, 0, 0, 30, 2, 47).copyInto(large, offset)
        val truncated = DnsWire.answerFor(query, DnsTransport.UDP, large)
        assertEquals(question().size, truncated.size)
        assertEquals(0x8380, unsigned16(truncated, 2))
        assertEquals(0, unsigned16(truncated, 6))
        assertArrayEquals(large, DnsWire.answerFor(query, DnsTransport.TCP, large))
    }

    @Test fun rejectsMismatchedOrMalformedResolverAnswers() {
        val query = DnsWire.parseQuery(question())
        val answer = question().apply { this[2] = 0x81.toByte(); this[3] = 0x80.toByte() }
        assertThrows(IllegalArgumentException::class.java) {
            DnsWire.answerFor(query, DnsTransport.TCP, answer.apply { this[0] = 0 })
        }
        assertThrows(IllegalArgumentException::class.java) {
            DnsWire.answerFor(query, DnsTransport.TCP, question())
        }
        assertThrows(IllegalArgumentException::class.java) {
            DnsWire.answerFor(query, DnsTransport.TCP, ByteArray(8193))
        }
    }

    @Test fun preservesNegativeRepliesAndCaseInsensitiveQuestionMatching() {
        val query = DnsWire.parseQuery(question())
        val answer = question().apply { this[2] = 0x81.toByte(); this[3] = 0x83.toByte(); this[13] = 69 }
        assertArrayEquals(answer, DnsWire.answerFor(query, DnsTransport.UDP, answer))
    }

    @Test fun legacyModeSynthesizesOnlyBoundedUnauthenticatedAddressRecords() {
        val query = DnsWire.parseQuery(question())
        assertEquals("example.com", DnsWire.legacyName(query))
        val answer = DnsWire.legacyAnswer(query, listOf(byteArrayOf(192.toByte(), 0, 2, 1)))
        assertEquals(1, unsigned16(answer, 6))
        assertEquals(0, unsigned16(answer, 2) and 0x20)
        assertArrayEquals(answer, DnsWire.answerFor(query, DnsTransport.TCP, answer))
        assertThrows(UnsupportedDnsQueryException::class.java) {
            DnsWire.legacyAnswer(DnsWire.parseQuery(question(type = 16)), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            DnsWire.legacyAnswer(query, List(33) { byteArrayOf(192.toByte(), 0, 2, 1) })
        }
    }

    @Test fun boundsCompressedAnswerPointerChainWork() {
        val query = DnsWire.parseQuery(question())
        val header = question().apply { this[2] = 0x81.toByte(); this[3] = 0x80.toByte(); this[7] = 2 }
        val firstRecord = byteArrayOf(-64, 12, -1, 0, 0, 1, 0, 0, 0, 0, 1, 4)
        val start = header.size + firstRecord.size
        val chain = ByteArray(260)
        repeat(130) { index ->
            val target = if (index == 0) 12 else start + (index - 1) * 2
            chain[index * 2] = (0xc0 or (target ushr 8)).toByte()
            chain[index * 2 + 1] = target.toByte()
        }
        val target = start + 258
        val lastRecord = byteArrayOf((0xc0 or (target ushr 8)).toByte(), target.toByte(), -1, 0, 0, 1, 0, 0, 0, 0, 0, 0)
        assertThrows(IllegalArgumentException::class.java) {
            DnsWire.answerFor(query, DnsTransport.TCP, header + firstRecord + chain + lastRecord)
        }
    }

    @Test fun rejectsFirstQueryQuestionPointerIntoEmbeddedBinaryLabelZero() {
        val header = question().copyOf(12)
        val malformed = header + byteArrayOf(3, 97, 0, 98, -64, 14, 0, 1, 0, 1)
        assertInvalid(malformed)
        // A forward pointer can fit within the packet yet still has no prior name.
        assertInvalid(header + byteArrayOf(-64, 16, 0, 1, 0, 1))
    }

    @Test fun rejectsCompressedEchoedFirstAnswerQuestion() {
        val query = DnsWire.parseQuery(question().copyOf(12) + byteArrayOf(3, 97, 0, 98, 0, 0, 1, 0, 1))
        val header = question().copyOf(12).apply { this[2] = 0x81.toByte(); this[3] = 0x80.toByte() }
        val malformed = header + byteArrayOf(3, 97, 0, 98, -64, 14, 0, 1, 0, 1)
        assertThrows(IllegalArgumentException::class.java) { DnsWire.answerFor(query, DnsTransport.TCP, malformed) }
    }

    @Test fun preservesLegitimateBinaryLabelsAndMarksLegacyModeUnsupported() {
        val wire = question().copyOf(12) + byteArrayOf(3, 97, 0, 98, 0, 0, 1, 0, 1)
        val query = DnsWire.parseQuery(wire)
        assertArrayEquals(wire, query.wire())
        assertThrows(UnsupportedDnsQueryException::class.java) { DnsWire.legacyName(query) }
    }

    private fun assertInvalid(packet: ByteArray) {
        assertThrows(IllegalArgumentException::class.java) { DnsWire.parseQuery(packet) }
    }

    companion object {
        fun question(type: Int = 1): ByteArray = byteArrayOf(
            0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            7, 101, 120, 97, 109, 112, 108, 101, 3, 99, 111, 109, 0,
            (type ushr 8).toByte(), type.toByte(), 0, 1)
        private fun unsigned16(bytes: ByteArray, offset: Int) =
            ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
    }
}
