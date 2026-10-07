package com.linex.vm

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.linex.vm.network.DnsBackend
import com.linex.vm.network.DnsBackendResult
import com.linex.vm.network.DnsCancellation
import com.linex.vm.network.DnsQuestion
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class VmDnsChannelTest {
    @Test fun borrowedDescriptorsCloseOnReturnAndFailureWithoutClosingOwnedEndpoint() {
        val closes = AtomicInteger()
        val backend = object : DnsBackend {
            override fun query(question: DnsQuestion, completion: (DnsBackendResult) -> Unit) = DnsCancellation { }
            override fun close() { closes.incrementAndGet() }
        }
        val channel = VmDnsChannel.startForTest(37, backend)
        try {
            var borrowed = -1
            channel.withNativeEndpoint { fd, generation ->
                borrowed = fd
                assertEquals(37L, generation)
                ParcelFileDescriptor.fromFd(fd).use { duplicate ->
                    assertTrue(OsConstants.S_ISSOCK(Os.fstat(duplicate.fileDescriptor).st_mode))
                }
            }
            assertClosed(borrowed)
            try {
                channel.withNativeEndpoint<Unit> { fd, _ ->
                    borrowed = fd
                    throw IllegalStateException("Native launch rejection fixture")
                }
                fail("Launch rejection should propagate")
            } catch (_: IllegalStateException) { }
            assertClosed(borrowed)
            // A third scoped borrow proves prior calls did not consume the owner.
            channel.withNativeEndpoint { fd, _ -> ParcelFileDescriptor.fromFd(fd).close() }
        } finally { channel.close(); channel.close() }
        assertEquals(1, closes.get())
        try {
            channel.withNativeEndpoint { _, _ -> fail("Closed channel cannot lend a descriptor") }
            fail("Closed channel must reject")
        } catch (_: IllegalStateException) { }
    }

    private fun assertClosed(fd: Int) {
        try {
            ParcelFileDescriptor.fromFd(fd).use { fail("Scoped borrowed descriptor leaked") }
        } catch (_: IOException) { }
    }
}
