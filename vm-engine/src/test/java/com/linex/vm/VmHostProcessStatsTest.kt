package com.linex.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VmHostProcessStatsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun task(root: File, id: Int, children: String): File =
        File(root, id.toString()).apply { mkdir(); File(this, "children").writeText(children) }

    @Test fun reportsChildrenAcrossEveryHostThread() {
        val root = temporary.newFolder()
        task(root, 100, "200 201\n")
        task(root, 101, "202 ")
        val stats = VmHostProcessStats(root).sample()
        assertTrue(stats.complete)
        assertEquals(2, stats.threadCount)
        assertEquals(3, stats.childCount)
    }

    @Test fun doesNotClaimZeroWhenTaskRootCannotBeRead() {
        val stats = VmHostProcessStats(File(temporary.root, "missing")).sample()
        assertFalse(stats.complete)
    }

    @Test fun missingChildrenFileOnLiveThreadIsIncomplete() {
        val root = temporary.newFolder()
        File(root, "100").mkdir()
        assertFalse(VmHostProcessStats(root).sample().complete)
    }

    @Test fun rejectsOversizedAndMalformedChildLists() {
        val root = temporary.newFolder()
        val task = task(root, 100, "1 ".repeat(3000))
        assertFalse(VmHostProcessStats(root).sample().complete)
        File(task, "children").writeText("200 malformed 201")
        assertFalse(VmHostProcessStats(root).sample().complete)
    }

    @Test fun rejectsObservationsAboveThreadLimit() {
        val root = temporary.newFolder()
        task(root, 100, "")
        task(root, 101, "")
        assertFalse(VmHostProcessStats(root, maximumThreads = 1).sample().complete)
    }

    @Test fun confirmsDisappearedThreadRatherThanAssumingPermissionFailure() {
        val root = temporary.newFolder()
        val first = task(root, 100, "")
        task(root, 101, "")
        val stats = VmHostProcessStats(root, readChildren = { file ->
            if (file.parentFile == first) {
                file.delete(); first.delete()
                throw java.io.IOException("Thread exited")
            }
            file.readBytes()
        }).sample()
        assertTrue(stats.complete)
        assertEquals(1, stats.threadCount)
    }

    @Test fun newThreadDuringObservationMakesSnapshotIncomplete() {
        val root = temporary.newFolder()
        task(root, 100, "")
        val stats = VmHostProcessStats(root, readChildren = { file ->
            task(root, 101, "")
            file.readBytes()
        }).sample()
        assertFalse(stats.complete)
    }
}
