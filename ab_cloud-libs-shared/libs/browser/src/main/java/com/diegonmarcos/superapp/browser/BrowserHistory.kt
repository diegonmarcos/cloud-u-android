package com.diegonmarcos.superapp.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One recorded page visit. Pure data. */
data class BrowserVisit(val url: String, val title: String, val ts: Long)

/**
 * Local browsing history.
 *
 * ON-DEVICE ONLY, AND THAT IS A REQUIREMENT RATHER THAN AN OVERSIGHT.
 * This class has no network code, no sink, no analytics call and no
 * exporter, and it must never grow one: it is a personal phone's
 * browsing history. The diagnostics sink configured in build.json
 * (BuildConfig.LOG_SINK_URL) is for crash/debug traces and nothing in
 * this file is routed to it.
 *
 * SharedPreferences-backed for the same reason [BrowserTabPrefs] is —
 * a few hundred rows do not justify a database dependency.
 */
class BrowserHistory(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("browser_history", Context.MODE_PRIVATE)

    /** Newest first. */
    fun all(): List<BrowserVisit> {
        val raw = sp.getString(KEY, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val out = ArrayList<BrowserVisit>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val u = o.optString("url")
            if (u.isNotBlank()) {
                out.add(BrowserVisit(u, o.optString("title", u), o.optLong("ts", 0L)))
            }
        }
        return out.sortedByDescending { it.ts }
    }

    /**
     * Record a visit. Re-visiting a URL moves it up rather than adding a
     * duplicate row, so the list reads as "pages", not "page loads" —
     * which is what makes it useful as a suggestion source.
     */
    fun record(url: String, title: String) {
        if (url.isBlank() || url == "about:blank") return
        val list = all().filterNot { it.url == url }.toMutableList()
        list.add(0, BrowserVisit(url, title.ifBlank { url }, System.currentTimeMillis()))
        save(list.take(CAP))
    }

    fun clear() = save(emptyList())

    private fun save(visits: List<BrowserVisit>) {
        val arr = JSONArray()
        for (v in visits) {
            arr.put(JSONObject().apply {
                put("url", v.url); put("title", v.title); put("ts", v.ts)
            })
        }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "history_json"
        /** ponytail: a flat cap, not time-based expiry. Add expiry if he asks for it. */
        private const val CAP = 500
    }
}
