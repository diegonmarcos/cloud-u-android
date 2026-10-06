package com.diegonmarcos.superapp.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the user's open browser tabs — URL, last-known title, pin
 * state, drag order and group — so all of it survives a restart.
 * Stored as a JSON array in SharedPreferences: small, plain, no dep.
 *
 * Schema: [{"url","title","ts","preview","pinned","order","group","private","id","icon"}] — see
 * [BrowserTabStore]; `url` is the tab's last COMMITTED page, `id` its stable identity (#886).
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

    private fun read(): List<BrowserTab> = BrowserTabStore.parse(sp.getString(KEY, "[]"))

    /** Add, or move-to-top if the URL is already open. #802 [isPrivate] applies to a NEW tab only. */
    fun add(url: String, title: String = url, isPrivate: Boolean = false): BrowserTab {
        if (url.isBlank()) return BrowserTab("", "", 0L)
        val now = System.currentTimeMillis()
        val existing = read().toMutableList()
        val prior = existing.firstOrNull { it.url == url }
        if (prior != null) existing.removeAll { it.key == prior.key }
        // Re-opening a pinned tab keeps it pinned, keeps its slot and
        // keeps its group — an add() must never quietly unpin something.
        val tab = prior?.copy(title = title, ts = now)
            ?: BrowserTab(url, title, now, isPrivate = isPrivate, id = BrowserTabStore.newId())
        existing.add(0, tab)
        save(existing)
        return tab
    }

    /**
     * #886 ALWAYS A NEW TAB, even when the url is already open in another one (the strip's + and a
     * link opened "in a new tab"): tabs are told apart by [BrowserTab.id], not by url.
     */
    fun addNew(url: String, title: String = url, isPrivate: Boolean = false, group: String = ""): BrowserTab {
        val tab = BrowserTab(url, title, System.currentTimeMillis(), isPrivate = isPrivate, group = group,
            id = BrowserTabStore.newId())
        save(listOf(tab) + read())
        return tab
    }

    fun byId(id: String?): BrowserTab? = if (id == null) null else read().firstOrNull { it.key == id }

    /**
     * #886 THE MISSING WRITE: the tab [id] now shows [url] (a committed page, not the one it was
     * opened with). Everything that brings a tab back after a restart reads this. [BrowserTabStore.commit]
     * refuses blank/about/data urls so a navigation that never committed cannot erase a good one.
     */
    fun commit(id: String, url: String?, title: String? = null) {
        val cur = read()
        val next = BrowserTabStore.commit(cur, id, url, title)
        if (next != cur) save(next)
    }

    fun updateTitle(url: String, title: String) =
        save(read().map { if (it.url == url) it.copy(title = title) else it })

    fun updateTitleById(id: String, title: String) =
        save(read().map { if (it.key == id) it.copy(title = title, id = it.key) else it })

    fun setIcon(id: String, path: String) =
        save(read().map { if (it.key == id) it.copy(iconPath = path, id = it.key) else it })

    fun updatePreviewById(id: String, path: String) =
        save(read().map { if (it.key == id) it.copy(previewPath = path, id = it.key) else it })

    /** Put the tab [id] in [group] ("" clears it). */
    fun setGroupById(id: String, group: String) =
        save(read().map { if (it.key == id) it.copy(group = group.trim(), id = it.key) else it })

    /** Close by id, honouring the pin exactly like [remove]. */
    fun removeById(id: String): Boolean {
        val t = byId(id) ?: return false
        if (t.pinned) return false
        save(read().filterNot { it.key == id })
        return true
    }

    // ── #886 group colours + the regroup rules (BrowserTabGroups is the logic; this is the store) ──

    fun groupColors(): Map<String, Int> {
        val o = runCatching { JSONObject(sp.getString(KEY_GROUP_COLORS, "{}") ?: "{}") }.getOrDefault(JSONObject())
        return o.keys().asSequence().associateWith { o.optInt(it) }
    }

    private fun saveColors(m: Map<String, Int>) {
        val o = JSONObject(); m.forEach { (k, v) -> o.put(k, v) }
        sp.edit().putString(KEY_GROUP_COLORS, o.toString()).apply()
    }

    /** Persist the outcome of a [BrowserTabGroups] rule, carrying the collapsed flag across a rename. */
    private fun apply(c: BrowserTabGroups.Change?, renamedFrom: String? = null): BrowserTabGroups.Change? {
        c ?: return null
        save(c.tabs); saveColors(c.colors)
        if (renamedFrom != null && renamedFrom != c.group && renamedFrom in collapsedGroups()) {
            setGroupCollapsed(renamedFrom, false); setGroupCollapsed(c.group, true)
        }
        return c
    }

    fun dropOnTab(dragId: String, targetId: String) =
        apply(BrowserTabGroups.dropOnTab(read(), groupColors(), dragId, targetId))

    fun dropOnGroup(dragId: String, group: String) =
        apply(BrowserTabGroups.dropOnGroup(read(), groupColors(), dragId, group))

    /** The strip's +: [freshId] joins [currentId]'s group, or starts one with it. */
    fun startOrJoinGroup(currentId: String, freshId: String) =
        apply(BrowserTabGroups.startOrJoin(read(), groupColors(), currentId, freshId))

    fun renameGroup(from: String, to: String, color: Int? = null) =
        apply(BrowserTabGroups.rename(read(), groupColors(), from, to, color), renamedFrom = from)

    fun ungroup(group: String) = apply(BrowserTabGroups.ungroup(read(), groupColors(), group))

    /** #802 clear-data "previews": forget every captured tab preview. */
    fun clearPreviews() = save(read().map { it.copy(previewPath = "") })

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

    fun setPinnedById(id: String, pinned: Boolean) =
        save(read().map { if (it.key == id) it.copy(pinned = pinned, id = it.key) else it })

    /** Put a tab in a group, or clear it with "". A tab has at most one. */
    fun setGroup(url: String, group: String) =
        save(read().map { if (it.url == url) it.copy(group = group.trim()) else it })

    /**
     * Persist a drag. [keysInOrder] is the grid's post-drop order of tab keys; the
     * index each lands on becomes its stored [BrowserTab.order],
     * which is what makes the new arrangement outlive the process.
     */
    fun reorder(keysInOrder: List<String>) {
        val byKey = read().associateBy { it.key }
        val moved = keysInOrder.mapNotNull { byKey[it] }
        val untouched = read().filterNot { keysInOrder.contains(it.key) }
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

    /** The tab that was last open: by id, else (a store from before ids) by url. */
    fun activeTab(): BrowserTab? =
        BrowserTabStore.active(read(), sp.getString(KEY_ACTIVE_ID, null), sp.getString(KEY_ACTIVE, null))

    fun activeId(): String? = activeTab()?.key

    /** The active tab's CURRENT url (what it last committed), never the url it was opened with. */
    fun activeUrl(): String? = activeTab()?.url ?: sp.getString(KEY_ACTIVE, null)

    fun setActive(url: String?) {
        val t = url?.let { u -> read().firstOrNull { it.url == u } }
        sp.edit().putString(KEY_ACTIVE, url).putString(KEY_ACTIVE_ID, t?.key).apply()
    }

    fun setActiveId(id: String?) {
        val t = byId(id)
        sp.edit().putString(KEY_ACTIVE_ID, t?.key).putString(KEY_ACTIVE, t?.url).apply()
    }

    private fun save(tabs: List<BrowserTab>) {
        sp.edit().putString(KEY, BrowserTabStore.serialize(tabs)).apply()
    }

    companion object {
        private const val KEY           = "tabs_json"
        private const val KEY_ACTIVE    = "active_url"
        private const val KEY_ACTIVE_ID = "active_id"
        private const val KEY_GROUP_COLORS = "group_colors"
        private const val KEY_SEEDED    = "defaults_seeded_v1"
        private const val KEY_COLLAPSED = "collapsed_groups"
    }
}
