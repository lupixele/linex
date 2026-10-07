package com.linex.vm.console

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class OwnedArgbFrameTest {
    @Test fun packsRgbaWithoutChangingOrPrematurelyReleasingOwnedPixels() {
        val pixels = intArrayOf(0xff123456.toInt(), 0x80785634.toInt())
        var recycled: IntArray? = null
        val frame = OwnedArgbFrame(2, 1, pixels) { recycled = it }
        val buffer = ByteBuffer.allocate(8)
        frame.writeRgba(buffer)
        assertArrayEquals(byteArrayOf(0x12, 0x34, 0x56, 0xff.toByte(), 0x78, 0x56, 0x34, 0x80.toByte()), buffer.array())
        assertNull(recycled)
        frame.close(); frame.close()
        assertSame(pixels, recycled)
        assertThrows(IllegalStateException::class.java) { frame.writeRgba(buffer) }
    }

    @Test fun rejectsInvalidDimensionsBeforeAllocatingUploadMemory() {
        assertThrows(IllegalArgumentException::class.java) { OwnedArgbFrame(0, 1, intArrayOf()) {} }
        assertThrows(IllegalArgumentException::class.java) { OwnedArgbFrame(4096, 4096, intArrayOf()) {} }
        assertThrows(IllegalArgumentException::class.java) { OwnedArgbFrame(1, 2, intArrayOf(1)) {} }
    }

    @Test fun dropsSupersededFramesAndNeverRecyclesTheCheckedOutFrame() {
        val releases = mutableListOf<Int>()
        fun frame(id: Int) = OwnedArgbFrame(1, 1, intArrayOf(id)) { releases += it[0] }
        val mailbox = OwnedFrameMailbox()
        val active = frame(1)
        mailbox.offer(active)
        assertSame(active, mailbox.takeReplacing {})
        mailbox.offer(frame(2)); mailbox.offer(frame(3))
        assertEquals(listOf(2), releases)
        mailbox.close()
        assertEquals(listOf(2, 3), releases)
        assertFalse(mailbox.offer(frame(4)))
        active.close()
        assertEquals(listOf(2, 3, 4, 1), releases)
    }

    @Test fun hiddenMailboxReleasesPendingAndRejectsNewFramesUntilResumed() {
        var released = 0
        fun frame() = OwnedArgbFrame(1, 1, intArrayOf(1)) { released++ }
        val mailbox = OwnedFrameMailbox()
        mailbox.offer(frame())
        mailbox.setAccepting(false)
        assertFalse(mailbox.offer(frame()))
        assertEquals(2, released)
        assertNull(mailbox.takeReplacing {})
        mailbox.setAccepting(true)
        assertTrue(mailbox.offer(frame()))
        mailbox.close(); mailbox.close()
        assertEquals(3, released)
    }

    @Test fun replacementReservesPendingUntilOldOwnerReleasedAndDropsConcurrentOffer() {
        val released = mutableListOf<Int>()
        val startedRelease = CountDownLatch(1)
        val finishRelease = CountDownLatch(1)
        val replacement = AtomicReference<OwnedArgbFrame?>()
        fun frame(id: Int) = OwnedArgbFrame(1, 1, intArrayOf(id)) { synchronized(released) { released += it[0] } }
        val mailbox = OwnedFrameMailbox()
        val old = frame(1)
        val pending = frame(2)
        mailbox.offer(pending)
        val worker = Thread {
            replacement.set(mailbox.takeReplacing {
                startedRelease.countDown()
                check(finishRelease.await(2, TimeUnit.SECONDS))
                old.close()
            })
        }
        worker.start()
        try {
            assertTrue(startedRelease.await(2, TimeUnit.SECONDS))
            assertFalse(mailbox.offer(frame(3)))
            assertEquals(listOf(3), synchronized(released) { released.toList() })
        } finally {
            finishRelease.countDown()
            worker.join(2000)
        }
        assertFalse(worker.isAlive)
        assertSame(pending, replacement.get())
        assertEquals(listOf(3, 1), synchronized(released) { released.toList() })
        replacement.get()!!.close()
        mailbox.close()
        assertEquals(listOf(3, 1, 2), synchronized(released) { released.toList() })
    }

    @Test fun offerAfterEmptyTransferCannotOverwriteAnUnreleasedCurrentOwner() {
        var released = 0
        val current = OwnedArgbFrame(1, 1, intArrayOf(1)) { released++ }
        val next = OwnedArgbFrame(1, 1, intArrayOf(2)) { released++ }
        val mailbox = OwnedFrameMailbox()
        assertNull(mailbox.takeReplacing { current.close() })
        assertEquals(0, released)
        mailbox.offer(next)
        assertSame(next, mailbox.takeReplacing { current.close() })
        assertEquals(1, released)
        next.close(); mailbox.close()
        assertEquals(2, released)
    }
}
