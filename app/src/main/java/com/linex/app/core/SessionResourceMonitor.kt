package com.linex.app.core

import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File

/** Partial process-group view: Android hides some /proc entries and children may change groups. */
data class SessionResources(val visibleProcesses: Int, val visibleRssBytes: Long, val cpuPercent: Double?)

/** Call on an IO dispatcher only while the overlay is visible; no background polling is owned here. */
class SessionResourceMonitor(
    private val ticksPerSecond: Long = Os.sysconf(OsConstants._SC_CLK_TCK),
    private val pageSize: Long = Os.sysconf(OsConstants._SC_PAGESIZE),
    private val readStats: () -> List<String> = {
        File("/proc").listFiles().orEmpty().asSequence()
            .filter { it.name.toIntOrNull() != null }
            .mapNotNull { runCatching { File(it, "stat").readText() }.getOrNull() }.toList()
    },
    private val nowMillis: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private var previous = emptyMap<Pair<Int, Long>, Long>()
    private var previousTime: Long? = null
    private var previousGroup: Int? = null

    /** CPU uses one core = 100%; RSS includes shared pages counted once per visible process. */
    fun sample(processGroup: Int): SessionResources? {
        if (processGroup <= 0 || ticksPerSecond <= 0 || pageSize <= 0) return null
        val now = nowMillis()
        val rows = readStats().mapNotNull(::parse).filter { it.group == processGroup }
        val current = rows.associate { (it.pid to it.startTicks) to it.cpuTicks }
        val elapsed = previousTime?.let { now - it } ?: 0L
        val common = current.keys.intersect(previous.keys)
        val cpu = if (previousGroup == processGroup && elapsed > 0 && common.isNotEmpty()) {
            common.sumOf { (current.getValue(it) - previous.getValue(it)).coerceAtLeast(0) }
                .toDouble() * 100_000.0 / ticksPerSecond / elapsed
        } else null
        previous = current
        previousTime = now
        previousGroup = processGroup
        if (rows.isEmpty()) return null
        return SessionResources(rows.size, rows.sumOf { it.rssPages * pageSize }, cpu)
    }

    /** Reset after hiding the overlay so the next CPU sample does not span a background interval. */
    fun reset() {
        previous = emptyMap()
        previousTime = null
        previousGroup = null
    }

    private data class Stat(val pid: Int, val group: Int, val cpuTicks: Long, val startTicks: Long, val rssPages: Long)

    private fun parse(text: String): Stat? {
        val end = text.lastIndexOf(')')
        if (end < 0) return null
        val pid = text.substringBefore(' ').toIntOrNull() ?: return null
        val fields = text.substring(end + 1).trim().split(Regex("\\s+"))
        val group = fields.getOrNull(2)?.toIntOrNull() ?: return null
        val user = fields.getOrNull(11)?.toLongOrNull() ?: return null
        val system = fields.getOrNull(12)?.toLongOrNull() ?: return null
        val start = fields.getOrNull(19)?.toLongOrNull() ?: return null
        val rss = fields.getOrNull(21)?.toLongOrNull() ?: return null
        if (user < 0 || system < 0 || rss < 0) return null
        return Stat(pid, group, user + system, start, rss)
    }
}
