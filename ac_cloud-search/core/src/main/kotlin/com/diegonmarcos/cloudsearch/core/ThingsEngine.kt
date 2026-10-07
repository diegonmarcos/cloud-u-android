package com.diegonmarcos.cloudsearch.core

import com.diegonmarcos.cloudsearch.core.SearchEngine.SourceStatus
import com.diegonmarcos.cloudsearch.core.SearchEngine.State
import org.json.JSONArray
import org.json.JSONObject

/**
 * #903 The Things comparison: the stores of the item's kind in the area (OpenStreetMap, cached per
 * area), the shelf prices Open Prices holds for them, and the online prices the fleet's price service
 * reads from the chains' own sites. Each source goes through the same cache and offline rules as the
 * rest of the app: fresh, cached, stale with its date, or an error with its reason. The one network
 * door is [Http]; [bearer] is the fleet sign-in the price service needs (null = not signed in).
 */
class ThingsEngine(
    private val cfg: SearchConfig,
    private val http: Http,
    private val cache: Cache,
    private val clock: () -> Long,
    private val bearer: () -> String?,
) {
    private val t: Things.Config get() = cfg.things ?: throw IllegalStateException("build.json::search.things is not declared")

    data class Result(
        val q: String, val area: Things.Area, val category: Things.Category, val matched: Boolean,
        val storesFound: Int, val rows: List<Things.Row>, val statuses: List<SourceStatus>,
    )

    private data class Fetched(val body: String?, val state: State, val detail: String, val at: Long?)

    private fun fetch(key: String, url: String, headers: Map<String, String>, ttlMs: Long): Fetched {
        val now = clock()
        val hit = cache.get(key)
        if (hit != null && now - hit.at < ttlMs) return Fetched(hit.body, State.CACHED, "", hit.at)
        val res = runCatching { http.get(url, headers, cfg.timeoutMs) }
        val r = res.getOrNull()
        if (r != null && r.code in 200..299) {
            cache.put(key, r.body, now)
            return Fetched(r.body, State.OK, "", now)
        }
        val why = when (r?.code) {
            null -> res.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName } ?: "no answer"
            401, 403 -> "HTTP ${r.code}: not authorised"
            else -> "HTTP ${r.code}"
        }
        return if (hit != null) Fetched(hit.body, State.STALE, why, hit.at) else Fetched(null, State.ERROR, why, null)
    }

    /** [fetch] over several equivalent URLs: the first that answers wins; the cache and the stale fallback are shared. */
    private fun fetchAny(key: String, urls: List<String>, ttlMs: Long): Fetched {
        var last: Fetched? = null
        for (u in urls) {
            val f = fetch(key, u, emptyMap(), ttlMs)
            if (f.state == State.OK || f.state == State.CACHED) return f
            last = f
        }
        return last ?: Fetched(null, State.ERROR, "no url declared", null)
    }

    /** A typed city as an area of [radiusKm], through the cache (a city does not move); null when nobody knows it. */
    fun geocode(city: String, radiusKm: Int): Pair<Things.Area?, SourceStatus> {
        val q = city.trim()
        val id = "geocoder"
        if (q.isEmpty()) return null to SourceStatus(id, "Geocoder", State.SKIPPED, 0, "no city typed", null, null)
        val url = t.geocoderUrl.replace("{q}", Templates.enc(q))
        val f = fetch("geo|${q.lowercase()}", url, emptyMap(), GEOCODE_TTL_MS)
        if (f.body == null) return null to SourceStatus(id, "Geocoder", f.state, 0, f.detail, f.at, null)
        val area = runCatching { Things.parseGeocode(f.body, t.clampRadius(radiusKm)) }.getOrNull()
        val status = if (area == null) SourceStatus(id, "Geocoder", State.ERROR, 0, "no place called '$q'", f.at, null)
        else SourceStatus(id, "Geocoder", f.state, 1, f.detail, f.at, null)
        return area to status
    }

    /** Stores, shelf prices and online prices for [q] around [area], as the sorted table. */
    fun compare(area0: Things.Area, q: String): Result {
        val query = q.trim()
        val area = area0.coarse(t.minRadiusKm, t.maxRadiusKm)
        val matched = Things.categoryFor(query, t)
        val cat = matched ?: t.fallback
        val statuses = mutableListOf<SourceStatus>()

        // stores — cached per area, category and radius; the public Overpass instances are tried in order
        val query0 = Things.overpassQuery(area, cat, t.overpassLimit)
        val sf = fetchAny("stores|${area.key}|${cat.id}", t.overpassUrls.map { it + "?data=" + Templates.enc(query0) }, t.storesTtlHours * 3_600_000L)
        val stores = sf.body?.let { b -> runCatching { Things.parseStores(b, area, t.maxStores) } }
        val storeList = stores?.getOrNull().orEmpty()
        statuses += when {
            sf.body == null -> SourceStatus(ID_STORES, "OpenStreetMap stores", sf.state, 0, sf.detail, sf.at, null)
            stores?.isFailure == true -> SourceStatus(ID_STORES, "OpenStreetMap stores", State.ERROR, 0, "unreadable answer: ${stores.exceptionOrNull()?.message}", sf.at, null)
            else -> SourceStatus(ID_STORES, "OpenStreetMap stores", sf.state, storeList.size, sf.detail, sf.at, null)
        }

        // shelf prices — Open Prices, tied to a place by its OSM id
        val shelfUrl = t.shelfUrl.replace("{lat}", area.lat.toString()).replace("{lon}", area.lon.toString()).replace("{radius}", area.radiusKm.toString())
        val pf = if (query.isEmpty()) null else fetch("shelf|${area.key}", shelfUrl, emptyMap(), cfg.cacheTtlMinutes * 60_000L)
        val shelf = pf?.body?.let { b -> runCatching { Things.parseShelf(b, query, clock(), t.shelfMaxAgeDays) } }
        val shelfList = shelf?.getOrNull().orEmpty()
        statuses += when {
            pf == null -> SourceStatus(ID_SHELF, "Open Prices", State.SKIPPED, 0, "needs a search term", null, null)
            pf.body == null -> SourceStatus(ID_SHELF, "Open Prices", pf.state, 0, pf.detail, pf.at, null)
            shelf?.isFailure == true -> SourceStatus(ID_SHELF, "Open Prices", State.ERROR, 0, "unreadable answer: ${shelf.exceptionOrNull()?.message}", pf.at, null)
            else -> SourceStatus(ID_SHELF, "Open Prices", pf.state, shelfList.size, pf.detail, pf.at, null)
        }

        // online prices — the fleet price service, which reads the chains' own sites
        val token = bearer()
        var backend: Things.Backend? = null
        var backendWhy = ""
        if (query.isEmpty() || storeList.isEmpty()) {
            backendWhy = if (query.isEmpty()) "needs a search term" else "no store to ask about"
            statuses += SourceStatus(ID_SERVICE, "Store sites", State.SKIPPED, 0, backendWhy, null, null)
        } else if (token.isNullOrBlank()) {
            backendWhy = "store prices need the fleet sign-in (Cloud Account or SuperApp)"
            statuses += SourceStatus(ID_SERVICE, "Store sites", State.SKIPPED, 0, backendWhy, null, null)
        } else {
            val url = t.backendUrl.replace("{q}", Templates.enc(query)).replace("{brands}", Templates.enc(Things.brandsOf(storeList).joinToString(",")))
            // the token authorises the request; it is not part of the cache key and never stored
            val bf = fetch("service|${query.lowercase()}|${Things.brandsOf(storeList).joinToString(",")}", url, mapOf("Authorization" to "Bearer $token"), t.backendTtlMinutes * 60_000L)
            val parsed = bf.body?.let { b -> runCatching { Things.parseBackend(b) } }
            backend = parsed?.getOrNull()
            if (bf.body == null) backendWhy = "price service: ${bf.detail}"
            else if (parsed?.isFailure == true) backendWhy = "price service answered something unreadable"
            statuses += when {
                bf.body == null -> SourceStatus(ID_SERVICE, "Store sites", bf.state, 0, bf.detail, bf.at, null)
                parsed?.isFailure == true -> SourceStatus(ID_SERVICE, "Store sites", State.ERROR, 0, backendWhy, bf.at, null)
                else -> SourceStatus(ID_SERVICE, "Store sites", bf.state, backend!!.offers.count { it.price != null }, bf.detail, bf.at, null)
            }
        }

        val rows = Things.compare(storeList, shelfList, backend, backendWhy, t.maxRows)
        return Result(query, area, cat, matched != null, storeList.size, rows, statuses)
    }

    fun json(r: Result): JSONObject = JSONObject()
        .put("q", r.q).put("area", JSONObject().put("lat", r.area.lat).put("lon", r.area.lon).put("radius_km", r.area.radiusKm).put("label", r.area.label))
        .put("category", r.category.id).put("category_matched", r.matched).put("stores_found", r.storesFound)
        .put("rows", JSONArray(r.rows.map { row ->
            JSONObject().put("store", row.store.name).put("osm", row.store.osm).put("km", Math.round(row.store.km * 10) / 10.0)
                .put("price", row.price ?: JSONObject.NULL).put("currency", row.currency ?: JSONObject.NULL).put("title", row.title ?: JSONObject.NULL)
                .put("source", row.source.name.lowercase()).put("url", row.url ?: JSONObject.NULL).put("at", row.at ?: JSONObject.NULL).put("note", row.note)
        }))

    companion object {
        const val ID_STORES = "osm-stores"
        const val ID_SHELF = "open-prices-shelf"
        const val ID_SERVICE = "store-sites"
        private const val GEOCODE_TTL_MS = 30L * 86_400_000L
    }
}
