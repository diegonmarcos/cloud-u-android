package com.diegonmarcos.cloudcalc

import com.diegonmarcos.cloudcalc.engine.CalcApi
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Exchange-rate freshness, the favourites' ordering and the cross-rate matrix: pure, so a JVM test holds them. */
object Fx {
    private val CET: ZoneId = ZoneId.of("Europe/Berlin")
    /** The ECB publishes its daily reference rates at about 16:00 CET on working days. */
    private const val PUBLISH_HOUR = 16
    /** A refresh that found nothing newer (a holiday) is not repeated on every open. */
    const val RETRY_MS = 60 * 60 * 1000L

    @Volatile var lastAttemptMs = 0L

    /** The date of the latest ECB publication that has happened at [nowMs]. */
    fun expectedDate(nowMs: Long): LocalDate {
        val z = Instant.ofEpochMilli(nowMs).atZone(CET)
        var d = if (z.hour >= PUBLISH_HOUR) z.toLocalDate() else z.toLocalDate().minusDays(1)
        while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.minusDays(1)
        return d
    }

    /** The calendar date a rates stamp (epoch seconds) names, null when there is none. */
    fun dateOf(epochSec: Long): LocalDate? = if (epochSec > 0) Instant.ofEpochSecond(epochSec).atZone(ZoneOffset.UTC).toLocalDate() else null

    /** Older than the latest ECB publication (or unknown). */
    fun isStale(epochSec: Long, nowMs: Long): Boolean = dateOf(epochSec)?.let { it < expectedDate(nowMs) } ?: true

    /** Stale, and not attempted within [RETRY_MS]. */
    fun shouldRefresh(epochSec: Long, nowMs: Long): Boolean = isStale(epochSec, nowMs) && nowMs - lastAttemptMs >= RETRY_MS

    /** The stamp of a ratesInfo / fetchRates answer. */
    fun time(json: String): Long = runCatching { JSONObject(json).optLong("time") }.getOrDefault(0L)

    /** Fetch when the rates are stale; the fetch's answer, or null when nothing was due. */
    fun refreshIfStale(api: CalcApi, nowMs: Long): String? {
        // One caller wins the attempt (the pager may compose two converters at once).
        val due = synchronized(this) { shouldRefresh(time(api.ratesInfo()), nowMs).also { if (it) lastAttemptMs = nowMs } }
        return if (due) api.fetchRates() else null
    }

    /** Fetch now (the daily job, the button): the engine replaces its files only on success. */
    fun refresh(api: CalcApi, nowMs: Long): String {
        lastAttemptMs = nowMs
        return api.fetchRates()
    }

    /** [codes] with the favourites first (in their declared order), a divider position after them. */
    fun pinned(codes: List<String>, favourites: List<String>): Pair<List<String>, List<String>> {
        val fav = favourites.filter { it in codes }
        return fav to codes.filter { it !in fav }
    }

    /** The ECB table is EUR-based: every rate is units of the currency per 1 EUR. */
    const val BASE = "EUR"
    private val NUMBER = Regex("""-?\d+(\.\d+)?""")
    private val DECIMAL = Regex("""-?\d+\.\d+""")

    /** The first number of an engine answer ("1.1634 USD", "≈ 1.16 USD"), null when there is none. */
    fun numberIn(text: String): Double? = NUMBER.find(text.replace(",", ""))?.value?.toDoubleOrNull()

    /** [x] cut (not rounded) to at most 4 decimals, trailing zeros stripped; float noise past 10 digits (1.2/6 = 0.19999999999999998) is not a digit to cut. */
    fun trunc(x: Double): String =
        if (!x.isFinite()) "?" else java.math.BigDecimal(x.toString()).round(java.math.MathContext(10)).setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }

    /** Every decimal number in [text] cut to at most 4 decimals ("1.16349 USD" -> "1.1634 USD"). */
    fun truncateNumbers(text: String): String = DECIMAL.replace(text) { m ->
        java.math.BigDecimal(m.value).setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }
    }

    /**
     * The cross rates of [codes] from ONE EUR-based table ([perEur]: units of each currency per 1 EUR):
     * the cell (row r, column c) is how much of c one unit of r buys = perEur[c] / perEur[r]; the diagonal is 1.
     * A currency missing from the table leaves its row and column null.
     */
    fun matrix(codes: List<String>, perEur: Map<String, Double>): Map<Pair<String, String>, Double?> =
        codes.flatMap { r -> codes.map { c -> r to c } }.associateWith { (r, c) ->
            val a = perEur[r]; val b = perEur[c]
            if (r == c) 1.0 else if (a == null || b == null || a == 0.0) null else b / a
        }
}
