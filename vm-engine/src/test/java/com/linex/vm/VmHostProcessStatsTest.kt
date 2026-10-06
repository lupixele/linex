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

    @Test fun usesVerifiedPidStatsWhenKernelHasNoThreadChildrenFiles() {
        val (tasks, processes) = withoutChildrenFiles()
        process(processes, 200, 100)
        process(processes, 201, 100, comm = "name ) with spaces")
        process(processes, 300, 77)
        val stats = VmHostProcessStats(tasks, processes = processes, readDumpability = { 1 }).sample()
        assertTrue(stats.complete)
        assertEquals(VmHostObservationMethod.PROC_PID_STAT, stats.method)
        assertEquals(2, stats.childCount)
        assertEquals("thread_children_unavailable", stats.detail)
    }

    @Test fun fallbackDoesNotConvertUnreadableVisibleProcessIntoZero() {
        val (tasks, processes) = withoutChildrenFiles()
        File(processes, "200").mkdir()
        val stats = VmHostProcessStats(tasks, processes = processes, readDumpability = { 1 }).sample()
        assertFalse(stats.complete)
        assertEquals("process_stat_unreadable", stats.detail)
    }

    @Test fun rejectsMalformedOversizedAndMismatchedPidStats() {
        val (tasks, processes) = withoutChildrenFiles()
        val child = process(processes, 200, 100)
        val stats = VmHostProcessStats(tasks, processes = processes, readDumpability = { 1 })
        for (invalid in listOf("200 (bad) S invalid", "x".repeat(5000), stat(201, 100), stat(200, 100).replace(" S ", " ? "))) {
            File(child, "stat").writeText(invalid)
            assertFalse(stats.sample().complete)
        }
    }

    @Test fun processFallbackIsBounded() {
        val (tasks, processes) = withoutChildrenFiles()
        process(processes, 200, 100)
        assertFalse(VmHostProcessStats(tasks, processes = processes, maximumProcesses = 1, readDumpability = { 1 }).sample().complete)
    }

    @Test fun newProcessDuringFallbackIsIncomplete() {
        val (tasks, processes) = withoutChildrenFiles()
        val stats = VmHostProcessStats(tasks, processes = processes, readDumpability = { 1 }, readStat = { file ->
            val bytes = file.readBytes()
            if (file.parentFile?.name == "100") process(processes, 200, 100)
            bytes
        }).sample()
        assertFalse(stats.complete)
        assertEquals("process_set_changed", stats.detail)
    }

    @Test fun reusedPidDuringFallbackIsIncomplete() {
        val (tasks, processes) = withoutChildrenFiles()
        process(processes, 200, 77)
        var childReads = 0
        val stats = VmHostProcessStats(tasks, processes = processes, readDumpability = { 1 }, readStat = { file ->
            if (file.parentFile?.name == "200" && ++childReads == 2) {
                file.writeText(stat(200, 100, start = 2))
            }
            file.readBytes()
        }).sample()
        assertFalse(stats.complete)
        assertEquals("process_identity_changed", stats.detail)
    }

    @Test fun unreadableDisappearedProcessIsConfirmedAbsent() {
        val (tasks, processes) = withoutChildrenFiles()
        val transient = File(processes, "200").apply { mkdir() }
        val stats = VmHostProcessStats(tasks, processes = processes, readDumpability = { 1 }, readStat = { file ->
            if (file.parentFile == transient) {
                transient.delete()
                throw java.io.IOException("Exited")
            }
            file.readBytes()
        }).sample()
        assertTrue(stats.complete)
        assertEquals(0, stats.childCount)
    }

    @Test fun unknownOrNondumpableVisibilityCannotProveZero() {
        val (tasks, processes) = withoutChildrenFiles()
        assertFalse(VmHostProcessStats(tasks, processes = processes).sample().complete)
        for (dumpability in listOf(null, 0, 2)) {
            val stats = VmHostProcessStats(tasks, processes = processes, readDumpability = { dumpability }).sample()
            assertFalse(stats.complete)
            assertEquals("process_visibility_unverified", stats.detail)
        }
    }

    @Test fun visibilityChangedDuringFallbackIsIncomplete() {
        val (tasks, processes) = withoutChildrenFiles()
        var calls = 0
        val stats = VmHostProcessStats(tasks, processes = processes, readDumpability = { if (++calls == 1) 1 else 0 }).sample()
        assertFalse(stats.complete)
        assertEquals("process_visibility_changed", stats.detail)
    }

    private fun withoutChildrenFiles(): Pair<File, File> {
        val root = temporary.newFolder()
        val processes = File(root, "proc").apply { mkdir() }
        val tasks = File(root, "task").apply { mkdir() }
        File(tasks, "100").mkdir()
        File(tasks, "101").mkdir()
        process(processes, 100, 77)
        File(processes, "self").apply { mkdir(); File(this, "stat").writeText(stat(100, 77)) }
        return tasks to processes
    }

    private fun process(root: File, pid: Int, parent: Int, comm: String = "worker"): File =
        File(root, pid.toString()).apply { mkdir(); File(this, "stat").writeText(stat(pid, parent, comm)) }

    private fun stat(pid: Int, parent: Int, comm: String = "worker", start: Int = 1): String =
        "$pid ($comm) S $parent " + "0 ".repeat(17) + "$start\n"
}
