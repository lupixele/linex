package com.linex.vm.network

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import android.system.Os

/** Real Android resolver through anonymous transport; does not prove guest networking. */
class AndroidSystemDnsTest {
    @Test fun concurrentSystemQueriesReuseDnsIdButRetainSeparateRequestIdentities() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val (androidEnd, nativeEnd) = AndroidDnsBridgeTest.socketPair()
        val bridge = AndroidDnsBridge.start(context, 21, androidEnd)
        try {
            val queries = (1L..8L).associateWith { id -> AndroidDnsBridgeTest.question(if (id % 2L == 0L) 1 else 28) }
            queries.forEach { (id, query) ->
                val transport = if (id <= 4L) DnsTransport.UDP else DnsTransport.TCP
                AndroidDnsBridgeTest.send(nativeEnd, DnsBridgeFrame.request(21, id, transport, query).encode())
            }
            val seen = mutableSetOf<Long>()
            // One overall deadline bounds the batch; individual receives must
            // not multiply the engine's 10-second deadline by the query count.
            val deadline = System.nanoTime() + 12_000_000_000L
            repeat(8) {
                val remainingMillis = (deadline - System.nanoTime()) / 1_000_000L
                assertTrue("Concurrent Android resolver batch deadline", remainingMillis > 0)
                val reply = AndroidDnsBridgeTest.receive(nativeEnd, remainingMillis.coerceAtMost(12_000).toInt())
                assertEquals(21L, reply.generation)
                assertTrue("Unexpected or duplicate DNS bridge identity", queries.containsKey(reply.requestId) && seen.add(reply.requestId))
                assertEquals("Android DNS resolution failed: ${reply.error}", DnsBridgeFrame.ANSWER, reply.kind)
                val transport = if (reply.requestId <= 4L) DnsTransport.UDP else DnsTransport.TCP
                assertEquals(transport, reply.transport)
                val answer = reply.payload()
                assertEquals(0, answer[3].toInt() and 15)
                assertArrayEquals(answer, DnsWire.answerFor(DnsWire.parseQuery(queries.getValue(reply.requestId)), transport, answer))
            }
            assertEquals(8, seen.size)
            assertEquals(0, bridge.diagnostics().pendingCount)
        } finally { bridge.close(); Os.close(nativeEnd) }
    }

    @Test fun systemResolverReturnsWireAnswersForAddressAndOrdinaryRecordTypes() {
        assertTrue("Full wire resolver proof requires API29+", Build.VERSION.SDK_INT >= 29)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val (androidEnd, nativeEnd) = AndroidDnsBridgeTest.socketPair()
        val bridge = AndroidDnsBridge.start(context, 17, androidEnd)
        try {
            for ((index, type) in listOf(1, 28, 16).withIndex()) {
                val query = AndroidDnsBridgeTest.question(type)
                AndroidDnsBridgeTest.send(nativeEnd,
                    DnsBridgeFrame.request(17, index + 1L, DnsTransport.TCP, query).encode())
                val reply = AndroidDnsBridgeTest.receive(nativeEnd, 12_000)
                assertEquals("Android DNS resolution failed: ${reply.error}", DnsBridgeFrame.ANSWER, reply.kind)
                val answer = reply.payload()
                assertEquals(0, answer[3].toInt() and 15)
                assertArrayEquals(answer, DnsWire.answerFor(DnsWire.parseQuery(query), DnsTransport.TCP, answer))
            }
            assertEquals(DnsResolverMode.WIRE, bridge.diagnostics().resolverMode)
            assertTrue(bridge.diagnostics().pendingCount == 0)
        } finally { bridge.close(); Os.close(nativeEnd) }
        assertFalse(bridge.isRunning)
    }
}
