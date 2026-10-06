package com.linex.vm.network

import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import org.junit.Assert.*
import org.junit.Test
import java.io.FileDescriptor
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual Android anonymous datagrams; a fake resolver proves only this transport boundary. */
class AndroidDnsBridgeTest {
    @Test fun carriesIndependentRequestIdsAndRejectsOversizedBeforeResolver() {
        val (androidEnd, nativeEnd) = socketPair()
        val calls = AtomicInteger()
        val backend = object : DnsBackend {
            override fun query(question: DnsQuestion, completion: (DnsBackendResult) -> Unit): DnsCancellation {
                calls.incrementAndGet()
                completion(DnsBackendResult.Answer(question.wire().apply {
                    this[2] = 0x81.toByte(); this[3] = 0x80.toByte()
                }))
                return DnsCancellation { }
            }
        }
        val bridge = AndroidDnsBridge.startForTest(7, androidEnd, backend)
        try {
            send(nativeEnd, ByteArray(16 * 1024 + 1))
            for (id in 1L..2L) send(nativeEnd, DnsBridgeFrame.request(7, id, DnsTransport.UDP, question()).encode())
            val first = receive(nativeEnd, 5000)
            val second = receive(nativeEnd, 5000)
            assertEquals(1L, first.requestId)
            assertEquals(2L, second.requestId)
            assertEquals(DnsBridgeFrame.ANSWER, first.kind)
            assertEquals(2, calls.get())
            assertEquals(1L, bridge.diagnostics().malformedFrames)
        } finally { bridge.close(); Os.close(nativeEnd) }
        assertFalse(bridge.isRunning)
    }

    @Test fun nativeCancellationCancelsWithoutAcknowledgment() {
        val (androidEnd, nativeEnd) = socketPair()
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val backend = object : DnsBackend {
            override fun query(question: DnsQuestion, completion: (DnsBackendResult) -> Unit): DnsCancellation {
                entered.countDown()
                return DnsCancellation { cancelled.countDown() }
            }
        }
        val bridge = AndroidDnsBridge.startForTest(7, androidEnd, backend)
        try {
            send(nativeEnd, DnsBridgeFrame.request(7, 1, DnsTransport.TCP, question()).encode())
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            send(nativeEnd, DnsBridgeFrame.cancel(7, 1, DnsTransport.TCP, DnsBridgeError.STOPPED).encode())
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            assertEquals(0, readable(nativeEnd, 250))
            assertEquals(0, bridge.diagnostics().pendingCount)
        } finally { bridge.close(); Os.close(nativeEnd) }
    }

    @Test fun resolverDeadlineContinuesWithoutGuestActivityAndCloseIsIdempotent() {
        val (androidEnd, nativeEnd) = socketPair()
        val cancellations = AtomicInteger()
        val backend = object : DnsBackend {
            override fun query(question: DnsQuestion, completion: (DnsBackendResult) -> Unit) =
                DnsCancellation { cancellations.incrementAndGet() }
        }
        val bridge = AndroidDnsBridge.startForTest(7, androidEnd, backend)
        try {
            send(nativeEnd, DnsBridgeFrame.request(7, 1, DnsTransport.UDP, question()).encode())
            assertEquals(DnsBridgeError.TIMEOUT, receive(nativeEnd, 12_000).error)
            assertEquals(1, cancellations.get())
        } finally { bridge.close(); bridge.close(); Os.close(nativeEnd) }
        assertFalse(androidEnd.valid())
        assertFalse(bridge.isRunning)
    }

    companion object {
        private const val MSG_NOSIGNAL = 0x4000 // Linux/Bionic socket ABI, not a hidden framework API.
        internal fun socketPair(): Pair<FileDescriptor, FileDescriptor> {
            val first = FileDescriptor(); val second = FileDescriptor()
            Os.socketpair(OsConstants.AF_UNIX,
                OsConstants.SOCK_SEQPACKET or OsConstants.SOCK_CLOEXEC or OsConstants.SOCK_NONBLOCK,
                0, first, second)
            return first to second
        }
        internal fun send(fd: FileDescriptor, bytes: ByteArray) {
            assertEquals(bytes.size, Os.sendto(fd, bytes, 0, bytes.size, MSG_NOSIGNAL, null as InetAddress?, 0))
        }
        internal fun receive(fd: FileDescriptor, timeout: Int): DnsBridgeFrame {
            assertTrue("DNS bridge reply deadline", readable(fd, timeout) > 0)
            val bytes = ByteArray(16 * 1024)
            val length = Os.recvfrom(fd, bytes, 0, bytes.size, OsConstants.MSG_TRUNC, null)
            assertTrue(length in 32..bytes.size)
            return DnsBridgeFrame.decode(bytes.copyOf(length))
        }
        private fun readable(fd: FileDescriptor, timeout: Int): Int {
            val poll = StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() }
            return Os.poll(arrayOf(poll), timeout)
        }
        internal fun question(type: Int = 1): ByteArray = byteArrayOf(
            0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            7, 101, 120, 97, 109, 112, 108, 101, 3, 99, 111, 109, 0,
            (type ushr 8).toByte(), type.toByte(), 0, 1)
    }
}
