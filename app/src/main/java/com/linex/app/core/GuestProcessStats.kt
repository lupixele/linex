package com.linex.app.core

/** /proc/<pid>/stat may contain spaces and ')' in the parenthesized name. */
internal object GuestProcessStats {
    private fun safeName(name: String): String = name.take(32).replace(Regex("[^A-Za-z0-9._:+-]"), "_")

    /** Only the kernel's short comm name, never command arguments or environment. */
    fun commandName(stat: String): String? {
        val start = stat.indexOf('(')
        val end = stat.lastIndexOf(')')
        if (start < 0 || end <= start + 1) return null
        return safeName(stat.substring(start + 1, end))
    }

    fun summarizeNames(names: List<String>, limit: Int = 10): String {
        require(limit in 1..20)
        val counts = names.groupingBy(::safeName).eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        val kept = counts.take(limit).map { "${it.key}=${it.value}" }
        val remaining = counts.drop(limit).sumOf { it.value }
        return (kept + if (remaining > 0) listOf("other=$remaining") else emptyList()).joinToString(", ")
    }

    fun processGroup(stat: String): Int? {
        val end = stat.lastIndexOf(')')
        if (end < 0) return null
        return stat.substring(end + 1).trim().split(Regex("\\s+"), limit = 4)
            .getOrNull(2)?.toIntOrNull()
    }
}
