package com.linex.vm.network

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class DnsCallbackDeliveryTest {
    @Test fun saturatedExecutorCancelsResolverBeforeReportingCapacity() {
        val executor = pool()
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            executor.execute { running.countDown(); release.await() }
            assertTrue(running.await(5, TimeUnit.SECONDS))
            executor.execute { }
            assertRejectionClosesResolver(executor)
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun shutdownExecutorCancelsResolverBeforeReportingCapacity() {
        val executor = pool()
        executor.shutdownNow()
        assertRejectionClosesResolver(executor)
    }

    @Test fun closeCancelsUndeliveredOperationsAndPreventsNewOwnership() {
        val operations = DnsCallbackOperations()
        val cancelled = mutableListOf<Int>()
        val completed = operations.register { cancelled.add(1) }!!
        val cancelledBeforeClose = operations.register { cancelled.add(2) }!!
        val queued = operations.register { cancelled.add(3) }!!
        assertTrue(completed.complete())
        cancelledBeforeClose.cancel()
        operations.close()
        operations.close()
        queued.cancel()
        assertEquals(listOf(2, 3), cancelled)
        assertNull(operations.register { fail("Closed scope cannot acquire a resolver") })
        assertFalse(queued.complete())
    }

    @Test fun operationCapacityIsBoundedAndCompletedQueriesReleaseTheirSlot() {
        val operations = DnsCallbackOperations()
        val active = List(64) { operations.register { }!! }
        assertNull(operations.register { })
        active.first().complete()
        assertNotNull(operations.register { })
        operations.close()
    }

    private fun assertRejectionClosesResolver(executor: ThreadPoolExecutor) {
        val events = mutableListOf<String>()
        val delivery = DnsCallbackDelivery(executor, { events.add("cancel") }, { events.add("capacity") })
        delivery.execute { fail("Rejected framework callback must not run") }
        assertEquals(listOf("cancel", "capacity"), events)
    }

    private fun pool() = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1), ThreadPoolExecutor.AbortPolicy())
}
