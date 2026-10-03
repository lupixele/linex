package com.linex.app.core

import org.junit.Assert.*
import org.junit.Test

class GuestProcessOwnershipTest {
    private fun row(pid: Int, start: Long = pid.toLong(), uid: Int = 1000, tracer: Int = 100,
                    group: Int = 100) = GuestProcessIdentity(pid, start, uid, tracer, group)

    @Test fun escapedDaemonRemainsOwnedAfterTracerDies() {
        val owner = GuestProcessOwnership(1000, row(100, tracer = 0))
        owner.observe(listOf(row(100, tracer = 0), row(200, group = 200)))
        assertEquals(listOf(200), owner.targets(listOf(row(200, tracer = 0, group = 200))))
    }

    @Test fun pidReuseDoesNotKillNewProcess() {
        val owner = GuestProcessOwnership(1000, row(100, tracer = 0))
        owner.observe(listOf(row(100, tracer = 0), row(200)))
        assertTrue(owner.targets(listOf(row(200, start = 999, tracer = 0))).isEmpty())
    }

    @Test fun unrelatedUidAndAndroidProcessesNeverBecomeTargets() {
        val owner = GuestProcessOwnership(1000, row(100, tracer = 0))
        val rows = listOf(row(100, tracer = 0), row(200, uid = 2000), row(300, tracer = 0, group = 300))
        owner.observe(rows)
        assertEquals(listOf(100), owner.targets(rows))
    }

    @Test fun reusedLauncherCannotAdoptNewChildren() {
        val owner = GuestProcessOwnership(1000, row(100, tracer = 0))
        val rows = listOf(row(100, start = 999, tracer = 0), row(200))
        owner.observe(rows)
        assertTrue(owner.targets(rows).isEmpty())
    }

    @Test fun unreadableAndChangedUidRemainUntargeted() {
        val owner = GuestProcessOwnership(1000, row(100, tracer = 0))
        owner.observe(listOf(row(100, tracer = 0), row(200)))
        assertTrue(owner.targets(emptyList()).isEmpty())
        assertTrue(owner.targets(listOf(row(200, uid = 2000))).isEmpty())
    }

    @Test fun parserHandlesSpacesAndParenthesesWithoutReadingArguments() {
        val fields = MutableList(20) { "0" }
        fields[0] = "S"; fields[2] = "100"; fields[19] = "12345"
        val parsed = GuestProcessIdentity.read("200 (Firefox (child)) ${fields.joinToString(" ")}",
            "Uid:\t1000\t1000\t1000\t1000\nTracerPid:\t100\n")
        assertEquals(row(200, start = 12345), parsed)
        assertNull(GuestProcessIdentity.read("200 (x) S", "Uid:\t1000\n"))
    }

    @Test fun positivelyReusedAndChangedUidEntriesArePruned() {
        val owner = GuestProcessOwnership(1000, row(100, tracer = 0))
        owner.observe(listOf(row(100, tracer = 0), row(200), row(300)))
        owner.observe(listOf(row(100, tracer = 0), row(200, start = 999, tracer = 0), row(300, uid = 2000)))
        assertEquals(1, owner.trackedCount)
    }

    @Test fun longRunningChurnKeepsRegistryBoundedAndLauncherRetained() {
        val owner = GuestProcessOwnership(1000, row(100, tracer = 0), capacity = 4)
        for (pid in 200..1000) {
            owner.observe(listOf(row(100, tracer = 0), row(pid)))
            assertTrue(owner.trackedCount <= 4)
        }
        assertEquals(listOf(100, 1000), owner.targets(listOf(row(100, tracer = 0), row(1000))))
    }

    @Test fun capacityPressurePreservesLiveEscapedChildBeforeUnreadableHistory() {
        val owner = GuestProcessOwnership(1000, row(100, tracer = 0), capacity = 3)
        owner.observe(listOf(row(100, tracer = 0), row(200, group = 200), row(300)))
        owner.observe(listOf(row(100, tracer = 0), row(200, tracer = 0, group = 200), row(400)))
        assertEquals(3, owner.trackedCount)
        assertEquals(listOf(200, 400), owner.targets(listOf(row(200, tracer = 0, group = 200), row(400), row(300))))
    }

    @Test fun fullRegistryDoesNotEvictVisibleOwnedChildrenToAdoptNewOnes() {
        val owner = GuestProcessOwnership(1000, row(100, tracer = 0), capacity = 2)
        val rows = listOf(row(100, tracer = 0), row(200, group = 200), row(300))
        owner.observe(rows)
        assertEquals(2, owner.trackedCount)
        assertEquals(listOf(100, 200), owner.targets(rows))
    }
}
