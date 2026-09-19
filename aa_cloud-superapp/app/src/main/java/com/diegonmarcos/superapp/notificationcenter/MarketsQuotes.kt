package com.diegonmarcos.superapp.notificationcenter

import com.diegonmarcos.superapp.notificationcenter.BadgeDeclaration.Instrument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * #517 — where the Markets badge's numbers come from, and the one rule they
 * obey: a price is printed only when this fetch produced it.
 *
 * ENDPOINT, and why this one. Yahoo Finance has no official public API and the
 * two commonly-used endpoints do NOT behave the same:
 *
 *  • `/v7/finance/quote?symbols=…` now requires a cookie+crumb handshake. From
 *    this app's own network path it answers
 *    `401 {"code":"Unauthorized","description":"User is unable to access this
 *    feature"}` — and it would answer that in the owner's pocket while every
 *    fixture-driven test stayed green, which is the worst of both.
 *  • `/v8/finance/chart/<symbol>` answers unauthenticated, with the last price
 *    at `chart.result[0].meta.regularMarketPrice` and — critically — its own
 *    `regularMarketTime`, so the quote carries the age of the number rather
 *    than the age of the request.
 *
 * So: v8, no key, nothing to put in sops. Verified against all four declared
 * symbols before this shipped, including that `BRL=X` really is the USD->BRL
 * cross (Yahoo's own `shortName` for it is the string "USD/BRL").
 *
 * FAILURE IS NEVER A NUMBER. [parse] and [row] are pure and total: every path
 * that did not end in a price Yahoo actually sent ends in [Status.UNAVAILABLE]
 * and renders as [DASH]. There is deliberately NO cache in here — a cache is
 * the mechanism by which the last good price gets redrawn as though it were
 * live, and that is the one defect this badge must not have. A price the badge
 * cannot refresh disappears; it does not quietly persist.
 *
 * No new dependency: `HttpURLConnection` + `org.json`, the same pair every
 * other feed in this app uses.
 */
object MarketsQuotes {

    /** Why a row looks the way it does. [OK] means Yahoo sent this price. */
    enum class Status { OK, UNAVAILABLE }

    /**
     * @param asOfMillis Yahoo's own `regularMarketTime`, not the time of the
     *   request — a Friday close read on a Sunday is two days old and has to
     *   say so.
     * @param reason the sentence the expanded badge shows. Empty when [OK].
     */
    data class Quote(
        val symbol: String,
        val price: Double?,
        val asOfMillis: Long,
        val status: Status,
        val reason: String = "",
    )

    const val CHART_ENDPOINT = "https://query1.finance.yahoo.com/v8/finance/chart/"

    /** What an unavailable price prints as. An em dash, not "0" and not "—?":
     *  the owner has to be able to tell "no data" from a real number at a
     *  glance, in a notification line he reads sideways. */
    const val DASH = "—"

    private const val UA = "Diego-SuperApp/1.0 (+https://diegonmarcos.com)"
    private const val TIMEOUT_MS = 12_000

    fun unavailable(symbol: String, reason: String) =
        Quote(symbol, null, 0L, Status.UNAVAILABLE, reason)

    fun chartUrl(symbol: String): String =
        CHART_ENDPOINT + URLEncoder.encode(symbol, "UTF-8") + "?interval=1d&range=1d"

    /**
     * PURE. The whole JSON contract in one testable function: no network, no
     * context, no clock.
     *
     * `optDouble` is avoided on purpose — it returns NaN for a missing key,
     * and NaN formats as "NaN" in a notification rather than failing. Every
     * shape that is not a finite number is [Status.UNAVAILABLE].
     */
    fun parse(symbol: String, body: String?): Quote {
        if (body.isNullOrBlank()) return unavailable(symbol, "Yahoo sent an empty response.")
        val meta = runCatching {
            JSONObject(body)
                .getJSONObject("chart")
                .getJSONArray("result")
                .getJSONObject(0)
                .getJSONObject("meta")
        }.getOrNull() ?: return unavailable(symbol, "Yahoo's response did not carry a quote.")

        val price = meta.opt("regularMarketPrice") as? Number
        val value = price?.toDouble()
        if (value == null || !value.isFinite())
            return unavailable(symbol, "Yahoo answered without a price for $symbol.")

        // Seconds in the payload; the rest of the app works in millis.
        val asOf = (meta.opt("regularMarketTime") as? Number)?.toLong()?.times(1000L) ?: 0L
        return Quote(symbol, value, asOf, Status.OK)
    }

    /**
     * One instrument, one request. Every throwable becomes an
     * [Status.UNAVAILABLE] carrying the reason: an offline phone and a
     * rate-limited endpoint are different facts and the expanded badge says
     * which. A non-200 is never parsed for a price.
     */
    suspend fun fetch(instrument: Instrument): Quote = withContext(Dispatchers.IO) {
        runCatching {
            val c = (URL(chartUrl(instrument.symbol)).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json")
            }
            try {
                when (val code = c.responseCode) {
                    200 -> parse(instrument.symbol, c.inputStream.bufferedReader().use { it.readText() })
                    401, 403 -> unavailable(instrument.symbol,
                        "Yahoo refused the request ($code) — this endpoint now wants a key.")
                    429 -> unavailable(instrument.symbol, "Rate limited by Yahoo (429).")
                    else -> unavailable(instrument.symbol, "Yahoo answered HTTP $code.")
                }
            } finally {
                c.disconnect()
            }
        }.getOrElse {
            unavailable(instrument.symbol, "Could not reach Yahoo: ${it.javaClass.simpleName}.")
        }
    }

    /**
     * PURE. One badge row, and the only place a price becomes text.
     *
     * Three outcomes, never mixed up:
     *  • no quote at all, or one that failed  -> "USD/BRL —"
     *  • a price newer than [staleAfterMillis] -> "USD/BRL 5.1421"
     *  • a price older than that               -> "USD/BRL 5.1421 (2d ago)"
     *
     * The dated form still shows the number because a Friday close IS the last
     * price over a weekend and hiding it would be its own lie — but it can
     * never be mistaken for live, which is the requirement.
     *
     * WHY "ago" AND NOT "stale". Checked against real responses before this
     * shipped: `regularMarketTime` for a future is the REGULAR SESSION's time,
     * not the last tick, so `CL=F` and `GC=F` legitimately read 12 hours behind
     * for most of the day and everything reads days behind over a weekend. That
     * is a fact about the exchange, not a malfunction, and labelling it "old"
     * would cry wolf until the owner stopped reading the marker — which is
     * precisely how a real stale price then slips past. The marker that means
     * something went wrong is [DASH], and [DASH] is reachable only from a fetch
     * that actually failed.
     */
    fun row(instrument: Instrument, quote: Quote?, nowMillis: Long, staleAfterMillis: Long): String {
        val price = quote?.price
        if (quote == null || quote.status != Status.OK || price == null || !price.isFinite())
            return "${instrument.label} $DASH"
        val text = "${instrument.label} ${format(price, instrument.decimals)}"
        val age = nowMillis - quote.asOfMillis
        // asOfMillis == 0 means Yahoo sent a price with no timestamp: it cannot
        // be shown as fresh, because nothing here knows that it is.
        if (quote.asOfMillis <= 0L) return "$text (age unknown)"
        return if (age > staleAfterMillis) "$text (${ageText(age)} ago)" else text
    }

    /** The single line the collapsed badge shows. */
    fun summary(
        instruments: List<Instrument>,
        quotes: Map<String, Quote>,
        nowMillis: Long,
        staleAfterMillis: Long,
    ): String = instruments
        .joinToString("  ·  ") { row(it, quotes[it.symbol], nowMillis, staleAfterMillis) }

    private fun format(price: Double, decimals: Int) =
        String.format(Locale.US, "%.${decimals}f", price)

    /** Coarse on purpose: "3d" is the useful fact, "3d 4h 12m" is noise in a
     *  notification line. */
    private fun ageText(ms: Long): String {
        val minutes = ms / 60_000L
        return when {
            minutes < 60 -> "${minutes}m"
            minutes < 1_440 -> "${minutes / 60}h"
            else -> "${minutes / 1_440}d"
        }
    }
}
