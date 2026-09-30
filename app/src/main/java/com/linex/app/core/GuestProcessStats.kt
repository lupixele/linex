package com.linex.app.core

/** /proc/<pid>/stat may contain spaces and ')' in the parenthesized name. */
internal object GuestProcessStats {
    fun processGroup(stat: String): Int? {
        val end = stat.lastIndexOf(')')
        if (end < 0) return null
        return stat.substring(end + 1).trim().split(Regex("\\s+"), limit = 4)
            .getOrNull(2)?.toIntOrNull()
    }
}
