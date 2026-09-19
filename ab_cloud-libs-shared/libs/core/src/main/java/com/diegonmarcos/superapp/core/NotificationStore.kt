package com.diegonmarcos.superapp.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Append-only in-app notification feed. Shared across modules — lives
 * in libs:core so both the app code (CrashLogger) AND libs:updater
 * (PackageInstallerReceiver, UpdateOverlayFragment) can push to it.
 *
 * EXACTLY ONE SURFACE READS IT: AggregatorStackFragment's
 * `notification_center` panel — the Notify page. There used to be a
 * second, the launcher's drop-down NotificationCenterFragment, which
 * drew the same entries and called NotificationManager.cancelAll() on
 * render just as the Notify page does, so whichever opened first
 * destroyed the dismissal state the other would have shown. #515
 * deleted it.
 *
 * ONE EVENT, ONE PRODUCER. App.onCreate used to run a version-bump
 * detector that pushed "Updated to vc:N" on the first launch after an
 * install, for the same install PackageInstallerReceiver had already
 * recorded here under the same source "Updater" — two entries per
 * update. #515 deleted the detector; the install callback, which knows
 * the actual outcome, is the producer.
 *
 * Why not Android's framework notifications? Those go to the system
 * shade and are subject to user channel preferences / DND / etc. This
 * is the SuperApp's *own* feed, surfaced inside the app at any time
 * regardless of OS notification state. Producers that DO post a
 * framework notification (the Updater on install) ALSO push here so
 * the launcher badge count and the in-app list stay in sync.
 */
object NotificationStore {
    private const val PREFS = "notification_store"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 100

    /** Severity hint for future colour-coding. Free-form string so
     *  callers can extend without code changes. */
    object Sev {
        const val INFO  = "info"
        const val WARN  = "warn"
        const val ERROR = "error"
    }

    data class Entry(
        val id: String,
        val ts: Long,
        val source: String,
        val severity: String,
        val title: String,
        val body: String,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("ts", ts)
            put("source", source)
            put("severity", severity)
            put("title", title)
            put("body", body)
        }

        companion object {
            fun fromJson(o: JSONObject): Entry = Entry(
                id       = o.optString("id", UUID.randomUUID().toString()),
                ts       = o.optLong("ts", 0L),
                source   = o.optString("source", ""),
                severity = o.optString("severity", Sev.INFO),
                title    = o.optString("title", ""),
                body     = o.optString("body", ""),
            )
        }
    }

    /** Prepend a new entry. Oldest entries beyond MAX_ENTRIES are
     *  silently dropped — this is a feed, not a log. */
    fun push(
        ctx: Context,
        source: String,
        title: String,
        body: String,
        severity: String = Sev.INFO,
    ) {
        val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = all(ctx).toMutableList()
        current.add(0, Entry(
            id       = UUID.randomUUID().toString(),
            ts       = System.currentTimeMillis(),
            source   = source,
            severity = severity,
            title    = title,
            body     = body,
        ))
        while (current.size > MAX_ENTRIES) current.removeAt(current.size - 1)
        val arr = JSONArray()
        for (e in current) arr.put(e.toJson())
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    /** Newest-first list of all stored entries. */
    fun all(ctx: Context): List<Entry> {
        val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Entry.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun clear(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }
}
