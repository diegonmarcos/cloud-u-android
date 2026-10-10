package com.diegonmarcos.cloudbrowser.provider

import com.diegonmarcos.superapp.browser.BrowserBookmark
import com.diegonmarcos.superapp.browser.BrowserFavourites
import com.diegonmarcos.superapp.browser.BrowserSearch
import com.diegonmarcos.superapp.browser.BrowserSearchEngine
import com.diegonmarcos.superapp.browser.BrowserSuggest
import com.diegonmarcos.superapp.browser.BrowserTab
import com.diegonmarcos.superapp.browser.BrowserVisit

/**
 * THE FLEET LOOKUP INTO THE BROWSER'S OWN PAGES, as a contract with no Android in it so a plain
 * JVM test holds it. The SuperApp's search (its browser-Fav, browser-History and browser-Web
 * scopes) reads it through [BrowserLookupProvider], `content://<applicationId>.lookup`:
 *
 *  - `/favourites?q=…&limit=…`  the bookmarks matching q (title + url + when added), best first;
 *  - `/history?q=…&limit=…`     the visited pages matching q (title + url + last visit), best first;
 *  - `/web?q=…`                 one row: what the address bar would do with q — the URL itself when
 *                               q is a URL or a domain, else the default engine's search URL.
 *
 * Read-only, text in and at most [MAX_LIMIT] rows out; no tab, cookie, form or setting is ever
 * served. Guarded by the constellation's SIGNATURE permission ([PERMISSION]) in the manifest,
 * re-checked in the provider ([allowed]), the way AgentFetchProvider and Cloud Account's
 * accountdata provider are.
 *
 * INCOGNITO NEVER LEAVES. History is only what [com.diegonmarcos.superapp.browser.BrowserHistory]
 * recorded, and a private tab is never recorded there (PrivateSession.visit); on top of that a
 * row whose URL is open in a private tab right now is dropped ([history] `privateTabs`), so not
 * even a page visited normally earlier can tell a caller what the private tabs are showing.
 */
object BrowserLookup {
    const val PERMISSION = "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"
    const val AUTHORITY_SUFFIX = "lookup"

    const val PATH_FAVOURITES = "favourites"
    const val PATH_HISTORY = "history"
    const val PATH_WEB = "web"

    const val PARAM_QUERY = "q"
    const val PARAM_LIMIT = "limit"

    /** The cursor's columns, in this order. [COL_TIME] is epoch ms (added / last visit; 0 for web). */
    const val COL_KIND = "kind"
    const val COL_TITLE = "title"
    const val COL_URL = "url"
    const val COL_TIME = "time"
    val COLUMNS = arrayOf(COL_KIND, COL_TITLE, COL_URL, COL_TIME)

    const val KIND_FAVOURITE = "favourite"
    const val KIND_HISTORY = "history"
    /** A web row that opens the URL that was typed. */
    const val KIND_URL = "url"
    /** A web row that searches the default engine. */
    const val KIND_SEARCH = "search"

    const val DEFAULT_LIMIT = 20
    const val MAX_LIMIT = 50
    const val MAX_QUERY = 200

    data class Row(val kind: String, val title: String, val url: String, val time: Long)

    /** A fleet app (the signature permission), or this browser itself. Nobody else. */
    fun allowed(callingUid: Int, myUid: Int, callerHoldsPermission: Boolean): Boolean =
        callingUid == myUid || callerHoldsPermission

    /** The asked limit, kept to 1..[MAX_LIMIT]; absent or unreadable is [DEFAULT_LIMIT]. */
    fun limit(raw: String?): Int = raw?.trim()?.toIntOrNull()?.coerceIn(1, MAX_LIMIT) ?: DEFAULT_LIMIT

    /** The query, trimmed and cut to [MAX_QUERY]; blank is no query (no rows). */
    fun query(raw: String?): String = raw?.trim().orEmpty().take(MAX_QUERY)

    fun favourites(list: List<BrowserBookmark>, q: String, limit: Int): List<Row> =
        if (q.isBlank()) emptyList()
        else BrowserFavourites.suggest(list, q, limit).map { Row(KIND_FAVOURITE, it.title.ifBlank { it.url }, it.url, it.ts) }

    /**
     * Visited pages matching [q], ranked the way the address bar ranks them (how well, then how
     * recent). [privateTabs] is the browser's open tab list: any URL a private tab holds is left out.
     */
    fun history(visits: List<BrowserVisit>, privateTabs: List<BrowserTab>, q: String, limit: Int, now: Long): List<Row> {
        if (q.isBlank()) return emptyList()
        val needle = q.lowercase()
        val hidden = privateTabs.filter { it.isPrivate }.map { it.url }.toSet()
        return visits.asSequence()
            .filter { it.url !in hidden }
            .mapNotNull { v -> BrowserSuggest.matchScore(needle, v.url, v.title)?.let { (it + BrowserSuggest.recency(v.ts, now)) to v } }
            .sortedByDescending { it.first }
            .map { it.second }
            .distinctBy { it.url }
            .take(limit)
            .map { Row(KIND_HISTORY, it.title.ifBlank { it.url }, it.url, it.ts) }
            .toList()
    }

    /** What the address bar does with [q] ([BrowserSearch.resolve]): open it, or search [engine] for it. */
    fun web(q: String, engine: BrowserSearchEngine): Row? {
        if (q.isBlank()) return null
        return if (BrowserSearch.isUrlLike(q)) Row(KIND_URL, q.trim(), BrowserSearch.normalizeUrl(q), 0L)
        else Row(KIND_SEARCH, engine.label, BrowserSearch.searchUrl(q, engine), 0L)
    }
}
