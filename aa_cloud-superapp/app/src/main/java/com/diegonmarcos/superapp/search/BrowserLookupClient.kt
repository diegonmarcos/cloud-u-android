package com.diegonmarcos.superapp.search

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import com.diegonmarcos.superapp.launcher.Sections

/**
 * The SuperApp's side of Cloud Browser's lookup (ac_cloud-browser provider/BrowserLookup): the
 * browser-Fav, browser-History and browser-Web scopes, asked for one query at a time, OFF the
 * main thread. The names below are that contract's (test-one-search.sh holds the two
 * sides equal). The provider is guarded by the fleet's signature permission, which this app holds
 * through libs:core like every fleet app.
 *
 * A Cloud Browser that is not installed, or was built before the lookup, answers nothing: the
 * scopes stay empty and [NOT_AVAILABLE] is said once at the end of the results.
 */
object BrowserLookupClient {
    const val AUTHORITY_SUFFIX = "lookup"
    const val PATH_FAVOURITES = "favourites"
    const val PATH_HISTORY = "history"
    const val PATH_WEB = "web"
    const val PARAM_QUERY = "q"
    const val PARAM_LIMIT = "limit"
    const val COL_KIND = "kind"
    const val COL_TITLE = "title"
    const val COL_URL = "url"
    const val COL_TIME = "time"
    const val KIND_URL = "url"

    /** Rows asked per scope: a search result list, not an export. */
    const val LIMIT = 20

    const val NOT_AVAILABLE = "Cloud Browser isn't installed or is too old: no favourites, history or web search"

    data class Row(val kind: String, val title: String, val url: String, val time: Long)

    /** Cloud Browser's package, from the external_apps row Cloud ▸ Apps' Browser tile opens. */
    fun browserPackage(): String? =
        Sections.externalApp("cloud-browser")?.hubPackage?.takeIf { it.isNotBlank() }

    fun path(kind: String): String? = when (kind) {
        SearchKinds.BROWSER_FAV -> PATH_FAVOURITES
        SearchKinds.BROWSER_HISTORY -> PATH_HISTORY
        SearchKinds.BROWSER_WEB -> PATH_WEB
        else -> null
    }

    /** The rows for [q] at [path], or null when no provider answers (absent or too old). */
    fun query(ctx: Context, pkg: String, path: String, q: String, limit: Int = LIMIT): List<Row>? {
        val authority = "$pkg.$AUTHORITY_SUFFIX"
        if (ctx.packageManager.resolveContentProvider(authority, 0) == null) return null
        val uri = Uri.parse("content://$authority/$path").buildUpon()
            .appendQueryParameter(PARAM_QUERY, q)
            .appendQueryParameter(PARAM_LIMIT, limit.toString())
            .build()
        return runCatching { ctx.contentResolver.query(uri, null, null, null, null)?.use(::rows) }.getOrNull()
    }

    fun rows(c: Cursor): List<Row> {
        val k = c.getColumnIndex(COL_KIND); val t = c.getColumnIndex(COL_TITLE)
        val u = c.getColumnIndex(COL_URL); val w = c.getColumnIndex(COL_TIME)
        if (u < 0) return emptyList()
        val out = ArrayList<Row>(c.count.coerceAtLeast(0))
        while (c.moveToNext()) {
            val url = c.getString(u).orEmpty()
            if (url.isBlank()) continue
            out += Row(if (k >= 0) c.getString(k).orEmpty() else "", if (t >= 0) c.getString(t).orEmpty() else url,
                url, if (w >= 0) c.getLong(w) else 0L)
        }
        return out
    }

    /** Opens [url] in Cloud Browser itself (its VIEW filter), never in another browser. */
    fun open(pkg: String, url: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg)
            .addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The hit a row becomes in [scope] for the query [q]. */
    fun hit(scope: SearchScope, row: Row, q: String, pkg: String, now: Long): SearchHit {
        val open = open(pkg, row.url)
        return when (scope.kind) {
            SearchKinds.BROWSER_WEB ->
                if (row.kind == KIND_URL) SearchHit("Open ${row.title}", "Cloud Browser · ${row.url}", scope.id, intent = open)
                else SearchHit("Search the web for “${q.trim()}”", "Cloud Browser · ${row.title}", scope.id, intent = open)
            else -> SearchHit(row.title.ifBlank { row.url },
                listOf(row.url, ago(row.time, now)).filter { it.isNotBlank() }.joinToString(" · "), scope.id, intent = open)
        }
    }

    /** "today", "yesterday", "3 d ago", "2 mo ago"; blank for no time. */
    fun ago(time: Long, now: Long): String {
        if (time <= 0L) return ""
        val days = ((now - time).coerceAtLeast(0L) / 86_400_000L).toInt()
        return when {
            days == 0 -> "today"
            days == 1 -> "yesterday"
            days < 60 -> "$days d ago"
            else -> "${days / 30} mo ago"
        }
    }

    /** The browser scopes' hits for [q]: what the [SearchSource] answers for them. */
    fun live(ctx: Context, scopes: List<SearchScope>, q: String, now: Long = System.currentTimeMillis()): LiveResult {
        val asked = scopes.filter { path(it.kind) != null }
        if (asked.isEmpty() || q.isBlank()) return LiveResult.EMPTY
        val pkg = browserPackage() ?: return LiveResult(emptyList(), listOf(NOT_AVAILABLE))
        var missing = false
        val hits = asked.flatMap { scope ->
            val rows = query(ctx, pkg, path(scope.kind)!!, q.trim()) ?: run { missing = true; emptyList() }
            rows.map { hit(scope, it, q, pkg, now) }
        }
        return LiveResult(hits, if (missing) listOf(NOT_AVAILABLE) else emptyList())
    }
}
