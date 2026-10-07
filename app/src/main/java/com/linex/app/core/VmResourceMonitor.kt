package com.linex.app.core

import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File

data class VmResources(val residentBytes: Long, val cpuPercent: Double?)

/** Samples the managed VM engine, including guest memory; this is not guest application RSS. */
class VmResourceMonitor(
    private val processId: Int,
    private val ticksPerSecond: Long = Os.sysconf(OsConstants._SC_CLK_TCK),
    private val pageSize: Long = Os.sysconf(OsConstants._SC_PAGESIZE),
    private val readStat: () -> String? = {
        if (processId > 0 && Os.stat("/proc/$processId").st_uid == Process.myUid())
            File("/proc/$processId/stat").takeIf { it.length() <= 16384 }?.readText()?.takeIf { it.length <= 16384 }
        else null
    },
    private val nowMillis: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private var startTicks: Long? = null
    private var previousCpu: Long? = null
    private var previousTime: Long? = null
    private var invalidated = false

    /** One CPU core =100%; unavailable or reused process identities return no sample. */
    fun sample(): VmResources? {
        if (invalidated || processId <= 0 || ticksPerSecond <= 0 || pageSize <= 0) return null
        val parsed = runCatching { readStat()?.let(::parse) }.getOrNull()
        if (parsed == null) {
            previousCpu = null
            previousTime = null
            return null
        }
        if (startTicks != null && startTicks != parsed.startTicks) {
            invalidated = true
            return null
        }
        startTicks = parsed.startTicks
        val now = nowMillis()
        val elapsed = previousTime?.let { now - it }
        val previous = previousCpu
        val cpu = if (previous != null && elapsed != null && elapsed > 0 && parsed.cpuTicks >= previous) {
            (parsed.cpuTicks - previous).toDouble() * 100_000.0 / ticksPerSecond / elapsed
        } else null
        previousCpu = parsed.cpuTicks
        previousTime = now
        return VmResources(parsed.residentBytes, cpu)
    }

    private data class Stat(val startTicks: Long, val cpuTicks: Long, val residentBytes: Long)

    private fun parse(text: String): Stat? {
        if (text.length > 16384 || text.substringBefore(' ').toIntOrNull() != processId) return null
        val end = text.lastIndexOf(')')
        if (end < 0) return null
        val fields = text.substring(end + 1).trim().split(Regex("\\s+"))
        val user = fields.getOrNull(11)?.toLongOrNull() ?: return null
        val system = fields.getOrNull(12)?.toLongOrNull() ?: return null
        val start = fields.getOrNull(19)?.toLongOrNull() ?: return null
        val pages = fields.getOrNull(21)?.toLongOrNull() ?: return null
        if (user < 0 || system < 0 || start < 0 || pages < 0) return null
        return runCatching { Stat(start, Math.addExact(user, system), Math.multiplyExact(pages, pageSize)) }.getOrNull()
    }
}
