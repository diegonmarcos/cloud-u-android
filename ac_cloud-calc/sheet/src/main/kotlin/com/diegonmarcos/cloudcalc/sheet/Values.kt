package com.diegonmarcos.cloudcalc.sheet

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** What a cell evaluates to. */
sealed class Value {
    data class Num(val v: Double) : Value()
    data class Str(val s: String) : Value()
    data class Bool(val b: Boolean) : Value()
    data class Err(val code: String) : Value()
    object Blank : Value() { override fun toString() = "Blank" }

    companion object {
        const val DIV0 = "#DIV/0!"
        const val REF = "#REF!"
        const val NAME = "#NAME?"
        const val VALUE = "#VALUE!"
        const val CIRC = "#CIRC!"
        val ERRORS = listOf(DIV0, REF, NAME, VALUE, CIRC)
        fun err(code: String) = Err(code)
    }
}

/** A1-style addressing helpers. Columns and rows are 0-based inside, 1-based (rows) and lettered (columns) outside. */
object A1 {
    const val MAX_COLS = 16384
    const val MAX_ROWS = 1048576

    fun colName(col: Int): String {
        var n = col + 1
        val sb = StringBuilder()
        while (n > 0) {
            val r = (n - 1) % 26
            sb.append('A' + r)
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    /** "AB" -> 27; null when [s] is not letters. */
    fun parseCol(s: String): Int? {
        if (s.isEmpty() || !s.all { it in 'A'..'Z' || it in 'a'..'z' }) return null
        var n = 0
        for (ch in s.uppercase()) n = n * 26 + (ch - 'A' + 1)
        return n - 1
    }

    fun cell(col: Int, row: Int) = colName(col) + (row + 1)

    /** "B7" / "$B$7" -> (1, 6); null when it is not a single cell. */
    fun parseCell(text: String): Pair<Int, Int>? {
        val m = Regex("^\\$?([A-Za-z]+)\\$?([0-9]+)$").matchEntire(text.trim()) ?: return null
        val c = parseCol(m.groupValues[1]) ?: return null
        val r = m.groupValues[2].toIntOrNull()?.minus(1) ?: return null
        return if (c in 0 until MAX_COLS && r in 0 until MAX_ROWS) c to r else null
    }

    /** "A1:C5" or "B2" -> a normalised [Area]; null otherwise. */
    fun parseArea(text: String): Area? {
        val parts = text.trim().split(':')
        if (parts.size > 2) return null
        val a = parseCell(parts[0]) ?: return null
        val b = if (parts.size == 2) parseCell(parts[1]) ?: return null else a
        return Area(minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first), maxOf(a.second, b.second))
    }
}

/** An inclusive rectangle of cells, 0-based. */
data class Area(val c1: Int, val r1: Int, val c2: Int, val r2: Int) {
    val cols: Int get() = c2 - c1 + 1
    val rows: Int get() = r2 - r1 + 1
    fun contains(c: Int, r: Int) = c in c1..c2 && r in r1..r2
    fun intersects(o: Area) = c1 <= o.c2 && o.c1 <= c2 && r1 <= o.r2 && o.r1 <= r2
    fun label(): String = if (c1 == c2 && r1 == r2) A1.cell(c1, r1) else A1.cell(c1, r1) + ":" + A1.cell(c2, r2)
}

/** How a number is shown. */
data class Format(val kind: Kind = Kind.GENERAL, val decimals: Int = 2, val currency: String = "$") {
    enum class Kind { GENERAL, NUMBER, PERCENT, CURRENCY, DATE }

    companion object {
        val GENERAL = Format()
        fun number(decimals: Int = 2) = Format(Kind.NUMBER, decimals)
        fun percent(decimals: Int = 0) = Format(Kind.PERCENT, decimals)
        fun currency(symbol: String = "$", decimals: Int = 2) = Format(Kind.CURRENCY, decimals, symbol)
        val DATE = Format(Kind.DATE)
    }
}

/** Text of values, number formatting and date serials (days since 1899-12-30, the Excel/LibreOffice epoch). */
object Fmt {
    private val EPOCH: LocalDate = LocalDate.of(1899, 12, 30)

    fun serial(d: LocalDate): Double = java.time.temporal.ChronoUnit.DAYS.between(EPOCH, d).toDouble()
    fun date(serial: Double): LocalDate = EPOCH.plusDays(Math.floor(serial).toLong())

    /** General: integers bare, others up to 10 significant digits without trailing zeros. */
    fun general(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return Value.VALUE
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return v.toLong().toString()
        val bd = BigDecimal(v).round(java.math.MathContext(10, RoundingMode.HALF_UP))
        val plain = if (Math.abs(v) >= 1e15 || Math.abs(v) < 1e-9) bd.stripTrailingZeros().toString() else bd.stripTrailingZeros().toPlainString()
        return plain.replace("E+", "E")
    }

    fun grouped(v: Double, decimals: Int): String {
        val bd = BigDecimal(v).setScale(decimals, RoundingMode.HALF_UP)
        val neg = bd.signum() < 0
        val s = bd.abs().toPlainString()
        val intPart = s.substringBefore('.')
        val frac = if (s.contains('.')) "." + s.substringAfter('.') else ""
        val g = intPart.reversed().chunked(3).joinToString(",").reversed()
        return (if (neg) "-" else "") + g + frac
    }

    fun text(v: Value): String = when (v) {
        is Value.Num -> general(v.v)
        is Value.Str -> v.s
        is Value.Bool -> if (v.b) "TRUE" else "FALSE"
        is Value.Err -> v.code
        Value.Blank -> ""
    }

    fun display(v: Value, f: Format): String {
        if (v !is Value.Num) return text(v)
        return when (f.kind) {
            Format.Kind.GENERAL -> general(v.v)
            Format.Kind.NUMBER -> grouped(v.v, f.decimals.coerceIn(0, 15))
            Format.Kind.PERCENT -> grouped(v.v * 100, f.decimals.coerceIn(0, 15)).replace(",", "") + "%"
            Format.Kind.CURRENCY -> {
                val s = grouped(Math.abs(v.v), f.decimals.coerceIn(0, 15))
                (if (v.v < 0) "-" else "") + f.currency + s
            }
            Format.Kind.DATE -> runCatching { date(v.v).toString() }.getOrDefault(Value.VALUE)
        }
    }

    /** The cell input [text] as a value, plus the format it implies ("50%" is 0.5 as a percent, an ISO date a date). */
    fun entry(text: String): Pair<Value, Format?> {
        if (text.startsWith("'")) return Value.Str(text.substring(1)) to null
        val t = text.trim()
        if (t.isEmpty()) return Value.Blank to null
        if (t.equals("TRUE", true)) return Value.Bool(true) to null
        if (t.equals("FALSE", true)) return Value.Bool(false) to null
        if (Value.ERRORS.contains(t.uppercase())) return Value.Err(t.uppercase()) to null
        number(t)?.let { return Value.Num(it) to null }
        if (t.endsWith("%")) number(t.dropLast(1).trim())?.let { return Value.Num(it / 100) to Format.percent(decimalsOf(t.dropLast(1))) }
        if (Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(t)) runCatching { LocalDate.parse(t) }.getOrNull()?.let { return Value.Num(serial(it)) to Format.DATE }
        return Value.Str(text) to null
    }

    private fun decimalsOf(s: String): Int = if (s.contains('.')) s.substringAfter('.').trim().length else 0

    /** A plain decimal number ("12", "-3.5", "1e3", ".5"); not NaN, not "Infinity", not hex. */
    fun number(t: String): Double? {
        if (!Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$").matches(t)) return null
        return t.toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }
    }
}
