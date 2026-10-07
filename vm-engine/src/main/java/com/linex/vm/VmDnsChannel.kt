package com.linex.vm

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.linex.vm.network.AndroidDnsBridge
import com.linex.vm.network.DnsBackend
import java.io.Closeable
import java.io.FileDescriptor
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/** Service-owned anonymous DNS channel. JNI only borrows a scoped duplicate. */
internal class VmDnsChannel private constructor(
    val generation: Long, private val nativeEnd: FileDescriptor,
    private val bridge: AndroidDnsBridge,
) : Closeable {
    private val closed = AtomicBoolean()

    fun <T> withNativeEndpoint(block: (Int, Long) -> T): T {
        check(!closed.get()) { "VM DNS channel is closed" }
        return ParcelFileDescriptor.dup(nativeEnd).use { block(it.fd, generation) }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { bridge.close() } finally {
                try { Os.close(nativeEnd) } catch (_: ErrnoException) { }
            }
        }
    }

    companion object {
        // Linux/Bionic socket flags exist on all supported Android kernels;
        // their OsConstants Java fields were only exposed in API29.
        private const val SOCK_CLOEXEC = 0x80000
        private const val SOCK_NONBLOCK = 0x800
        fun start(context: Context): VmDnsChannel {
            val random = SecureRandom()
            var generation: Long
            do { generation = random.nextLong() and Long.MAX_VALUE } while (generation == 0L)
            return create(generation) { AndroidDnsBridge.start(context, generation, it) }
        }

        internal fun startForTest(generation: Long, backend: DnsBackend): VmDnsChannel =
            create(generation) { AndroidDnsBridge.startForTest(generation, it, backend) }

        private fun create(generation: Long, consumeAndroidEnd: (FileDescriptor) -> AndroidDnsBridge): VmDnsChannel {
            require(generation > 0)
            val androidEnd = FileDescriptor()
            val nativeEnd = FileDescriptor()
            Os.socketpair(OsConstants.AF_UNIX,
                OsConstants.SOCK_SEQPACKET or SOCK_CLOEXEC or SOCK_NONBLOCK,
                0, androidEnd, nativeEnd)
            try {
                // The bridge consumes androidEnd, including when its setup fails.
                return VmDnsChannel(generation, nativeEnd, consumeAndroidEnd(androidEnd))
            } catch (failure: Exception) {
                try { Os.close(nativeEnd) } catch (_: ErrnoException) { }
                throw failure
            }
        }
    }
}
