package com.linex.app.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class LatestFrameMailboxTest {
    @Test fun burstsKeepLatestFrameWithoutRescheduling() {
        var scheduled = 0
        val released = mutableListOf<Int>()
        val mailbox = LatestFrameMailbox<Int>({ scheduled++ }, {}, { released.add(it) })
        mailbox.offer(1); mailbox.offer(2); mailbox.offer(3)
        assertEquals(1, scheduled)
        assertEquals(listOf(1, 2), released)
        assertEquals(3, mailbox.take())
        mailbox.complete()
        mailbox.offer(4)
        assertEquals(2, scheduled)
        mailbox.close()
        assertEquals(listOf(1, 2, 4), released)
    }

    @Test fun arrivalDuringPresentationWaitsForNextAnimation() {
        var scheduled = 0
        val mailbox = LatestFrameMailbox<Int>({ scheduled++ }, {}, {})
        mailbox.offer(1)
        assertEquals(1, mailbox.take())
        mailbox.offer(2)
        assertEquals(1, scheduled)
        mailbox.complete()
        assertEquals(2, scheduled)
        assertEquals(2, mailbox.take())
        mailbox.complete()
        mailbox.close()
    }

    @Test fun closingCancelsAndReleasesLateFramesExactlyOnce() {
        var scheduled = 0
        var cancelled = 0
        val released = mutableListOf<Int>()
        val mailbox = LatestFrameMailbox<Int>({ scheduled++ }, { cancelled++ }, { released.add(it) })
        mailbox.offer(1)
        mailbox.close(); mailbox.close()
        mailbox.offer(2)
        mailbox.complete()
        assertNull(mailbox.take())
        assertEquals(1, scheduled)
        assertEquals(1, cancelled)
        assertEquals(listOf(1, 2), released)
    }

    @Test fun concurrentCloseCannotCancelBeforeAnInFlightPost() {
        val posting = CountDownLatch(1)
        val continuePosting = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val postedAfterCancel = AtomicBoolean(false)
        val mailbox = LatestFrameMailbox<Int>({
            posting.countDown()
            check(continuePosting.await(3, TimeUnit.SECONDS))
            postedAfterCancel.set(cancelled.get())
        }, { cancelled.set(true) }, {})
        val producer = thread { mailbox.offer(1) }
        assertTrue(posting.await(3, TimeUnit.SECONDS))
        val closer = thread { closing.countDown(); mailbox.close() }
        try {
            assertTrue(closing.await(3, TimeUnit.SECONDS))
        } finally {
            continuePosting.countDown()
            producer.join(3000)
            closer.join(3000)
        }
        assertFalse(producer.isAlive)
        assertFalse(closer.isAlive)
        assertTrue(cancelled.get())
        assertFalse("A cancelled view must not receive a late animation post", postedAfterCancel.get())
    }
}
