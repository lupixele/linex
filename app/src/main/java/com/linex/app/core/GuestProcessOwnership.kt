package com.linex.app.core

/** Kernel identity, without command lines, environment variables, or guest paths. */
internal data class GuestProcessIdentity(
    val pid: Int, val startTicks: Long, val uid: Int, val tracerPid: Int, val group: Int
) {
    companion object {
        fun read(stat: String, status: String): GuestProcessIdentity? {
            val end = stat.lastIndexOf(')')
            if (end < 0) return null
            val pid = stat.substringBefore(' ').toIntOrNull() ?: return null
            val fields = stat.substring(end + 1).trim().split(Regex("\\s+"))
            val group = fields.getOrNull(2)?.toIntOrNull() ?: return null
            val start = fields.getOrNull(19)?.toLongOrNull() ?: return null
            val values = status.lineSequence().associate { it.substringBefore(':') to it.substringAfter(':', "").trim() }
            val uid = values["Uid"]?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull() ?: return null
            val tracer = values["TracerPid"]?.toIntOrNull() ?: return null
            if (pid <= 0 || start < 0 || uid < 0 || tracer < 0) return null
            return GuestProcessIdentity(pid, start, uid, tracer, group)
        }
    }
}

/** Remember positively attributed tracees after their tracer is killed. */
internal class GuestProcessOwnership(
    private val appUid: Int,
    private val launcher: GuestProcessIdentity,
    capacity: Int = 4096
) {
    private val limit = capacity.coerceIn(1, 4096)
    private val owned = mutableMapOf(launcher.pid to launcher.startTicks)
    val trackedCount: Int get() = owned.size

    fun observe(rows: List<GuestProcessIdentity>) {
        val visible = rows.associateBy { it.pid }
        // Positive evidence of PID reuse or changed ownership invalidates an old
        // entry even after the tracer dies. Keep the launcher identity immutable.
        owned.entries.removeAll { (pid, start) ->
            pid != launcher.pid && visible[pid]?.let { it.startTicks != start || it.uid != appUid } == true
        }
        // Do not let a reused PID adopt children from another session.
        if (rows.none { it.pid == launcher.pid && it.startTicks == launcher.startTicks && it.uid == appUid }) return
        rows.filter { it.pid != launcher.pid && it.uid == appUid && it.tracerPid == launcher.pid }
            .forEach { child ->
                if (child.pid !in owned && owned.size >= limit) {
                    // Under capacity pressure forget inaccessible history first.
                    // Visible matching children, including escaped daemons, win
                    // over newly discovered processes. Omitted entries can no
                    // longer be cleaned up unless the live tracer reattributes them.
                    val missing = owned.keys.firstOrNull { it != launcher.pid && it !in visible }
                    if (missing != null) owned.remove(missing)
                }
                if (child.pid in owned || owned.size < limit) owned[child.pid] = child.startTicks
            }
    }

    fun targets(rows: List<GuestProcessIdentity>): List<Int> = rows
        .filter { it.uid == appUid && owned[it.pid] == it.startTicks }.map { it.pid }
}
