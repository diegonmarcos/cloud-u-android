package com.diegonmarcos.capitalfinterm

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The one shared performance function for every performance table. Columns: WTD, MTD, YTD (period-to-date, base = last close before
 * the period starts) | 1M, 3M, 1Y, 3Y, 10Y (trailing, base = last close on or before as-of minus the period). Periods of one year or
 * less are simple total return; trailing periods over one year are annualised CAGR, (end/start)^(1/years) - 1. A column is null ("—")
 * when the history does not reach the base date or a price is not positive.
 */
object Performance {
    val COLUMNS = listOf("WTD", "MTD", "YTD", "1M", "3M", "1Y", "3Y", "10Y")

    /** Fractional returns (0.05 = +5%) per [COLUMNS] for a daily close series; order of [closes] does not matter. */
    fun row(closes: List<Pair<LocalDate, Double>>): List<Double?> {
        val s = closes.filter { it.second.isFinite() }.sortedBy { it.first }
        if (s.isEmpty()) return COLUMNS.map { null }
        val (asOf, last) = s.last()
        fun before(d: LocalDate) = s.lastOrNull { it.first.isBefore(d) }?.second
        fun onOrBefore(d: LocalDate) = if (s.first().first.isAfter(d)) null else s.lastOrNull { !it.first.isAfter(d) }?.second
        val weekStart = asOf.with(DayOfWeek.MONDAY)
        return listOf(
            simple(before(weekStart), last),
            simple(before(asOf.withDayOfMonth(1)), last),
            simple(before(asOf.withDayOfYear(1)), last),
            simple(onOrBefore(asOf.minusMonths(1)), last),
            simple(onOrBefore(asOf.minusMonths(3)), last),
            simple(onOrBefore(asOf.minusYears(1)), last),
            cagr(onOrBefore(asOf.minusYears(3)), last, 3.0),
            cagr(onOrBefore(asOf.minusYears(10)), last, 10.0),
        )
    }

    fun simple(start: Double?, end: Double): Double? =
        if (start == null || start <= 0.0 || end <= 0.0) null else end / start - 1.0

    fun cagr(start: Double?, end: Double, years: Double): Double? =
        if (start == null || start <= 0.0 || end <= 0.0 || years <= 0.0) null
        else if (years <= 1.0) simple(start, end) else Math.pow(end / start, 1.0 / years) - 1.0

    /** Display: signed percent with two decimals, or "—" for a missing value. */
    fun format(v: Double?): String = if (v == null || !v.isFinite()) "—" else String.format(java.util.Locale.US, "%+.2f%%", v * 100.0)
}
