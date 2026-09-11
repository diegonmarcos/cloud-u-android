package com.diegonmarcos.superapp.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the user's open browser tabs — URL, last-known title, pin
 * state, drag order and group — so all of it survives a restart.
 * Stored as a JSON array in SharedPreferences: small, plain, no dep.
 *
 * Schema: [{"url","title","ts","preview","pinned","order","group"}]
 *
 * The three fields after "preview" are new. They are read with optional
 * defaults so a tab written by the previous version loads unchanged:
 * pinned=false, order=UNSET (falls through to recency), group="". An
 * upgrade therefore does not reshuffle anybody's open tabs.
 *
 * The SharedPreferences file name stays "tabs" — it predates the rename
 * from com.diegonmarcos.superapp.tabs.TabPrefs and users' open-tab lists
 * ride on it.
 */
class BrowserTabPrefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("tabs", Context.MODE_PRIVATE)

    /** In draw order: pinned first, then drag order, then recency. */
    fun all(): List<BrowserTab> = BrowserTabOrder.sort(read())

    private fun read(): List<BrowserTab> {
        val raw = sp.getString(KEY, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val out = ArrayList<BrowserTab>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val u = o.optString("url")
            if (u.isBlank()) continue
            out.add(
                BrowserTab(
                    url         = u,
                    title       = o.optString("title", u),
                    ts          = o.optLong("ts", 0L),
                    previewPath = o.optString("preview", ""),
                    pinned      = o.optBoolean("pinned", false),
                    order       = o.optInt("order", BrowserTab.UNSET_ORDER),
                    group       = o.optString("group", ""),
                )
            )
        }
        return out
    }

    /** Add, or move-to-top if the URL is already open. */
    fun add(url: String, title: String = url): BrowserTab {
        if (url.isBlank()) return BrowserTab("", "", 0L)
        val now = System.currentTimeMillis()
        val existing = read().toMutableList()
        val prior = existing.firstOrNull { it.url == url }
        existing.removeAll { it.url == url }
        // Re-opening a pinned tab keeps it pinned, keeps its slot and
        // keeps its group — an add() must never quietly unpin something.
        val tab = prior?.copy(title = title, ts = now)
            ?: BrowserTab(url, title, now)
        existing.add(0, tab)
        save(existing)
        return tab
    }

    fun updateTitle(url: String, title: String) =
        save(read().map { if (it.url == url) it.copy(title = title) else it })

    fun updatePreview(url: String, path: String) =
        save(read().map { if (it.url == url) it.copy(previewPath = path) else it })

    /**
     * Close a tab — UNLESS IT IS PINNED.
     *
     * That refusal is the whole meaning of item 2. It lives here, at the
     * store, rather than in whichever view drew the ✕: a constraint that
     * only exists in one button's click handler is one new code path away
     * from being gone, and this store is reachable from the grid, the
     * overflow menu and the address bar alike.
     *
     * Unpinning is explicit: setPinned(url, false) first.
     *
     * @return true if the tab was closed, false if it was pinned or absent.
     */
    fun remove(url: String): Boolean {
        val next = BrowserTabOps.close(read(), url) ?: return false
        save(next)
        return true
    }

    /** Pin or unpin. Pinning is what [remove] refuses on. */
    fun setPinned(url: String, pinned: Boolean) =
        save(read().map { if (it.url == url) it.copy(pinned = pinned) else it })

    /** Put a tab in a group, or clear it with "". A tab has at most one. */
    fun setGroup(url: String, group: String) =
        save(read().map { if (it.url == url) it.copy(group = group.trim()) else it })

    /**
     * Persist a drag. [urlsInOrder] is the grid's post-drop order; the
     * index each URL lands on becomes its stored [BrowserTab.order],
     * which is what makes the new arrangement outlive the process.
     */
    fun reorder(urlsInOrder: List<String>) {
        val byUrl = read().associateBy { it.url }
        val moved = urlsInOrder.mapNotNull { byUrl[it] }
        val untouched = read().filterNot { urlsInOrder.contains(it.url) }
        save(BrowserTabOrder.stamp(moved) + untouched)
    }

    /** Collapsed group names — item 3's collapse state, persisted. */
    fun collapsedGroups(): Set<String> =
        sp.getStringSet(KEY_COLLAPSED, emptySet()) ?: emptySet()

    fun setGroupCollapsed(group: String, collapsed: Boolean) {
        val next = collapsedGroups().toMutableSet()
        if (collapsed) next.add(group) else next.remove(group)
        sp.edit().putStringSet(KEY_COLLAPSED, next).apply()
    }

    /**
     * FIRST RUN ONLY. Pin [urls] in the order given, once, ever.
     *
     * The flag is set whether or not anything was seeded, and it is
     * never cleared. That is the difference between a default and an
     * irritation: if he later closes or unpins one of these, the next
     * launch must leave it closed rather than putting it back. Wiping
     * app data is the only way to seed again — which is exactly what a
     * fresh install is.
     *
     * @return the number of tabs actually seeded (0 on every later run).
     */
    fun seedOnce(urls: List<String>): Int {
        val alreadySeeded = sp.getBoolean(KEY_SEEDED, false)
        sp.edit().putBoolean(KEY_SEEDED, true).apply()

        val existing = read().toMutableList()
        val fresh = BrowserSeed.plan(
            alreadySeeded = alreadySeeded,
            openUrls = existing.map { it.url }.toHashSet(),
            configUrls = urls,
            now = System.currentTimeMillis(),
        )
        if (fresh.isEmpty()) return 0
        existing.addAll(fresh)
        save(existing)
        return fresh.size
    }

    /** True once [seedOnce] has run. Exposed so a caller can log the fact. */
    fun seeded(): Boolean = sp.getBoolean(KEY_SEEDED, false)

    fun clear() = save(emptyList())

    fun activeUrl(): String? = sp.getString(KEY_ACTIVE, null)

    fun setActive(url: String?) {
        sp.edit().putString(KEY_ACTIVE, url).apply()
    }

    private fun save(tabs: List<BrowserTab>) {
        val arr = JSONArray()
        for (t in tabs) {
            arr.put(JSONObject().apply {
                put("url",     t.url)
                put("title",   t.title)
                put("ts",      t.ts)
                put("preview", t.previewPath)
                put("pinned",  t.pinned)
                put("order",   t.order)
                put("group",   t.group)
            })
        }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY           = "tabs_json"
        private const val KEY_ACTIVE    = "active_url"
        private const val KEY_SEEDED    = "defaults_seeded_v1"
        private const val KEY_COLLAPSED = "collapsed_groups"
    }
}
