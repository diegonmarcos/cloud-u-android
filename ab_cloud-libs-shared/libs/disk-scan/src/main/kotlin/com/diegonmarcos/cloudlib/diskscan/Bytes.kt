package com.diegonmarcos.cloudlib.diskscan

/** Binary-unit byte counts for people: 1023 B, 1.0 KB, 1.5 MB, 2.0 GB. */
object Bytes {
    private val UNITS = listOf("KB", "MB", "GB", "TB")

    fun human(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        var v = bytes.toDouble() / 1024
        var i = 0
        while (v >= 1024 && i < UNITS.size - 1) { v /= 1024; i++ }
        return String.format(java.util.Locale.ROOT, "%.1f %s", v, UNITS[i])
    }
}
