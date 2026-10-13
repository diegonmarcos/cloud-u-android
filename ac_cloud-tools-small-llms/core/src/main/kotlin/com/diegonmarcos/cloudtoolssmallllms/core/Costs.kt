package com.diegonmarcos.cloudtoolssmallllms.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** A model's price in dollars per 1M tokens, as the shared catalogue publishes it. */
data class Price(val inPerM: Double, val outPerM: Double)

/**
 * Where prices come from: the app adapts libs:model-catalogue (its live OpenRouter pricing, cached,
 * the bundled snapshot offline) to this; the suite adapts the bundled catalogue.json. null = the
 * catalogue does not price that model.
 */
fun interface PriceBook {
    fun price(model: String): Price?
}

/** What OpenRouter's `usage` says a call took; [cost] is its own USD figure when it sends one. */
data class Usage(val promptTokens: Int, val completionTokens: Int, val cost: Double?)

object Costs {
    private const val MILLION = 1_000_000.0

    /** [tokensIn] in and [tokensOut] out at [price]. */
    fun estimate(price: Price, tokensIn: Int, tokensOut: Int): Double = (tokensIn * price.inPerM + tokensOut * price.outPerM) / MILLION

    /**
     * A chat completion's `usage`: prompt_tokens, completion_tokens and OpenRouter's `cost` (USD).
     * Decisions answers name them input_tokens / output_tokens. null when there is no usage at all.
     */
    fun usage(o: JSONObject?): Usage? {
        if (o == null) return null
        val pin = num(o, "prompt_tokens") ?: num(o, "input_tokens")
        val pout = num(o, "completion_tokens") ?: num(o, "output_tokens")
        val cost = num(o, "cost")?.takeIf { it >= 0 }
        if (pin == null && pout == null && cost == null) return null
        return Usage(pin?.toInt() ?: 0, pout?.toInt() ?: 0, cost)
    }

    /** What a call actually cost: OpenRouter's own figure when sent, else its tokens at [price]; null when neither is known. */
    fun actual(u: Usage?, price: Price?): Double? = when {
        u == null -> null
        u.cost != null -> u.cost
        price != null -> estimate(price, u.promptTokens, u.completionTokens)
        else -> null
    }

    /** "$0.000123", "$0.0420", "$1.25": three significant figures under a cent, as the cost chip shows it. */
    fun usd(x: Double?): String {
        if (x == null || x.isNaN() || x < 0) return "—"
        if (x == 0.0) return "$0"
        if (x >= 0.01) return "$" + String.format(java.util.Locale.ROOT, "%.4f", x).trimEnd('0').let { if (it.endsWith('.')) it + "00" else it }
        return "$" + java.math.BigDecimal(x).round(java.math.MathContext(3)).stripTrailingZeros().toPlainString()
    }

    private fun num(o: JSONObject, k: String): Double? = o.opt(k)?.takeIf { it is Number }?.let { (it as Number).toDouble() }
}

/** Every priced call, kept for the day and month totals. Plain data: the app stores [toJson]. */
class Ledger(entries: List<Entry> = emptyList()) {
    data class Entry(val at: Long, val usd: Double, val task: String, val model: String)

    private val list = entries.toMutableList()
    val entries: List<Entry> get() = list.toList()

    fun add(e: Entry) {
        require(e.usd >= 0 && !e.usd.isNaN()) { "a cost is never negative" }
        list += e
    }

    /** The total of the calendar day [now] falls in, in [zone]. */
    fun day(now: Long, zone: ZoneId): Double {
        val d = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return list.filter { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() == d }.sumOf { it.usd }
    }

    /** The total of the calendar month [now] falls in, in [zone]. */
    fun month(now: Long, zone: ZoneId): Double {
        val m = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return list.filter { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate().let { d -> d.year == m.year && d.month == m.month } }.sumOf { it.usd }
    }

    /** Per task, this month: task id -> (calls, dollars), most expensive first. */
    fun byTask(now: Long, zone: ZoneId): List<Triple<String, Int, Double>> {
        val m = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return list.filter { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate().let { d -> d.year == m.year && d.month == m.month } }
            .groupBy { it.task }.map { (k, v) -> Triple(k, v.size, v.sumOf { it.usd }) }.sortedByDescending { it.third }
    }

    fun toJson(): String = JSONArray(list.map { JSONObject().put("at", it.at).put("usd", it.usd).put("task", it.task).put("model", it.model) }).toString()

    companion object {
        /** A stored ledger; anything unreadable is an empty one, never a crash. */
        fun fromJson(s: String?): Ledger {
            val a = runCatching { JSONArray(s ?: return Ledger()) }.getOrNull() ?: return Ledger()
            return Ledger((0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val usd = o.optDouble("usd", -1.0)
                if (usd < 0) null else Entry(o.optLong("at"), usd, o.optString("task"), o.optString("model"))
            })
        }
    }
}
