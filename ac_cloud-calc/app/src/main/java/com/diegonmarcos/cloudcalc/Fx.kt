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
        if (!shouldRefresh(time(api.ratesInfo()), nowMs)) return null
        return refresh(api, nowMs)
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

    /** The matrix cell text from the engine's answer: the number, no unit. */
    fun cell(resultText: String): String {
        val n = resultText.trim().split(Regex("\\s+")).firstOrNull().orEmpty().replace(",", "")
        return n.toBigDecimalOrNull()?.round(java.math.MathContext(4))?.stripTrailingZeros()?.toPlainString() ?: n
    }
}
