package com.linex.vm

import java.io.File
import java.io.IOException

/** A bounded observation of this managed service's host threads, not emulated guest PIDs. */
data class VmHostProcessSnapshot(val threadCount: Int, val childCount: Int, val complete: Boolean)

internal class VmHostProcessStats(
    private val tasks: File = File("/proc/self/task"),
    private val maximumThreads: Int = 512,
    private val readChildren: (File) -> ByteArray = ::readBoundedChildren,
) {
    fun sample(): VmHostProcessSnapshot {
        val before = listTasks() ?: return VmHostProcessSnapshot(0, 0, false)
        if (before.isEmpty() || before.size > maximumThreads) {
            return VmHostProcessSnapshot(before.size, 0, false)
        }
        val children = mutableSetOf<Int>()
        var complete = true
        for ((id, directory) in before) {
            try {
                val bytes = readChildren(File(directory, "children"))
                if (bytes.size > MAXIMUM_CHILD_BYTES || bytes.any { it < 0 }) {
                    complete = false
                    continue
                }
                val text = bytes.toString(Charsets.US_ASCII).trim()
                if (text.isEmpty()) continue
                val pids = text.split(Regex("\\s+"))
                for (pid in pids) {
                    val value = pid.toIntOrNull()
                    if (value == null || value <= 0) complete = false else children += value
                }
            } catch (_: Exception) {
                // A disappeared thread is harmless, but an unreadable living
                // thread must never masquerade as a zero-child observation.
                val remaining = listTasks()
                if (remaining == null || remaining.containsKey(id)) complete = false
            }
        }
        val after = listTasks()
        if (after == null || after.isEmpty() || after.size > maximumThreads || after.keys.any { it !in before }) complete = false
        return VmHostProcessSnapshot(after?.size ?: before.size, children.size, complete)
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
                if (count == 0) throw IOException("Host child-PID observation made no progress")
                used += count
            }
            buffer.copyOf(used)
        }
    }
}
