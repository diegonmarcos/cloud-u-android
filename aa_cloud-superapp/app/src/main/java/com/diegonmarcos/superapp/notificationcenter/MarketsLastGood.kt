package com.diegonmarcos.superapp.notificationcenter

import android.content.Context

/**
 * Persists the Markets badge's last GOOD price per symbol (price, Yahoo asOf,
 * saved-at) in SharedPreferences, so it survives the process being killed.
 * Only [MarketsQuotes.isGood] quotes are ever written: a zero, null or error
 * result can never overwrite a saved rate. Loaded quotes come back marked
 * [MarketsQuotes.Quote.cached] until a fresh fetch replaces them.
 */
class MarketsLastGood(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(quotes: Map<String, MarketsQuotes.Quote>) {
        val e = prefs.edit()
        var dirty = false
        for ((symbol, q) in quotes) {
            if (!MarketsQuotes.isGood(q) || q.cached) continue
            e.putString(symbol, "${q.price}|${q.asOfMillis}|${q.savedAtMillis}")
            dirty = true
        }
        if (dirty) e.apply()
    }

    fun load(): Map<String, MarketsQuotes.Quote> = prefs.all.mapNotNull { (symbol, v) ->
        val parts = (v as? String)?.split('|') ?: return@mapNotNull null
        val price = parts.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
        val q = MarketsQuotes.Quote(
            symbol = symbol,
            price = price,
            asOfMillis = parts.getOrNull(1)?.toLongOrNull() ?: 0L,
            status = MarketsQuotes.Status.OK,
            reason = "",
            cached = true,
            savedAtMillis = parts.getOrNull(2)?.toLongOrNull() ?: 0L,
        )
        if (MarketsQuotes.isGood(q)) symbol to q else null
    }.toMap()

    companion object {
        const val PREFS = "markets_last_good"
    }
}
