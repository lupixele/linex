package com.linex.app.core

import org.junit.Assert.*
import org.junit.Test

class VmResourceMonitorTest {
    private fun stat(pid: Int = 70, ticks: Long = 100, start: Long = 1, pages: Long = 10, system: Long = 0): String {
        val fields = MutableList(22) { "0" }
        fields[0] = "S"; fields[11] = ticks.toString(); fields[12] = system.toString()
        fields[19] = start.toString(); fields[21] = pages.toString()
        return "$pid (VM (engine)) ${fields.joinToString(" ")}"
    }

    @Test fun measuresEngineResidentMemoryAndCpuAcrossCores() {
        var time = 1000L
        var row: String? = stat()
        val monitor = VmResourceMonitor(70, 100, 4096, { row }, { time })
        assertEquals(40960L, monitor.sample()!!.residentBytes)
        time += 1000; row = stat(ticks = 300)
        assertEquals(200.0, monitor.sample()!!.cpuPercent!!, 0.01)
    }

    @Test fun pidReusePermanentlyInvalidatesTheSessionMonitor() {
        var row: String? = stat()
        val monitor = VmResourceMonitor(70, 100, 4096, { row }, { 1000L })
        monitor.sample()
        row = stat(start = 2)
        assertNull(monitor.sample())
        row = stat(start = 1)
        assertNull(monitor.sample())
    }

    @Test fun hiddenOrMalformedStatsDoNotInventMetricsOrCrossMissingCpuIntervals() {
        var row: String? = stat()
        var time = 1000L
        val monitor = VmResourceMonitor(70, 100, 4096, { row }, { time })
        monitor.sample()
        for (invalid in listOf(null, "denied", stat(pid = 71), stat(pages = -1),
                stat(ticks = Long.MAX_VALUE, system = 1), stat(pages = Long.MAX_VALUE))) {
            row = invalid
            assertNull(monitor.sample())
        }
        time += 1000; row = stat(ticks = 200)
        assertNull(monitor.sample()!!.cpuPercent)
    }

    @Test fun counterResetAndReversedTimeRequireFreshBaseline() {
        var row: String? = stat()
        var time = 1000L
        val monitor = VmResourceMonitor(70, 100, 4096, { row }, { time })
        monitor.sample()
        time = 900L; row = stat(ticks = 150)
        assertNull(monitor.sample()!!.cpuPercent)
        time = 1900L; row = stat(ticks = 100)
        assertNull(monitor.sample()!!.cpuPercent)
    }
}
