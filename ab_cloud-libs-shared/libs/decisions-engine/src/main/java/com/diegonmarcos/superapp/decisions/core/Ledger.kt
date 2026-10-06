package com.diegonmarcos.superapp.decisions.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Why a call was not made for want of budget. [wire] is the reason a caller reads. */
enum class Refusal(val wire: String) {
    OVER_BUDGET("over_budget"),
    APP_QUOTA("app_quota"),
    RATE_LIMITED("rate_limited"),
}

/**
 * The spend control (jev-fleet-usage.md section 2: "cost is logged, never enforced"):
 *  - the FLEET's daily USD cap: once today's spend reaches it, nothing is asked until the UTC day rolls;
 *  - a per-app daily quota of calls, so one app cannot spend the fleet's day;
 *  - a per-use hourly limit (sliding window), the use's own max_calls_per_hour.
 *
 * [admit] COUNTS the call against its app and use before it is made, so concurrent callers cannot all
 * slip under a limit; [charge] adds what the answer cost (the estimate when it carried none). Persisted
 * through [store] so a restart does not reset today's spend. Thread-safe.
 */
class Ledger(private val store: KvStore, private val rules: BudgetRules, private val clock: () -> Long) {

    private var doc: JSONObject = store.read() ?: JSONObject()

    @Synchronized
    fun admit(app: String, use: String, maxPerHour: Int): Refusal? {
        val now = clock()
        roll(now)
        if (doc.optDouble("spent", 0.0) >= rules.dailyUsdCap) return Refusal.OVER_BUDGET
        val apps = doc.getJSONObject("apps")
        if (apps.optInt(app, 0) >= rules.perAppDailyCalls) return Refusal.APP_QUOTA
        val calls = doc.getJSONObject("calls")
        val recent = recent(calls.optJSONArray(use), now)
        if (recent.size >= maxPerHour) {
            calls.put(use, JSONArray(recent))
            return Refusal.RATE_LIMITED
        }
        calls.put(use, JSONArray(recent + now))
        apps.put(app, apps.optInt(app, 0) + 1)
        store.write(doc)
        return null
    }

    /** Add one answered call's cost: [cost] when it is a real non-negative number, else the estimate. */
    @Synchronized
    fun charge(cost: Double?) {
        roll(clock())
        val amount = if (cost != null && cost >= 0.0 && !cost.isNaN()) cost else rules.estCallUsd
        doc.put("spent", doc.optDouble("spent", 0.0) + amount)
        store.write(doc)
    }

    @Synchronized
    fun spentToday(): Double {
        roll(clock())
        return doc.optDouble("spent", 0.0)
    }

    @Synchronized
    fun callsToday(app: String): Int {
        roll(clock())
        return doc.getJSONObject("apps").optInt(app, 0)
    }

    private fun recent(a: JSONArray?, now: Long): List<Long> =
        if (a == null) emptyList() else (0 until a.length()).map { a.optLong(it) }.filter { it > now - HOUR_MS }

    /** A new UTC day starts the spend and the per-app counts from zero; the hourly window keeps sliding. */
    private fun roll(now: Long) {
        val day = LocalDate.ofEpochDay(Math.floorDiv(now, DAY_MS)).toString()
        if (doc.optString("day") != day) {
            doc.put("day", day).put("spent", 0.0).put("apps", JSONObject())
        }
        if (!doc.has("calls")) doc.put("calls", JSONObject())
        if (!doc.has("apps")) doc.put("apps", JSONObject())
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 86_400_000L
    }
}
