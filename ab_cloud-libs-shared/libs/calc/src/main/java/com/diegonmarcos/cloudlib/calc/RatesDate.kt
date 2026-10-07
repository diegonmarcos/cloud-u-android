package com.diegonmarcos.cloudlib.calc

import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The date the cached rates THEMSELVES carry: the ECB daily file names it in `<Cube time='YYYY-MM-DD'>`.
 * libqalculate's own time can be the oldest of several sources (or a file's age), which showed a stale
 * "last update" after a good fetch; this is the date of the file on disk, so a fetch that replaced the
 * file always moves it.
 */
object RatesDate {
    private val ECB_TIME = Regex("""time\s*=\s*['"](\d{4}-\d{2}-\d{2})['"]""")

    /** The ECB file's date as midnight-UTC epoch seconds, 0 when [xml] holds none. */
    fun parseEcb(xml: String): Long =
        ECB_TIME.find(xml)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it).atStartOfDay().toEpochSecond(ZoneOffset.UTC) }.getOrNull() } ?: 0L

    /** The date of the ECB source among [sources] (url, file pairs) read from disk; [fallback] when there is none. */
    fun of(sources: List<Pair<String, String>>, fallback: Long): Long {
        val ecb = sources.firstOrNull { it.first.contains("ecb.europa.eu") } ?: return fallback
        val f = File(ecb.second)
        val t = if (f.isFile) runCatching { parseEcb(f.readText()) }.getOrDefault(0L) else 0L
        return if (t > 0) t else fallback
    }
}
