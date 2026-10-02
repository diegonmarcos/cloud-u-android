package com.diegonmarcos.superapp.notificationcenter

import android.content.Context
import com.diegonmarcos.superapp.core.FleetAlerts
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * #777 — every alert the fleet raised ([FleetAlerts]), newest first, plus the
 * owner's Alerts filters. The ONLY content of the Alerts group: there is no
 * sample feed behind it any more (the old Infos badge baked one into the APK).
 *
 * Dedupe: an alert with a dedupe key REPLACES the same app's earlier alert
 * under that key, so a check that runs every night says "2 updates available"
 * once, not once per night.
 *
 * Filters hide, they never delete: a muted app or severity is still stored and
 * still listed by /api/notify/alerts, it just stays out of the shade and off
 * the page until unmuted.
 */
object AlertStore {

    private const val PREFS = "fleet_alerts"
    private const val KEY = "alerts"
    private const val MAX = 100

    data class Alert(
        val id: String,
        val app: String,
        val title: String,
        val text: String,
        val severity: String,
        val deepLink: String,
        val dedupeKey: String,
        val ts: Long,
    ) {
        fun json(): JSONObject = JSONObject()
            .put("id", id).put("app", app).put("title", title).put("text", text)
            .put("severity", severity).put("deep_link", deepLink).put("dedupe_key", dedupeKey).put("ts", ts)

        companion object {
            fun of(o: JSONObject) = Alert(
                id = o.optString("id"), app = o.optString("app"), title = o.optString("title"),
                text = o.optString("text"), severity = o.optString("severity", FleetAlerts.INFO),
                deepLink = o.optString("deep_link"), dedupeKey = o.optString("dedupe_key"),
                ts = o.optLong("ts"),
            )
        }
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Everything stored, newest first, filters NOT applied. */
    @Synchronized
    fun all(ctx: Context): List<Alert> {
        val arr = runCatching { JSONArray(prefs(ctx).getString(KEY, "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(Alert::of) }
    }

    @Synchronized
    private fun save(ctx: Context, list: List<Alert>) {
        prefs(ctx).edit().putString(KEY, JSONArray().apply { list.take(MAX).forEach { put(it.json()) } }.toString())
            .commit() // commit: the provider call returns "ok" only once it is really kept
    }

    /** Store [a] as raised by [app] (the CALLER's package, never the payload's). */
    @Synchronized
    fun add(ctx: Context, app: String, a: FleetAlerts.Alert, now: Long = System.currentTimeMillis()): Alert {
        val entry = Alert(UUID.randomUUID().toString(), app, a.title, a.text, a.severity, a.deepLink, a.dedupeKey, now)
        val rest = all(ctx).filterNot { a.dedupeKey.isNotBlank() && it.app == app && it.dedupeKey == a.dedupeKey }
        save(ctx, listOf(entry) + rest)
        return entry
    }

    @Synchronized
    fun withdraw(ctx: Context, app: String, dedupeKey: String): Boolean {
        val all = all(ctx)
        val rest = all.filterNot { it.app == app && it.dedupeKey == dedupeKey }
        if (rest.size != all.size) save(ctx, rest)
        return rest.size != all.size
    }

    @Synchronized
    fun remove(ctx: Context, id: String) = save(ctx, all(ctx).filterNot { it.id == id })

    /** Clear all, or only [app]'s. Returns how many went. */
    @Synchronized
    fun clear(ctx: Context, app: String? = null): Int {
        val all = all(ctx)
        val rest = if (app.isNullOrBlank()) emptyList() else all.filterNot { it.app == app }
        save(ctx, rest)
        return all.size - rest.size
    }

    // ── Filters (Notify page ▸ Alerts) ────────────────────────────────────

    fun appMuted(ctx: Context, app: String) = prefs(ctx).getBoolean("mute.app.$app", false)
    fun setAppMuted(ctx: Context, app: String, muted: Boolean) =
        prefs(ctx).edit().putBoolean("mute.app.$app", muted).apply()

    fun severityMuted(ctx: Context, sev: String) = prefs(ctx).getBoolean("mute.sev.$sev", false)
    fun setSeverityMuted(ctx: Context, sev: String, muted: Boolean) =
        prefs(ctx).edit().putBoolean("mute.sev.$sev", muted).apply()

    /** What the shade and the page show: stored minus muted. */
    fun visible(ctx: Context): List<Alert> =
        all(ctx).filterNot { appMuted(ctx, it.app) || severityMuted(ctx, it.severity) }

    /** Every app that has an alert stored — the choices of the app filter. */
    fun apps(ctx: Context): List<String> = all(ctx).map { it.app }.distinct().sorted()
}
