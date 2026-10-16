package com.diegonmarcos.cloudcalc.sheet

/** RFC 4180 text tables: CSV (comma) and the clipboard's TSV (tab). */
object Csv {
    fun parse(text: String, delimiter: Char = ','): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var any = false
        var i = 0
        val t = text.removePrefix("﻿")
        while (i < t.length) {
            val c = t[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < t.length && t[i + 1] == '"') { cell.append('"'); i++ } else quoted = false
                } else cell.append(c)
            } else when {
                c == '"' && cell.isEmpty() -> { quoted = true; any = true }
                c == delimiter -> { row.add(cell.toString()); cell.setLength(0); any = true }
                c == '\r' -> { }
                c == '\n' -> { row.add(cell.toString()); cell.setLength(0); rows.add(row); row = ArrayList(); any = false }
                else -> { cell.append(c); any = true }
            }
            i++
        }
        if (any || cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows
    }

    fun write(rows: List<List<String>>, delimiter: Char = ','): String {
        val sb = StringBuilder()
        for (r in rows) {
            sb.append(r.joinToString(delimiter.toString()) { quote(it, delimiter) }).append('\n')
        }
        return sb.toString()
    }

    private fun quote(s: String, d: Char): String =
        if (s.any { it == d || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
