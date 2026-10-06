package com.linex.vm.network

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.content.Context
import android.os.Build
import java.io.Closeable
import java.io.FileDescriptor
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

data class DnsBridgeDiagnostics(
    val pendingCount: Int, val queuedBytes: Int, val networkGeneration: Long,
    val malformedFrames: Long, val transportErrno: Int?, val resolverMode: DnsResolverMode,
    val privateDnsState: PrivateDnsState, val resolverMetrics: DnsBrokerMetrics
)

/** Consumes the service-owned Android end of an anonymous SOCK_SEQPACKET pair. */
class AndroidDnsBridge private constructor(
    generation: Long, private val endpoint: FileDescriptor, private val backend: DnsBackend,
    private val resolverMode: DnsResolverMode
) : Closeable {
    private val broker = DnsRequestBroker(generation, backend)
    private val stopped = AtomicBoolean()
    @Volatile private var malformed = 0L
    @Volatile private var transportErrno: Int? = null
    private val worker = Thread(::loop, "LinexVmDns")
    private var observer: DefaultDnsNetworkObserver? = null
    val isRunning: Boolean get() = !stopped.get() && worker.isAlive

    fun diagnostics() = DnsBridgeDiagnostics(broker.pendingCount, broker.queuedBytes,
        broker.networkGeneration, malformed, transportErrno, resolverMode,
        observer?.privateDnsState ?: PrivateDnsState.UNKNOWN, broker.metrics())

    override fun close() {
        if (stopped.compareAndSet(false, true)) {
            try { Os.shutdown(endpoint, OsConstants.SHUT_RDWR) } catch (_: ErrnoException) { }
        }
        if (Thread.currentThread() !== worker) {
            try { worker.join(1000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }

    private fun loop() {
        val receive = ByteArray(DnsBridgeFrame.MAX_FRAME_BYTES)
        val poll = StructPollfd().apply { fd = endpoint }
        try {
            while (!stopped.get()) {
                broker.expire()
                poll.events = ((if (broker.canReadRequest) OsConstants.POLLIN else 0) or
                    (if (broker.peekReply() != null) OsConstants.POLLOUT else 0)).toShort()
                poll.revents = 0
                Os.poll(arrayOf(poll), 100)
                val events = poll.revents.toInt()
                if (events and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) break
                if (events and OsConstants.POLLIN != 0) readRequests(receive)
                if (events and OsConstants.POLLOUT != 0) {
                    for (index in 0 until 16) {
                        if (!broker.sendNextReply(::send)) break
                    }
                }
            }
        } catch (failure: ErrnoException) {
            if (!stopped.get()) transportErrno = failure.errno
        } finally {
            stopped.set(true)
            try { observer?.close() } catch (_: RuntimeException) { }
            broker.close()
            try { backend.close() } catch (_: RuntimeException) { }
            try { Os.close(endpoint) } catch (_: ErrnoException) { }
        }
    }

    private fun readRequests(buffer: ByteArray) {
        repeat(16) {
            if (!broker.canReadRequest || stopped.get()) return
            val length = try {
                Os.recvfrom(endpoint, buffer, 0, buffer.size, OsConstants.MSG_TRUNC, null)
            } catch (failure: ErrnoException) {
                if (failure.errno == OsConstants.EAGAIN || failure.errno == OsConstants.EINTR) return
                throw failure
            }
            if (length == 0) { stopped.set(true); return }
            if (length !in DnsBridgeFrame.HEADER_BYTES..buffer.size) { malformed++; return@repeat }
            val frame = try { DnsBridgeFrame.decode(buffer.copyOf(length)) }
                catch (_: IllegalArgumentException) { malformed++; return@repeat }
            broker.accept(frame)
        }
    }

    private fun send(frame: DnsBridgeFrame): Boolean {
        val bytes = frame.encode()
        return try {
            val length = Os.sendto(endpoint, bytes, 0, bytes.size, MSG_NOSIGNAL, null as InetAddress?, 0)
            check(length == bytes.size) { "Partial DNS sequence packet" }
            true
        } catch (failure: ErrnoException) {
            if (failure.errno == OsConstants.EAGAIN || failure.errno == OsConstants.EINTR) false else throw failure
        }
    }

    companion object {
        private const val MSG_NOSIGNAL = 0x4000 // Linux/Bionic socket ABI; no process-wide signal change.

        /** Owns endpoint even if setup fails; caller continues to own only the native end. */
        internal fun startForTest(generation: Long, endpoint: FileDescriptor, backend: DnsBackend): AndroidDnsBridge {
            return startOwned(generation, endpoint, backend, DnsResolverMode.TEST_BOUNDARY, null)
        }

        fun start(context: Context, generation: Long, endpoint: FileDescriptor): AndroidDnsBridge {
            val mode = if (Build.VERSION.SDK_INT >= 29) DnsResolverMode.WIRE else DnsResolverMode.LIMITED_ADDRESSES
            val backend = try { AndroidDnsBackends.create(context.applicationContext) } catch (failure: Exception) {
                try { Os.close(endpoint) } catch (_: ErrnoException) { }
                throw failure
            }
            return startOwned(generation, endpoint, backend, mode, context.applicationContext)
        }

        private fun startOwned(generation: Long, endpoint: FileDescriptor, backend: DnsBackend,
                               mode: DnsResolverMode, context: Context?): AndroidDnsBridge {
            var bridge: AndroidDnsBridge? = null
            try {
                // The service constructs the socketpair; Android has no public
                // getsockoptInt. Native independently checks its socket type.
                require(OsConstants.S_ISSOCK(Os.fstat(endpoint).st_mode))
                // fcntlInt became public in API30. Earlier versions still use
                // the service's atomic SOCK_NONBLOCK|SOCK_CLOEXEC construction;
                // no reflection into older hidden methods is permitted.
                if (Build.VERSION.SDK_INT >= 30) {
                    require(Os.fcntlInt(endpoint, OsConstants.F_GETFL, 0) and OsConstants.O_NONBLOCK != 0)
                    require(Os.fcntlInt(endpoint, OsConstants.F_GETFD, 0) and OsConstants.FD_CLOEXEC != 0)
                }
                bridge = AndroidDnsBridge(generation, endpoint, backend, mode)
                if (context != null) bridge.observer = DefaultDnsNetworkObserver(context, bridge.broker::networkChanged)
                bridge.worker.start()
                return bridge
            } catch (failure: Exception) {
                try { bridge?.observer?.close() } catch (_: RuntimeException) { }
                try { backend.close() } catch (_: RuntimeException) { }
                try { Os.close(endpoint) } catch (_: ErrnoException) { }
                throw failure
            }
        }
    }
}
