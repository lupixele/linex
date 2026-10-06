package com.linex.vm

import java.io.File
import java.io.IOException
import java.nio.file.Files

enum class VmHostObservationMethod(val wireValue: String) {
    THREAD_CHILDREN("thread_children"), PROC_PID_STAT("proc_pid_stat");

    companion object {
        fun fromWire(value: String?): VmHostObservationMethod =
            entries.singleOrNull { it.wireValue == value }
                ?: throw IllegalStateException("Unknown host process observation method")
    }
}

/** A bounded observation of this managed service's host threads, not emulated guest PIDs. */
data class VmHostProcessSnapshot(
    val threadCount: Int, val childCount: Int, val complete: Boolean,
    val method: VmHostObservationMethod = VmHostObservationMethod.THREAD_CHILDREN,
    val detail: String? = null,
)

internal class VmHostProcessStats(
    private val tasks: File = File("/proc/self/task"),
    private val maximumThreads: Int = 512,
    private val readChildren: (File) -> ByteArray = ::readBoundedChildren,
    private val processes: File? = if (tasks.path == "/proc/self/task") File("/proc") else null,
    private val maximumProcesses: Int = 4096,
    private val readStat: (File) -> ByteArray = ::readBoundedChildren,
    private val readDumpability: () -> Int? = { null },
) {
    fun sample(): VmHostProcessSnapshot {
        val threads = sampleThreadChildren()
        // PROC_CHILDREN defaults off in the Android common kernel. A separate
        // verified PID-stat observation can cover every host thread through
        // PPID=real_parent's TGID; an unreadable PID must still fail the proof.
        return if (processes != null && threads.detail == "thread_children_unavailable") {
            samplePidStats(threads.threadCount)
        } else threads
    }

    private fun sampleThreadChildren(): VmHostProcessSnapshot {
        val before = listTasks() ?: return VmHostProcessSnapshot(0, 0, false, detail = "task_directory_unreadable")
        if (before.isEmpty() || before.size > maximumThreads) {
            return VmHostProcessSnapshot(before.size, 0, false, detail = "task_count_invalid")
        }
        val children = mutableSetOf<Int>()
        var complete = true
        var detail: String? = null
        for ((id, directory) in before) {
            try {
                val bytes = readChildren(File(directory, "children"))
                if (bytes.size > MAXIMUM_CHILD_BYTES || bytes.any { it < 0 }) {
                    complete = false
                    detail = "thread_children_malformed"
                    continue
                }
                val text = bytes.toString(Charsets.US_ASCII).trim()
                if (text.isEmpty()) continue
                val pids = text.split(Regex("\\s+"))
                for (pid in pids) {
                    val value = pid.toIntOrNull()
                    if (value == null || value <= 0) {
                        complete = false; detail = "thread_children_malformed"
                    } else children += value
                }
            } catch (_: Exception) {
                // A disappeared thread is harmless, but an unreadable living
                // thread must never masquerade as a zero-child observation.
                val remaining = listTasks()
                if (remaining == null || remaining.containsKey(id)) {
                    complete = false
                    if (detail == null) detail = "thread_children_unavailable"
                }
            }
        }
        val after = listTasks()
        if (after == null || after.isEmpty() || after.size > maximumThreads) {
            complete = false
            detail = "task_set_changed"
        } else if (after.keys.any { it !in before }) {
            complete = false
            // PID-stat PPID covers every thread, including a new Binder thread.
            // The independent fallback can verify this observation when the
            // optional children files were unavailable on a living thread.
            if (detail != "thread_children_unavailable") detail = "task_set_changed"
        }
        return VmHostProcessSnapshot(after?.size ?: before.size, children.size, complete, detail = detail)
    }

    private fun samplePidStats(threadCount: Int): VmHostProcessSnapshot {
        val root = requireNotNull(processes)
        val identities = mutableMapOf<Int, ProcessIdentity>()
        val unreadable = mutableSetOf<Int>()
        val children = mutableSetOf<Int>()
        fun result(complete: Boolean, detail: String) = VmHostProcessSnapshot(
            threadCount, children.size, complete, VmHostObservationMethod.PROC_PID_STAT, detail)
        if (runCatching { readDumpability() }.getOrNull() != 1) {
            return result(false, "process_visibility_unverified")
        }
        val ownIdentity = try { parseStat(readStat(File(root, "self/stat"))) }
            catch (_: Exception) { return result(false, "self_stat_unreadable") }
        val before = listProcesses(root) ?: return result(false, "process_directory_unreadable_or_over_limit")
        if (ownIdentity.pid !in before) return result(false, "self_pid_not_visible")
        for ((pid, directory) in before) {
            try {
                val identity = parseStat(readStat(File(directory, "stat")))
                if (identity.pid != pid) return result(false, "process_pid_mismatch")
                identities[pid] = identity
                if (identity.parent == ownIdentity.pid) children += pid
            } catch (_: Exception) {
                unreadable += pid
            }
        }
        val after = listProcesses(root) ?: return result(false, "process_directory_unreadable_or_over_limit")
        if (after.keys.any { it !in before }) return result(false, "process_set_changed")
        if (unreadable.any { it in after }) return result(false, "process_stat_unreadable")
        if (identities[ownIdentity.pid] != ownIdentity || ownIdentity.pid !in after) {
            return result(false, "self_identity_changed")
        }
        for ((pid, directory) in after) {
            val identity = try { parseStat(readStat(File(directory, "stat"))) }
                catch (_: Exception) { return result(false, "process_stat_unreadable") }
            if (identity != identities[pid]) return result(false, "process_identity_changed")
        }
        if (runCatching { readDumpability() }.getOrNull() != 1) {
            return result(false, "process_visibility_changed")
        }
        // This is a bounded snapshot, not a claim that no short-lived process
        // can ever exist between samples. Unprivileged descendants inherit this
        // UID/dumpability, but a descendant could subsequently make itself
        // nondumpable. The separate audited helper-source/import guards remain
        // essential; this is observation, not a hostile-code security boundary.
        return result(true, "thread_children_unavailable")
    }

    private fun listProcesses(root: File): Map<Int, File>? = try {
        val found = mutableMapOf<Int, File>()
        Files.newDirectoryStream(root.toPath()).use { entries ->
            for (entry in entries) {
                val pid = entry.fileName.toString().toIntOrNull()?.takeIf { it > 0 } ?: continue
                found[pid] = entry.toFile()
                if (found.size > maximumProcesses) return null
            }
        }
        found.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }

    private data class ProcessIdentity(val pid: Int, val parent: Int, val start: Long)

    private fun parseStat(bytes: ByteArray): ProcessIdentity {
        require(bytes.size in 1..MAXIMUM_CHILD_BYTES)
        // comm can contain spaces, newlines, UTF-8 or closing parentheses.
        // The last ')' delimits it; only the structural fields are interpreted.
        val text = bytes.toString(Charsets.ISO_8859_1)
        val opening = text.indexOf(" (")
        val closing = text.lastIndexOf(')')
        require(opening > 0 && closing > opening)
        val pid = text.substring(0, opening).toIntOrNull()
        val fields = text.substring(closing + 1).trim().split(Regex("\\s+"))
        require(pid != null && pid > 0 && fields.size >= 20 && fields[0].length == 1 &&
            fields[0][0] in "RSDZTWtXxKPI")
        val parent = fields[1].toIntOrNull()
        val start = fields[19].toLongOrNull()
        require(parent != null && parent >= 0 && start != null && start >= 0)
        return ProcessIdentity(pid, parent, start)
    }

    private fun listTasks(): Map<Int, File>? = try {
        tasks.listFiles()?.mapNotNull { file ->
            file.name.toIntOrNull()?.takeIf { it > 0 }?.let { it to file }
        }?.toMap()
    } catch (_: SecurityException) {
        null
    }

    companion object {
        private const val MAXIMUM_CHILD_BYTES = 4096

        private fun readBoundedChildren(file: File): ByteArray = file.inputStream().use { input ->
            val buffer = ByteArray(MAXIMUM_CHILD_BYTES + 1)
            var used = 0
            while (used < buffer.size) {
                val count = input.read(buffer, used, buffer.size - used)
                if (count < 0) break
                if (count == 0) throw IOException("Host process observation made no progress")
                used += count
            }
            buffer.copyOf(used)
        }
    }
}
