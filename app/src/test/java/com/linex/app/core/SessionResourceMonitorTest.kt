package com.linex.app.core

import org.junit.Assert.*
import org.junit.Test

class SessionResourceMonitorTest {
    private fun stat(pid: Int, group: Int, ticks: Long, start: Long = 1, rss: Long = 10): String {
        val fields = MutableList(22) { "0" }
        fields[0] = "S"; fields[2] = "$group"; fields[11] = "$ticks"
        fields[19] = "$start"; fields[21] = "$rss"
        return "$pid (browser (worker)) ${fields.joinToString(" ")}"
    }

    @Test fun samplesOnlyVisibleGroupAndCalculatesCpuDelta() {
        var time = 1000L
        var rows = listOf(stat(1, 50, 10), stat(2, 99, 999))
        val monitor = SessionResourceMonitor(100, 4096, { rows }, { time })
        val first = monitor.sample(50)!!
        assertEquals(1, first.visibleProcesses)
        assertEquals(40960L, first.visibleRssBytes)
        assertNull(first.cpuPercent)
        time += 1000
        rows = listOf(stat(1, 50, 60))
        assertEquals(50.0, monitor.sample(50)!!.cpuPercent!!, 0.01)
    }

    @Test fun pidReuseAndHiddenProcessesDoNotInventCpuUsage() {
        var time = 1000L
        var rows = listOf(stat(1, 50, 100))
        val monitor = SessionResourceMonitor(100, 4096, { rows }, { time })
        monitor.sample(50)
        time += 1000
        rows = listOf(stat(1, 50, 1000, start = 2), "denied", "1 (bad) S")
        assertNull(monitor.sample(50)!!.cpuPercent)
        rows = emptyList()
        assertNull(monitor.sample(50))
    }

    @Test fun resetAndGroupChangesRequireFreshBaseline() {
        var time = 1000L
        val monitor = SessionResourceMonitor(100, 4096, { listOf(stat(1, 50, time)) }, { time })
        monitor.sample(50)
        time += 1000
        monitor.reset()
        assertNull(monitor.sample(50)!!.cpuPercent)
        assertNull(monitor.sample(99))
        time += 1000
        assertNull(monitor.sample(50)!!.cpuPercent)
    }
}
