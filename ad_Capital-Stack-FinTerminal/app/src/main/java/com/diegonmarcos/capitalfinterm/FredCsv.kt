package com.diegonmarcos.capitalfinterm

/** One dated observation. */
data class Obs(val date: String, val value: Double)

/**
 * The CSV the public FRED graph endpoint serves: a header (the date column, then one column per series id) and one row per date,
 * a missing value being "." or empty. Anything else (an HTML error page, a zip) parses to nothing, so a bad answer is "no data".
 */
object FredCsv {
    private val DATE_HEADERS = setOf("observation_date", "date")

    /** series id to its observations in file order (missing values dropped). Empty when [csv] is not a FRED CSV. */
    fun parse(csv: String): Map<String, List<Obs>> {
        val lines = csv.trimStart('﻿').lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return emptyMap()
        val head = lines[0].split(',').map { it.trim() }
        if (head.size < 2 || head[0].lowercase() !in DATE_HEADERS) return emptyMap()
        val ids = head.drop(1)
        val out = LinkedHashMap<String, MutableList<Obs>>()
        ids.forEach { out[it] = ArrayList() }
        for (line in lines.drop(1)) {
            val cells = line.split(',')
            if (cells.size < 2) continue
            val date = cells[0].trim()
            for (i in ids.indices) {
                val v = cells.getOrNull(i + 1)?.trim().orEmpty()
                if (v.isEmpty() || v == ".") continue
                val d = v.toDoubleOrNull() ?: continue
                if (d.isFinite()) out.getValue(ids[i]).add(Obs(date, d))
            }
        }
        return out
    }
}
