package com.diegonmarcos.superapp.browser

import org.json.JSONObject

/** One link the app ships as a favourite; [group] becomes the bookmark's folder ("Public", "Private (mesh)"). */
data class FavSeedItem(val title: String, val url: String, val group: String)

/** The app's declared favourites (build.json::ui.browser.favourites, expanded at build time). */
data class FavSeed(val version: Int, val items: List<FavSeedItem>) {
    companion object {
        val EMPTY = FavSeed(0, emptyList())

        fun parse(o: JSONObject?): FavSeed {
            o ?: return EMPTY
            val arr = o.optJSONArray("items") ?: return EMPTY
            val items = (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = e.optString("url").trim()
                if (url.isEmpty()) null else FavSeedItem(e.optString("title", url).ifBlank { url }, url, e.optString("group"))
            }.distinctBy { it.url }
            return FavSeed(o.optInt("version", 1), items)
        }
    }
}

/**
 * #893 Fav = the bookmarks, one store. The pure rules: the seed is applied ONCE per entry (first run, and
 * any entry a later seed version adds), never overwriting what the user edited and never re-adding what they
 * deleted (an entry applied once is remembered in `seen`); the view (list|grid) is a persisted choice.
 */
object BrowserFavourites {
    const val VIEW_LIST = "list"
    const val VIEW_GRID = "grid"

    fun normView(v: String?): String = if (v == VIEW_GRID) VIEW_GRID else VIEW_LIST
    fun toggled(v: String?): String = if (normView(v) == VIEW_GRID) VIEW_LIST else VIEW_GRID

    /** @return the new bookmark list and the new `seen` set. Entries already present or already seen are skipped. */
    fun merge(list: List<BrowserBookmark>, seed: FavSeed, seen: Set<String>, now: Long): Pair<List<BrowserBookmark>, Set<String>> {
        var out = list
        for (it in seed.items) {
            if (it.url in seen) continue
            if (out.none { b -> b.url == it.url }) out = out + BrowserBookmark(it.url, it.title, BrowserBookmarkOps.normFolder(it.group), now)
        }
        return out to (seen + seed.items.map { it.url })
    }

    /** The tile's letter when there is no favicon. */
    fun letter(title: String, url: String): String =
        (title.trim().ifEmpty { url.substringAfter("://").trim() }).firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

    /** Folder → bookmarks, top level ("") first, the rest sorted: what both views draw. */
    fun sections(list: List<BrowserBookmark>): List<Pair<String, List<BrowserBookmark>>> =
        list.groupBy { it.folder }.toList().sortedWith(compareBy({ it.first.isNotEmpty() }, { it.first }))

    // ── search (the Fav page's live filter and the address bar's Favorites section share these) ──

    private fun tokens(q: String): List<String> = q.lowercase().split(' ', '\t').filter { it.isNotEmpty() }

    /** Every whitespace-separated word of [q] occurs, case-insensitive, in the title or the address. An empty query matches all. */
    fun matches(b: BrowserBookmark, q: String): Boolean {
        val hay = (b.title + " " + b.url).lowercase()
        return tokens(q).all { hay.contains(it) }
    }

    /** [sections] narrowed to the bookmarks that match [q]; a folder left with nothing is dropped. */
    fun filter(sections: List<Pair<String, List<BrowserBookmark>>>, q: String): List<Pair<String, List<BrowserBookmark>>> =
        if (tokens(q).isEmpty()) sections
        else sections.mapNotNull { (f, items) -> items.filter { matches(it, q) }.takeIf { it.isNotEmpty() }?.let { f to it } }

    /**
     * The address bar's Favorites section: the matching bookmarks best match first (host or title start beats
     * a substring, see [BrowserSuggest.matchScore]), ties keep the list's order, at most [limit]; a URL in
     * [skip] (already shown above) is left out so a page is never offered twice.
     */
    fun suggest(list: List<BrowserBookmark>, q: String, limit: Int, skip: Set<String> = emptySet()): List<BrowserBookmark> {
        val ts = tokens(q)
        if (ts.isEmpty() || limit <= 0) return emptyList()
        return list.filter { it.url !in skip && matches(it, q) }
            .sortedByDescending { b -> ts.sumOf { BrowserSuggest.matchScore(it, b.url, b.title) ?: 10.0 } / ts.size }
            .distinctBy { it.url }.take(limit)
    }
}
