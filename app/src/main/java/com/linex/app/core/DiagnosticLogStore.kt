package com.linex.app.core

import java.io.File
import java.security.MessageDigest
import java.util.Base64

data class LogEntry(val timestamp: String, val instanceId: String?, val tag: String, val message: String) {
    override fun toString(): String = "[$timestamp] " +
        (instanceId?.let { "[Inst:$it] " } ?: "") + "[$tag] $message"
}

/** Only called by the logger's serial IO worker. One bounded journal per instance. */
internal class DiagnosticLogStore(
    private val directory: File,
    private val maxEntries: Int = 1000,
    private val maxBytes: Long = 512 * 1024
) {
    init { check(directory.isDirectory || directory.mkdirs()) { "Cannot create diagnostics directory" } }
    private fun files() = directory.listFiles().orEmpty().filter {
        it.name == "linex_global.log" || (it.name.startsWith("instance_") && it.extension == "log")
    }
    fun load(): List<LogEntry> = files().flatMap { file ->
        readTail(file).mapNotNull(::decode).filter {
            // Old versions duplicated instance output in the global journal.
            file.name != "linex_global.log" || it.instanceId == null
        }
    }.sortedBy { it.timestamp }

    fun append(entry: LogEntry) {
        val file = fileFor(entry.instanceId)
        file.appendText(encode(entry) + "\n")
        if (file.length() > maxBytes) {
            val tail = readTail(file).toMutableList()
            var bytes = tail.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() + 1 }
            while (tail.size > 1 && bytes > maxBytes / 2) {
                bytes -= tail.removeAt(0).toByteArray(Charsets.UTF_8).size + 1
            }
            file.writeText(tail.joinToString("\n", postfix = "\n"))
        }
    }
    fun clear(instanceId: String?) {
        val targets = if (instanceId == null) files() else listOf(fileFor(instanceId))
        targets.forEach { check(!it.exists() || it.delete()) { "Cannot clear ${it.name}" } }
    }
    private fun fileFor(id: String?): File {
        if (id == null) return File(directory, "linex_global.log")
        val safeId = if (id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) id else
            MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "instance_$safeId.log")
    }
    private fun readTail(file: File): List<String> {
        val tail = ArrayDeque<String>()
        file.useLines { lines -> lines.forEach { line ->
            tail.addLast(line)
            if (tail.size > maxEntries) tail.removeFirst()
        } }
        return tail.toList()
    }
    private fun encode(entry: LogEntry): String = "v1\t" +
        listOf(entry.timestamp, entry.instanceId.orEmpty(), entry.tag, entry.message)
            .joinToString("\t") { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }
    private fun decode(line: String): LogEntry? {
        if (line.startsWith("v1\t")) return runCatching {
            val parts = line.split('\t').drop(1).map { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }
            require(parts.size == 4)
            LogEntry(parts[0], parts[1].ifEmpty { null }, parts[2], parts[3])
        }.getOrNull()
        val match = Regex("^\\[([^]]*)] (?:\\[Inst:([^]]+)] )?\\[([^]]+)] (.*)$").matchEntire(line) ?: return null
        return LogEntry(match.groupValues[1], match.groupValues[2].ifEmpty { null }, match.groupValues[3], match.groupValues[4])
    }
}
