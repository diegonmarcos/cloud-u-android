package com.diegonmarcos.cloudlib.diskscan

/**
 * Huge files: every file AT OR ABOVE [thresholdBytes], largest first (path breaks a tie so the
 * order is stable between two scans), at most [top] of them. The threshold is inclusive on
 * purpose: "files of 100 MB and more" must list a file of exactly 100 MB.
 */
object HugeFiles {
    const val DEFAULT_THRESHOLD = 100L * 1024 * 1024
    const val DEFAULT_TOP = 50

    fun find(entries: List<Entry>, thresholdBytes: Long = DEFAULT_THRESHOLD, top: Int = DEFAULT_TOP): List<Entry> {
        require(thresholdBytes >= 0) { "threshold must not be negative" }
        if (top <= 0) return emptyList()
        return entries.filter { it.bytes >= thresholdBytes }
            .sortedWith(compareByDescending<Entry> { it.bytes }.thenBy { it.path })
            .take(top)
    }

    /** Parse a threshold written as bytes or with a K/M/G suffix (binary units); null when unreadable. */
    fun parseThreshold(text: String?): Long? {
        val t = text?.trim()?.uppercase()?.removeSuffix("B") ?: return null
        if (t.isEmpty()) return null
        val mul = when (t.last()) { 'K' -> 1024L; 'M' -> 1024L * 1024; 'G' -> 1024L * 1024 * 1024; else -> 1L }
        val num = if (mul == 1L) t else t.dropLast(1)
        val n = num.toLongOrNull() ?: return null
        return if (n < 0) null else n * mul
    }
}
