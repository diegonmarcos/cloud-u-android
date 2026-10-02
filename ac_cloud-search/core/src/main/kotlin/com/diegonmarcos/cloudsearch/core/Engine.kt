package com.diegonmarcos.cloudsearch.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** The one network door. [UrlHttp] is the real one; the JVM suite hands in a local server or a fake. */
interface Http {
    data class Response(val code: Int, val body: String)

    fun get(url: String, headers: Map<String, String>, timeoutMs: Int): Response
    fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): Response
}

/**
 * HttpURLConnection over Android's own resolver, so every lookup goes through the SuperApp's DNS
 * menu (#741: no resolver, DoH client or pinned DNS server of this app's own).
 */
class UrlHttp(private val userAgent: String) : Http {
    override fun get(url: String, headers: Map<String, String>, timeoutMs: Int): Http.Response = call(url, "GET", headers, null, timeoutMs)
    override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): Http.Response = call(url, "POST", headers, body, timeoutMs)

    private fun call(url: String, method: String, headers: Map<String, String>, body: String?, timeoutMs: Int): Http.Response {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Accept", "application/json, application/rss+xml, text/xml;q=0.9, */*;q=0.5")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return Http.Response(code, text)
        } finally {
            c.disconnect()
        }
    }
}

/** Raw responses on disk, keyed by what was asked: the offline last-results view and the TTL both read it. */
class Cache(private val dir: File) {
    data class Entry(val body: String, val at: Long)

    fun put(key: String, body: String, at: Long) {
        dir.mkdirs()
        File(dir, name(key)).writeText(JSONObject().put("at", at).put("body", body).toString())
    }

    fun get(key: String): Entry? = runCatching {
        val o = JSONObject(File(dir, name(key)).readText())
        Entry(o.getString("body"), o.getLong("at"))
    }.getOrNull()

    private fun name(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } + ".json"
}

/** {q}, {city}, {lat}, {lon}, {radius} in a declared URL, each value URL-encoded. */
object Templates {
    fun fill(template: String, q: String, city: SearchConfig.City): String = template
        .replace("{q}", enc(q))
        .replace("{city}", enc(city.label))
        .replace("{lat}", city.lat.toString())
        .replace("{lon}", city.lon.toString())
        .replace("{radius}", city.radiusKm.toString())

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

/**
 * Asks every source a vertical declares, through the cache: a fresh entry (under the TTL) is used
 * as is, otherwise the source is fetched; a failed fetch falls back to the last stored answer and
 * says it is stale. Disabled sources and links are reported, never fetched.
 */
class SearchEngine(val cfg: SearchConfig, private val http: Http, private val cache: Cache, private val clock: () -> Long) {

    enum class State { OK, CACHED, STALE, ERROR, SKIPPED, LINK, DISABLED }

    data class SourceStatus(val id: String, val label: String, val state: State, val count: Int, val detail: String, val at: Long?, val link: String?)
    data class QueryResult(val vertical: String, val q: String, val city: String, val listings: List<Listing>, val statuses: List<SourceStatus>)
    data class FeedResult(val items: List<FeedItem>, val fetched: Int, val statuses: List<SourceStatus>)

    private data class Fetched(val body: String?, val state: State, val detail: String, val at: Long?)

    private fun fetch(key: String, url: String, headers: Map<String, String>, timeoutMs: Int): Fetched {
        val now = clock()
        val hit = cache.get(key)
        if (hit != null && now - hit.at < cfg.cacheTtlMinutes * 60_000L) return Fetched(hit.body, State.CACHED, "", hit.at)
        val res = runCatching { http.get(url, headers, timeoutMs) }
        val r = res.getOrNull()
        if (r != null && r.code in 200..299) {
            cache.put(key, r.body, now)
            return Fetched(r.body, State.OK, "", now)
        }
        val why = r?.let { "HTTP ${it.code}" } ?: (res.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName } ?: "no answer")
        return if (hit != null) Fetched(hit.body, State.STALE, why, hit.at) else Fetched(null, State.ERROR, why, null)
    }

    fun query(verticalId: String, q: String, cityId: String?): QueryResult {
        val v = cfg.vertical(verticalId) ?: throw IllegalArgumentException("no vertical '$verticalId'")
        val city = cfg.city(cityId)
        val query = q.trim()
        val listings = LinkedHashMap<String, Listing>()
        val statuses = v.sources.mapNotNull { cfg.sources[it] }.map { s ->
            when {
                !s.fetches -> notFetched(s, query, city)
                s.queryRequired && query.isEmpty() -> SourceStatus(s.id, s.label, State.SKIPPED, 0, "needs a search term", null, null)
                else -> {
                    val url = Templates.fill(s.url, query, city)
                    val f = fetch("src|${s.id}|$url", url, s.headers, cfg.timeoutMs)
                    if (f.body == null) SourceStatus(s.id, s.label, f.state, 0, f.detail, f.at, null)
                    else runCatching { Parsers.parse(s.parser, f.body, s) }.fold(
                        onSuccess = { parsed ->
                            val kept = parsed
                                .filter { !s.localFilter || query.isEmpty() || Filters.textMatch(it, query) }
                                .filter { !s.cityFilter || Filters.cityMatch(it, city) }
                            kept.forEach { listings.putIfAbsent(it.key, it) }
                            SourceStatus(s.id, s.label, f.state, kept.size, f.detail, f.at, null)
                        },
                        onFailure = { SourceStatus(s.id, s.label, State.ERROR, 0, "unreadable answer: ${it.message}", f.at, null) },
                    )
                }
            }
        }
        return QueryResult(v.id, query, city.id, listings.values.take(cfg.maxResults), statuses)
    }

    /** A source this app never fetches: a link to the site's own search, or disabled with its reason. */
    private fun notFetched(s: SearchConfig.Source, q: String, city: SearchConfig.City): SourceStatus =
        if (s.kind == SearchConfig.KIND_LINK && s.enabled) SourceStatus(s.id, s.label, State.LINK, 0, s.why, null, Templates.fill(s.url, q, city))
        else SourceStatus(s.id, s.label, State.DISABLED, 0, s.why, null, null)

    /** The statuses of the sources a vertical lists but never fetches — no network, safe on any thread. */
    fun unfetched(verticalId: String, q: String, cityId: String?): List<SourceStatus> {
        val v = cfg.vertical(verticalId) ?: return emptyList()
        val city = cfg.city(cityId)
        return v.sources.mapNotNull { cfg.sources[it] }.filter { !it.fetches }.map { notFetched(it, q.trim(), city) }
    }

    /** The vertical's feeds, newest first, narrowed to its declared keywords. */
    fun feed(verticalId: String): FeedResult {
        val v = cfg.vertical(verticalId) ?: throw IllegalArgumentException("no vertical '$verticalId'")
        val all = mutableListOf<FeedItem>()
        val statuses = v.feeds.mapNotNull { cfg.feeds[it] }.map { feed ->
            val f = fetch("feed|${feed.id}", feed.url, emptyMap(), cfg.timeoutMs)
            if (f.body == null) SourceStatus(feed.id, feed.label, f.state, 0, f.detail, f.at, null)
            else runCatching { Parsers.rss(f.body, feed.label) }.fold(
                onSuccess = { all += it; SourceStatus(feed.id, feed.label, f.state, it.size, f.detail, f.at, null) },
                onFailure = { SourceStatus(feed.id, feed.label, State.ERROR, 0, "unreadable feed: ${it.message}", f.at, null) },
            )
        }
        val kept = all.filter { item -> v.feedKeywords.isEmpty() || v.feedKeywords.any { k -> (item.title + " " + item.text).contains(k, ignoreCase = true) } }
            .distinctBy { it.url ?: it.title }
            .sortedByDescending { it.date ?: 0L }
        return FeedResult(kept, all.size, statuses)
    }

    /** Market analysis for a vertical whose `analysis` is declared, computed from its live sources only. */
    fun analysis(verticalId: String, q: String, cityId: String?): Analysis.Result? {
        val v = cfg.vertical(verticalId) ?: return null
        if (v.analysis != "jobs") return null
        val r = query(verticalId, q, cityId)
        val ba = v.sources.mapNotNull { cfg.sources[it] }.firstOrNull { it.fetches && it.parser == "ba" }
        val baBody = ba?.let { s -> cache.get("src|${s.id}|${Templates.fill(s.url, r.q, cfg.city(cityId))}")?.body }
        return Analysis.jobs(r, baBody)
    }

    /** Statuses as JSON, for the debug API and the offline view's banner. */
    fun statusJson(s: List<SourceStatus>): JSONArray = JSONArray(s.map {
        JSONObject().put("id", it.id).put("label", it.label).put("state", it.state.name.lowercase())
            .put("count", it.count).put("detail", it.detail).put("at", it.at ?: JSONObject.NULL).put("link", it.link ?: JSONObject.NULL)
    })
}
