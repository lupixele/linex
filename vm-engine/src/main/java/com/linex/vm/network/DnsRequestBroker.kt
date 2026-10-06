package com.linex.vm.network

import java.io.Closeable
import java.util.ArrayDeque

fun interface DnsCancellation { fun cancel() }

sealed interface DnsBackendResult {
    class Answer(val wire: ByteArray) : DnsBackendResult
    class Failure(val error: DnsBridgeError) : DnsBackendResult
}

interface DnsBackend : Closeable {
    /** Must return promptly; legacy blocking resolution belongs on its bounded executor. */
    fun query(question: DnsQuestion, completion: (DnsBackendResult) -> Unit): DnsCancellation
    override fun close() = Unit
}

data class DnsBrokerMetrics(
    val resolverCalls: Long, val answersQueued: Long, val errors: Map<DnsBridgeError, Long>,
    val lateCompletions: Long, val nativeCancellations: Long,
    val latencySamples: Long, val totalLatencyNanos: Long
)

/** Thread-safe request lifetime boundary. Only the socket worker drains replies. */
class DnsRequestBroker(
    private val generation: Long, private val backend: DnsBackend,
    private val nanoTime: () -> Long = System::nanoTime
) : Closeable {
    private class Pending(val frame: DnsBridgeFrame, val query: DnsQuestion, val started: Long) {
        var cancellation: DnsCancellation? = null
    }
    private val lock = Any()
    private val pending = LinkedHashMap<Long, Pending>()
    private val replies = ArrayDeque<DnsBridgeFrame>()
    private var bytes = 0
    private var highestId = 0L
    private var closed = false
    private var resolverGeneration = 0L
    private var resolverCalls = 0L
    private var answersQueued = 0L
    private val errorCounts = LongArray(DnsBridgeError.entries.size)
    private var lateCompletions = 0L
    private var nativeCancellations = 0L
    private var latencySamples = 0L
    private var totalLatencyNanos = 0L

    init { require(generation > 0) }
    val pendingCount: Int get() = synchronized(lock) { pending.size }
    val queuedBytes: Int get() = synchronized(lock) { bytes }
    val networkGeneration: Long get() = synchronized(lock) { resolverGeneration }
    val canReadRequest: Boolean get() = synchronized(lock) { canReadLocked() }
    fun metrics(): DnsBrokerMetrics = synchronized(lock) {
        DnsBrokerMetrics(resolverCalls, answersQueued,
            DnsBridgeError.entries.associateWith { errorCounts[it.ordinal] }.filterValues { it != 0L },
            lateCompletions, nativeCancellations, latencySamples, totalLatencyNanos)
    }

    /** False means apply transport backpressure; true includes safe rejection/drop. */
    fun accept(frame: DnsBridgeFrame): Boolean {
        if (frame.kind == DnsBridgeFrame.CANCEL) {
            cancelFromNative(frame)
            return true
        }
        val request: Pending
        synchronized(lock) {
            if (!canReadLocked()) return false
            if (frame.kind != DnsBridgeFrame.REQUEST || frame.generation != generation ||
                frame.requestId <= highestId) return true
            highestId = frame.requestId
            val query = try { DnsWire.parseQuery(frame.payload()) } catch (_: UnsupportedDnsQueryException) {
                enqueueError(frame, DnsBridgeError.UNSUPPORTED); return true
            } catch (_: IllegalArgumentException) {
                enqueueError(frame, DnsBridgeError.MALFORMED); return true
            }
            if (pending.size == MAX_PENDING) {
                enqueueError(frame, DnsBridgeError.CAPACITY); return true
            }
            request = Pending(frame, query, nanoTime())
            pending[frame.requestId] = request
            resolverCalls++
        }
        val cancellation = try {
            backend.query(request.query) { result -> finish(request, result) }
        } catch (_: RuntimeException) {
            finish(request, DnsBackendResult.Failure(DnsBridgeError.RESOLVER_FAILURE))
            return true
        }
        val cancel = synchronized(lock) {
            if (pending[frame.requestId] === request) {
                request.cancellation = cancellation; false
            } else true
        }
        if (cancel) cancelSafely(cancellation)
        return true
    }

    /** Absolute deadlines continue to expire while QEMU/its guest is paused. */
    fun expire() {
        val cancellations = synchronized(lock) {
            val now = nanoTime()
            val expired = pending.values.filter { now - it.started >= DEADLINE_NANOS }
            expired.forEach {
                latency(it)
                pending.remove(it.frame.requestId)
                enqueueError(it.frame, DnsBridgeError.TIMEOUT)
            }
            expired.mapNotNull { it.cancellation }
        }
        cancellations.forEach(::cancelSafely)
    }

    fun networkChanged() {
        val cancellations = synchronized(lock) {
            if (closed) return
            resolverGeneration++
            val oldReplies = replies.toList()
            replies.clear(); bytes = 0
            oldReplies.forEach { queued ->
                enqueue(if (queued.kind == DnsBridgeFrame.ANSWER)
                    error(queued, DnsBridgeError.NETWORK_CHANGED) else queued,
                    countEvent = queued.kind == DnsBridgeFrame.ANSWER)
            }
            val active = pending.values.toList()
            pending.clear()
            active.forEach { latency(it); enqueueError(it.frame, DnsBridgeError.NETWORK_CHANGED) }
            active.mapNotNull { it.cancellation }
        }
        cancellations.forEach(::cancelSafely)
    }

    fun takeReply(): DnsBridgeFrame? = synchronized(lock) {
        val frame = replies.pollFirst() ?: return null
        bytes -= DnsBridgeFrame.HEADER_BYTES + frame.payloadSize
        frame
    }

    fun peekReply(): DnsBridgeFrame? = synchronized(lock) { replies.peekFirst() }

    /** The sender must be nonblocking. Sending and network invalidation are serialized. */
    fun sendNextReply(sender: (DnsBridgeFrame) -> Boolean): Boolean = synchronized(lock) {
        if (closed) return false
        val frame = replies.peekFirst() ?: return false
        if (!sender(frame)) return false
        takeReply()
        true
    }

    override fun close() {
        val cancellations = synchronized(lock) {
            if (closed) return
            closed = true
            val active = pending.values.mapNotNull { it.cancellation }
            pending.clear(); replies.clear(); bytes = 0
            active
        }
        cancellations.forEach(::cancelSafely)
    }

    private fun finish(request: Pending, result: DnsBackendResult) {
        synchronized(lock) {
            if (closed || pending[request.frame.requestId] !== request) { lateCompletions++; return }
            pending.remove(request.frame.requestId)
            latency(request)
            val frame = request.frame
            val reply = if (nanoTime() - request.started >= DEADLINE_NANOS)
                error(frame, DnsBridgeError.TIMEOUT) else when (result) {
                is DnsBackendResult.Failure -> error(frame, result.error)
                is DnsBackendResult.Answer -> try {
                    val answer = DnsWire.answerFor(request.query, frame.transport, result.wire)
                    DnsBridgeFrame.answer(generation, frame.requestId, frame.transport, answer)
                } catch (_: IllegalArgumentException) { error(frame, DnsBridgeError.RESOLVER_FAILURE) }
            }
            // Reserve one small error for each other pending request. An answer
            // that does not fit becomes CAPACITY, preserving the fixed memory bound.
            val size = DnsBridgeFrame.HEADER_BYTES + reply.payloadSize
            if (bytes + size + pending.size * DnsBridgeFrame.HEADER_BYTES <= MAX_QUEUED_BYTES) {
                enqueue(reply)
            } else enqueueError(frame, DnsBridgeError.CAPACITY)
        }
    }

    private fun cancelFromNative(frame: DnsBridgeFrame) {
        val cancellation = synchronized(lock) {
            if (closed || frame.generation != generation) return
            val active = pending[frame.requestId]
            val handle = if (active?.frame?.transport == frame.transport) {
                nativeCancellations++; latency(active)
                pending.remove(frame.requestId); active.cancellation
            } else null
            val iterator = replies.iterator()
            while (iterator.hasNext()) {
                val queued = iterator.next()
                if (queued.requestId == frame.requestId && queued.transport == frame.transport) {
                    bytes -= DnsBridgeFrame.HEADER_BYTES + queued.payloadSize
                    iterator.remove()
                }
            }
            handle
        }
        cancellation?.let(::cancelSafely)
    }

    private fun canReadLocked() = !closed &&
        replies.size + pending.size < MAX_QUEUED_FRAMES &&
        bytes + (pending.size + 1) * DnsBridgeFrame.HEADER_BYTES <= MAX_QUEUED_BYTES
    private fun enqueueError(frame: DnsBridgeFrame, failure: DnsBridgeError) = enqueue(error(frame, failure))
    private fun error(frame: DnsBridgeFrame, failure: DnsBridgeError) =
        DnsBridgeFrame.error(generation, frame.requestId, frame.transport, failure)
    private fun enqueue(frame: DnsBridgeFrame, countEvent: Boolean = true) {
        if (countEvent) {
            if (frame.kind == DnsBridgeFrame.ANSWER) answersQueued++
            frame.error?.let { errorCounts[it.ordinal]++ }
        }
        replies.addLast(frame)
        bytes += DnsBridgeFrame.HEADER_BYTES + frame.payloadSize
        check(bytes + pending.size * DnsBridgeFrame.HEADER_BYTES <= MAX_QUEUED_BYTES)
        check(replies.size + pending.size <= MAX_QUEUED_FRAMES)
    }
    private fun latency(request: Pending) {
        latencySamples++
        totalLatencyNanos += (nanoTime() - request.started).coerceAtLeast(0)
    }
    private fun cancelSafely(cancellation: DnsCancellation) {
        try { cancellation.cancel() } catch (_: RuntimeException) { /* stale results remain fenced */ }
    }

    companion object {
        const val MAX_PENDING = 64
        const val MAX_QUEUED_BYTES = 512 * 1024
        const val MAX_QUEUED_FRAMES = 128
        const val DEADLINE_NANOS = 10_000_000_000L
    }
}
