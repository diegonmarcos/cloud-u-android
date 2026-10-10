package com.diegonmarcos.superapp.search

import android.content.Context

/**
 * The scope chips each search entry was last left on ([SearchEntry.id] keys it), so the Home
 * star, the Home swipe sheet and Cloud ▸ Apps each reopen on their own choice. Nothing saved
 * yet is null, and [SearchEntry.selection] then starts the entry on its defaults.
 */
class SearchScopePrefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("search_scope_prefs", Context.MODE_PRIVATE)

    fun load(entry: SearchEntry): Set<String>? =
        if (!sp.contains(key(entry))) null else sp.getStringSet(key(entry), null)?.toSet()

    fun save(entry: SearchEntry, ids: Set<String>) {
        sp.edit().putStringSet(key(entry), ids.toSet()).apply()
    }

    companion object {
        fun key(entry: SearchEntry) = "scopes_${entry.id}"
    }
}
