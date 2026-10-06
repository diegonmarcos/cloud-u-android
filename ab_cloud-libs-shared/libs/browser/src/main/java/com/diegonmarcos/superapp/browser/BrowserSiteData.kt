package com.diegonmarcos.superapp.browser

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * #887 site data, the pure half (JVM-tested in BrowserSiteDataTest): which URLs belong to "this site"
 * when saving it offline, when a crawl must stop, how a saved copy is indexed and sized, and which
 * cookies belong to one site. The android half is [OfflineSites] and [BrowserStorage].
 */
object SiteScope {

    /** `https://example.org:8443` — scheme, lower-case host, a non-default port; "" for anything that is not http(s). */
    fun origin(url: String?): String {
        val u = runCatching { URI((url ?: "").trim()) }.getOrNull() ?: return ""
        val scheme = u.scheme?.lowercase() ?: return ""
        val host = u.host?.lowercase() ?: return ""
        if (scheme != "http" && scheme != "https") return ""
        val port = u.port
        val default = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
        return "$scheme://$host" + if (port == -1 || default) "" else ":$port"
    }

    fun sameOrigin(a: String?, b: String?): Boolean = origin(a).isNotEmpty() && origin(a) == origin(b)

    /** [url] without its fragment, the origin canonicalised; null for what is not an http(s) page address. */
    fun normalize(url: String?): String? {
        val raw = (url ?: "").trim()
        val o = origin(raw)
        if (o.isEmpty()) return null
        val u = runCatching { URI(raw) }.getOrNull() ?: return null
        val path = (u.rawPath ?: "").ifEmpty { "/" }
        return o + path + (u.rawQuery?.let { "?$it" } ?: "")
    }

    private val BINARY = setOf("zip", "gz", "tar", "7z", "rar", "apk", "exe", "dmg", "iso", "mp3", "mp4", "mkv", "avi", "mov", "webm",
        "wav", "flac", "ogg", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "jpg", "jpeg", "png", "gif", "webp", "svg", "ico", "css", "js", "json", "xml", "woff", "woff2", "ttf")

    /** Looks like a page, not a download or an asset (those are inside the archive already, or not wanted). */
    fun isPage(url: String): Boolean {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty().lowercase()
        val ext = path.substringAfterLast('/').substringAfterLast('.', "")
        return ext !in BINARY
    }

    /** The links of [candidates] a crawl of [base]'s site may follow: same origin, pages only, normalised, no repeats. */
    fun inScope(base: String, candidates: List<String>): List<String> =
        candidates.mapNotNull { normalize(it) }.filter { sameOrigin(base, it) && isPage(it) }.distinct()
}

/** How far and how big a site save may go (Configs ▸ Data & storage). */
data class CrawlLimits(val depth: Int, val maxPages: Int, val maxBytes: Long) {
    companion object {
        fun of(depth: Int?, maxPages: Int?, maxMb: Int?) =
            CrawlLimits((depth ?: 1).coerceIn(0, 3), (maxPages ?: 25).coerceIn(1, 200), (maxMb ?: 50).coerceIn(5, 500) * 1024L * 1024L)
    }
}

/**
 * The queue of one site save: breadth-first from [start], never leaving [start]'s origin, never past
 * [CrawlLimits.depth] links from it, never revisiting a page, stopping at the page or size limit.
 * The driver asks [next], saves that page, reports [saved], and offers the links it found.
 */
class SiteCrawl(start: String, val limits: CrawlLimits) {
    data class Item(val url: String, val depth: Int)

    private val startUrl = SiteScope.normalize(start) ?: ""
    private val queue = ArrayDeque<Item>()
    private val seen = HashSet<String>()
    var pages = 0; private set
    var bytes = 0L; private set
    /** Why the crawl ended early: null while it may go on or when it simply ran out of pages. */
    var stopReason: String? = null; private set

    init { if (startUrl.isNotEmpty()) { queue.add(Item(startUrl, 0)); seen.add(startUrl) } }

    /** The next page to save, or null when there is none (queue empty, or a limit reached). */
    fun next(): Item? {
        if (pages >= limits.maxPages) { stopReason = "page limit (${limits.maxPages})"; return null }
        if (bytes >= limits.maxBytes) { stopReason = "size limit (${SiteData.human(limits.maxBytes)})"; return null }
        return queue.removeFirstOrNull()
    }

    fun saved(size: Long) { pages++; bytes += size.coerceAtLeast(0) }

    /** Links found on a page at [depth]: in-scope, unseen ones join the queue while depth allows. */
    fun offer(depth: Int, links: List<String>): Int {
        if (depth >= limits.depth) return 0
        var added = 0
        for (l in SiteScope.inScope(startUrl, links)) if (seen.add(l)) { queue.add(Item(l, depth + 1)); added++ }
        return added
    }

    val pending: Int get() = queue.size
}

object SiteData {
    /** 1536 -> `1.5 KB`; the unit a person reads, never raw bytes. */
    fun human(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB")
        var v = bytes / 1024.0; var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return String.format(java.util.Locale.US, "%.1f %s", v, units[i])
    }

    /** One row of the storage breakdown. [bytes] null = not measurable (shown as "—"). */
    data class Item(val id: String, val label: String, val bytes: Long?, val detail: String = "")

    /** The sum of the measurable items. */
    fun total(items: List<Item>): Long = items.sumOf { it.bytes ?: 0L }

    /** The ids a "clear everything" covers: every item. Kept as a function so the list cannot drift from the breakdown. */
    fun everything(items: List<Item>): Set<String> = items.map { it.id }.toSet()
}

data class OfflinePage(val url: String, val file: String, val bytes: Long, val title: String)

data class OfflineSite(
    val id: String, val title: String, val origin: String, val startUrl: String, val ts: Long,
    val pages: List<OfflinePage>, val kind: String = "site", val stopped: String = "",
) {
    val bytes: Long get() = pages.sumOf { it.bytes }
}

/** The index of saved copies, as JSON, and the lookups the viewer needs. */
object OfflineIndex {
    fun toJson(sites: List<OfflineSite>): String = JSONArray().also { a ->
        sites.forEach { s ->
            a.put(JSONObject().put("id", s.id).put("title", s.title).put("origin", s.origin).put("start", s.startUrl)
                .put("ts", s.ts).put("kind", s.kind).put("stopped", s.stopped)
                .put("pages", JSONArray().also { p ->
                    s.pages.forEach { p.put(JSONObject().put("url", it.url).put("file", it.file).put("bytes", it.bytes).put("title", it.title)) }
                }))
        }
    }.toString()

    fun fromJson(raw: String?): List<OfflineSite> {
        val a = runCatching { JSONArray(raw ?: "[]") }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifBlank { return@mapNotNull null }
            val pa = o.optJSONArray("pages") ?: JSONArray()
            val pages = (0 until pa.length()).mapNotNull { j ->
                pa.optJSONObject(j)?.let { OfflinePage(it.optString("url"), it.optString("file"), it.optLong("bytes"), it.optString("title")) }
            }
            OfflineSite(id, o.optString("title"), o.optString("origin"), o.optString("start"), o.optLong("ts"), pages,
                o.optString("kind", "site"), o.optString("stopped"))
        }.sortedByDescending { it.ts }
    }

    fun remove(sites: List<OfflineSite>, id: String) = sites.filterNot { it.id == id }

    /** The saved file of [url] inside [site] (the url compared normalised, fragment ignored), or null. */
    fun pageFor(site: OfflineSite, url: String): OfflinePage? {
        val n = SiteScope.normalize(url) ?: return null
        return site.pages.firstOrNull { SiteScope.normalize(it.url) == n }
    }

    /** The saved copy that holds [url], across every site. */
    fun find(sites: List<OfflineSite>, url: String): Pair<OfflineSite, OfflinePage>? =
        sites.firstNotNullOfOrNull { s -> pageFor(s, url)?.let { s to it } }
}

/** Cookies of one site: Android's CookieManager has no per-site clear, so the site's cookies are expired by name. */
object CookieScope {
    /** The cookie NAMES in a `Cookie:` header value (`a=1; b=2`). */
    fun names(header: String?): List<String> =
        (header ?: "").split(';').map { it.trim().substringBefore('=').trim() }.filter { it.isNotEmpty() }.distinct()

    /** [host] and its parent domains that could have set a cookie for it (down to two labels): `a.b.example.org` -> a.b.example.org, b.example.org, example.org. */
    fun hosts(host: String): List<String> {
        val parts = host.lowercase().trim('.').split('.').filter { it.isNotEmpty() }
        if (parts.size < 2) return parts.joinToString(".").takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList()
        return (0..parts.size - 2).map { parts.drop(it).joinToString(".") }
    }

    /** (url to set on, Set-Cookie string) pairs that expire cookie [name] for [host]: host-only and Domain= variants. */
    fun expiries(host: String, names: List<String>, secure: Boolean = true): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val attrs = "; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/" + if (secure) "; Secure" else ""
        for (h in hosts(host)) for (n in names) {
            val url = (if (secure) "https://" else "http://") + h + "/"
            out.add(url to "$n=$attrs")
            out.add(url to "$n=$attrs; Domain=$h")
            out.add(url to "$n=$attrs; Domain=.$h")
        }
        return out
    }
}
