package com.diegonmarcos.cloudsearch.core.agents

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** What a model costs per token, in US dollars (OpenRouter publishes both as decimal strings per token). */
data class Pricing(val promptPerToken: Double, val completionPerToken: Double) {
    companion object {
        /** The catalogue's price for [model], or null when it is not listed or the price is not a plain number (e.g. `-1` for a router). */
        fun fromCatalog(json: String, model: String): Pricing? {
            val data = runCatching { JSONObject(json).optJSONArray("data") }.getOrNull() ?: return null
            for (i in 0 until data.length()) {
                val m = data.optJSONObject(i) ?: continue
                if (m.optString("id") != model) continue
                val p = m.optJSONObject("pricing") ?: return null
                val prompt = p.optString("prompt").toDoubleOrNull() ?: return null
                val completion = p.optString("completion").toDoubleOrNull() ?: return null
                if (prompt < 0 || completion < 0) return null
                return Pricing(prompt, completion)
            }
            return null
        }
    }
}

/** The owner's caps in US dollars: for one run and for one calendar day. A cap of 0 means no model call at all. */
data class Caps(val runUsd: Double, val dayUsd: Double)

sealed interface Spend {
    data object Allowed : Spend
    data class Denied(val scope: String, val why: String) : Spend
}

/**
 * The budget guard. Every model call is checked BEFORE it is made against its worst-case cost (the prompt as
 * priced plus the full completion allowance), and the real cost is recorded after. A call that could take the run
 * or the day past its cap is not made.
 */
class BudgetLedger(private val caps: Caps, private val spentTodayUsd: Double, private val onSpend: (Double) -> Unit = {}) {
    var spentRunUsd: Double = 0.0
        private set

    val spentDayUsd: Double get() = spentTodayUsd + spentRunUsd

    fun check(estimateUsd: Double): Spend {
        if (estimateUsd < 0 || estimateUsd.isNaN()) return Spend.Denied("run", "the cost estimate is not a number")
        if (spentRunUsd + estimateUsd > caps.runUsd) {
            return Spend.Denied("run", "this run's cap of ${usd(caps.runUsd)} would be passed (spent ${usd(spentRunUsd)}, next call up to ${usd(estimateUsd)})")
        }
        if (spentDayUsd + estimateUsd > caps.dayUsd) {
            return Spend.Denied("day", "today's cap of ${usd(caps.dayUsd)} would be passed (spent ${usd(spentDayUsd)}, next call up to ${usd(estimateUsd)})")
        }
        return Spend.Allowed
    }

    fun record(actualUsd: Double) {
        val v = if (actualUsd.isNaN() || actualUsd < 0) 0.0 else actualUsd
        spentRunUsd += v
        onSpend(v)
    }

    companion object {
        /** About three characters to a token for German text: deliberately on the high side. */
        const val CHARS_PER_TOKEN = 3.0

        /** The worst case of one call: [promptChars] of input and the whole [maxTokens] of output. */
        fun estimate(promptChars: Int, maxTokens: Int, price: Pricing): Double =
            Math.ceil(promptChars / CHARS_PER_TOKEN) * price.promptPerToken + maxTokens * price.completionPerToken

        /** What a finished call cost: the provider's own figure when it sent one, else tokens times the price. */
        fun actual(promptTokens: Int, completionTokens: Int, providerCostUsd: Double?, price: Pricing): Double =
            providerCostUsd?.takeIf { it >= 0 } ?: (promptTokens * price.promptPerToken + completionTokens * price.completionPerToken)

        /** The calendar day [nowMs] falls on in [zone], as `2026-10-08` (what the day's spend is filed under). */
        fun dayKey(nowMs: Long, zone: ZoneId): String = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toString()

        fun usd(v: Double): String = "$" + String.format(java.util.Locale.ROOT, "%.4f", v)
    }
}
