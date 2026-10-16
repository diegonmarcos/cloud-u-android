package com.diegonmarcos.superapp.appstore

import android.content.Context

/**
 * Configs ▸ Sources: which of the Store's declared FEEDS (assets/appstore-feeds.json, read by [FeedViewer]) the owner
 * keeps on. Every feed is on until switched off; the Feed page shows a disabled feed as off (and never reads it), so a
 * switch here is honoured, not decorative. The declaration stays the only list of feeds: this holds ids, never urls.
 * [isOn] is pure over the switched-off set so a test can hold it without a device.
 */
object StoreSourcePrefs {
    private const val PREFS = "store_sources"
    private const val KEY_OFF = "off_feeds"

    fun isOn(off: Set<String>, id: String): Boolean = id !in off

    fun off(ctx: Context): Set<String> = runCatching {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_OFF, emptySet())?.toSet() ?: emptySet()
    }.getOrDefault(emptySet())

    fun enabled(ctx: Context, id: String): Boolean = isOn(off(ctx), id)

    fun set(ctx: Context, id: String, on: Boolean) {
        val next = off(ctx).toMutableSet().apply { if (on) remove(id) else add(id) }
        runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_OFF, next).apply() }
    }
}
