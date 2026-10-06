package com.linex.vm.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsRequestBrokerTest {
    private var now = 0L
    private val backend = FakeBackend()
    private val broker = DnsRequestBroker(7, backend) { now }

    @Test fun rejectsInvalidQueryBeforeCallingResolver() {
        val malformed = DnsWireTest.question().apply { this[12] = -64; this[13] = 12 }
        broker.accept(request(1, malformed))
        assertEquals(0, backend.callbacks.size)
        assertEquals(DnsBridgeError.MALFORMED, broker.takeReply()!!.error)
    }

    @Test fun boundsPendingRequestsAndDropsDuplicateOrWrongSession() {
        repeat(64) { assertTrue(broker.accept(request(it + 1L))) }
        assertEquals(64, broker.pendingCount)
        broker.accept(request(65))
        assertEquals(DnsBridgeError.CAPACITY, broker.takeReply()!!.error)
        broker.accept(request(1))
        broker.accept(DnsBridgeFrame.request(8, 66, DnsTransport.UDP, DnsWireTest.question()))
        assertEquals(64, backend.callbacks.size)
    }

    @Test fun timesOutAtAbsoluteDeadlineAndCancelsLateCompletions() {
        broker.accept(request(1))
        now = 9_999_999_999L
        broker.expire()
        assertEquals(1, broker.pendingCount)
        now++
        broker.expire()
        assertEquals(0, broker.pendingCount)
        assertEquals(1, backend.cancelled)
        assertEquals(DnsBridgeError.TIMEOUT, broker.takeReply()!!.error)
        backend.answer(0)
        assertEquals(null, broker.takeReply())
    }

    @Test fun networkChangeCancelsPendingAndNewQueriesCanRecover() {
        broker.accept(request(1))
        broker.networkChanged()
        assertEquals(1, backend.cancelled)
        assertEquals(DnsBridgeError.NETWORK_CHANGED, broker.takeReply()!!.error)
        backend.answer(0)
        assertEquals(null, broker.takeReply())
        broker.accept(request(2))
        backend.answer(1)
        assertEquals(2L, broker.takeReply()!!.requestId)
    }

    @Test fun dropsAlreadyQueuedOldNetworkAnswers() {
        broker.accept(request(1))
        backend.answer(0)
        broker.networkChanged()
        assertEquals(DnsBridgeError.NETWORK_CHANGED, broker.takeReply()!!.error)
    }

    @Test fun lateAnswerCannotBeatAbsoluteDeadlineBetweenExpiryTicks() {
        broker.accept(request(1))
        now = 10_000_000_000L
        backend.answer(0)
        assertEquals(DnsBridgeError.TIMEOUT, broker.takeReply()!!.error)
    }

    @Test fun boundsAggregateReplyQueueAndAppliesBackpressure() {
        var id = 1L
        while (broker.canReadRequest) {
            assertTrue(broker.accept(request(id++)))
            backend.answer(backend.callbacks.lastIndex, large = true)
        }
        assertTrue(broker.queuedBytes <= 512 * 1024)
        assertFalse(broker.accept(request(id)))
        while (broker.takeReply() != null) Unit
        assertEquals(0, broker.queuedBytes)
        assertTrue(broker.canReadRequest)
    }

    @Test fun closesIdempotentlyCancelsAndDropsStaleAnswers() {
        broker.accept(request(1))
        broker.close(); broker.close()
        assertEquals(1, backend.cancelled)
        backend.answer(0)
        assertFalse(broker.canReadRequest)
        assertFalse(broker.accept(request(2)))
        assertEquals(null, broker.takeReply())
    }

    @Test fun nativeCancellationIsOneWayExactAndDropsPendingOrQueuedAnswers() {
        broker.accept(request(1))
        broker.accept(DnsBridgeFrame.cancel(7, 1, DnsTransport.UDP, DnsBridgeError.STOPPED))
        assertEquals(1, broker.pendingCount)
        broker.accept(DnsBridgeFrame.cancel(7, 1, DnsTransport.TCP, DnsBridgeError.STOPPED))
        assertEquals(0, broker.pendingCount)
        assertEquals(1, backend.cancelled)
        backend.answer(0)
        assertEquals(null, broker.takeReply())
        broker.accept(request(2))
        backend.answer(1)
        broker.accept(DnsBridgeFrame.cancel(7, 2, DnsTransport.TCP, DnsBridgeError.TIMEOUT))
        assertEquals(null, broker.takeReply())
    }

    @Test fun immediateCallbackBeforeCancellationHandleReturnsIsSafe() {
        var cancellationCalls = 0
        val immediate = object : DnsBackend {
            override fun query(question: DnsQuestion, completion: (DnsBackendResult) -> Unit): DnsCancellation {
                completion(DnsBackendResult.Failure(DnsBridgeError.RESOLVER_FAILURE))
                return DnsCancellation { cancellationCalls++ }
            }
        }
        val broker = DnsRequestBroker(7, immediate) { now }
        assertTrue(broker.accept(request(1)))
        assertEquals(0, broker.pendingCount)
        assertEquals(1, cancellationCalls)
        assertEquals(DnsBridgeError.RESOLVER_FAILURE, broker.takeReply()!!.error)
    }

    @Test fun aggregatesCountsAndLatencyWithoutNamesOrPacketContents() {
        broker.accept(request(1))
        now = 25_000_000L
        backend.answer(0)
        broker.accept(request(2))
        now += 10_000_000_000L
        broker.expire()
        val metrics = broker.metrics()
        assertEquals(2L, metrics.resolverCalls)
        assertEquals(1L, metrics.answersQueued)
        assertEquals(1L, metrics.errors[DnsBridgeError.TIMEOUT])
        assertEquals(2L, metrics.latencySamples)
        assertEquals(10_025_000_000L, metrics.totalLatencyNanos)
        assertFalse(metrics.toString().contains("example"))
    }

    @Test fun nonblockingFailedSendKeepsReplyAndByteReservation() {
        broker.accept(request(1))
        backend.answer(0)
        val bytes = broker.queuedBytes
        assertFalse(broker.sendNextReply { false })
        assertEquals(bytes, broker.queuedBytes)
        assertTrue(broker.sendNextReply { true })
        assertEquals(0, broker.queuedBytes)
    }

    @Test fun networkChangesDoNotRecountQueuedFailures() {
        broker.accept(request(1, DnsWireTest.question(252)))
        broker.networkChanged(); broker.networkChanged()
        assertEquals(1L, broker.metrics().errors[DnsBridgeError.UNSUPPORTED])
    }

    @Test fun manyTinyMalformedQueriesCannotGrowAnUnboundedObjectQueue() {
        val invalid = DnsWireTest.question().apply { this[12] = 64 }
        var id = 1L
        while (broker.canReadRequest) broker.accept(request(id++, invalid))
        assertTrue(id <= 129L)
        assertEquals(0, backend.callbacks.size)
        assertTrue(broker.queuedBytes <= 512 * 1024)
    }

    private fun request(id: Long, bytes: ByteArray = DnsWireTest.question()) =
        DnsBridgeFrame.request(7, id, DnsTransport.TCP, bytes)

    private class FakeBackend : DnsBackend {
        val callbacks = mutableListOf<(DnsBackendResult) -> Unit>()
        var cancelled = 0
        override fun query(question: DnsQuestion, completion: (DnsBackendResult) -> Unit): DnsCancellation {
            callbacks.add(completion)
            return DnsCancellation { cancelled++ }
        }
        fun answer(index: Int, large: Boolean = false) {
            var answer = DnsWireTest.question().apply { this[2] = 0x81.toByte(); this[3] = 0x80.toByte() }
            if (large) {
                val offset = answer.size
                answer = answer.copyOf(8192)
                answer[7] = 1
                val length = answer.size - offset - 12
                byteArrayOf(-64, 12, 0, 16, 0, 1, 0, 0, 0, 30, (length ushr 8).toByte(), length.toByte())
                    .copyInto(answer, offset)
            }
            callbacks[index](DnsBackendResult.Answer(answer))
        }
    }
}
