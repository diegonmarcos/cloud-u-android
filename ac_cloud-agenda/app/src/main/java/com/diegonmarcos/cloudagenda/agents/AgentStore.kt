package com.diegonmarcos.cloudagenda.agents

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the screenshot agent remembers between runs, in this app's private
 * preferences: whether it is on, which candidates were dismissed (so they
 * never come back), and the events the user accepted (which the Calendar
 * shows under the "agents" calendar).
 *
 * Candidates are identified by the key the parser derives from title, day
 * and time, so the same event seen in two screenshots is one candidate.
 */
class AgentStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("cal_agents", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(v) { prefs.edit().putBoolean(KEY_ENABLED, v).apply() }

    var lastScan: Long
        get() = prefs.getLong(KEY_LAST_SCAN, 0L)
        set(v) { prefs.edit().putLong(KEY_LAST_SCAN, v).apply() }

    @Synchronized
    fun dismissed(): Set<String> = prefs.getStringSet(KEY_DISMISSED, emptySet())?.toSet() ?: emptySet()

    @Synchronized
    fun dismiss(key: String) {
        if (key.isBlank()) return
        prefs.edit().putStringSet(KEY_DISMISSED, dismissed() + key).apply()
    }

    @Synchronized
    fun accepted(): JSONArray =
        runCatching { JSONArray(prefs.getString(KEY_ACCEPTED, "[]")) }.getOrDefault(JSONArray())

    /** Stores one accepted event; a second accept of the same key replaces it. */
    @Synchronized
    fun accept(event: JSONObject) {
        val key = event.optString("key")
        val out = JSONArray()
        val cur = accepted()
        for (i in 0 until cur.length()) {
            val e = cur.optJSONObject(i) ?: continue
            if (e.optString("key") != key) out.put(e)
        }
        out.put(event)
        prefs.edit().putString(KEY_ACCEPTED, out.toString()).apply()
    }

    /** Removes an accepted event and remembers its key as dismissed. */
    @Synchronized
    fun remove(key: String) {
        val out = JSONArray()
        val cur = accepted()
        for (i in 0 until cur.length()) {
            val e = cur.optJSONObject(i) ?: continue
            if (e.optString("key") != key) out.put(e)
        }
        prefs.edit().putString(KEY_ACCEPTED, out.toString()).apply()
        dismiss(key)
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_LAST_SCAN = "last_scan"
        const val KEY_DISMISSED = "dismissed"
        const val KEY_ACCEPTED = "accepted"
    }
}
