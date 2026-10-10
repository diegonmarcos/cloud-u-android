package com.diegonmarcos.cloudsearch.core.models

import com.diegonmarcos.cloudsearch.core.Cache
import com.diegonmarcos.cloudsearch.core.Http
import com.diegonmarcos.cloudsearch.core.models.ModelCatalogue.Catalogue
import com.diegonmarcos.cloudsearch.core.models.ModelCatalogue.Price
import com.diegonmarcos.cloudsearch.core.models.ModelCatalogue.PriceUnit
import com.diegonmarcos.cloudsearch.core.models.ModelCatalogue.Source
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset

/**
 * The catalogue's prices: read live from OpenRouter at most once every [Catalogue.refreshHours],
 * kept in [cache] (filesDir, so it survives offline), and the bundled snapshot when nothing was ever
 * fetched. A failed refresh never loses what is on screen: the last stored prices stay, marked stale.
 */
class ModelCatalogueRepository(
    private val cat: Catalogue, private val http: Http, private val cache: Cache,
    private val clock: () -> Long, private val timeoutMs: Int,
) {
    enum class Origin { LIVE, CACHED, STALE, SNAPSHOT }

    /** [prices] null = the snapshot; [missing] = curated ids a fetched catalogue no longer lists; [at] = when fetched. */
    data class Priced(val prices: Map<String, Price>?, val missing: Set<String>, val at: Long?, val origin: Origin)

    /** "Prices as of <date>": the fetch's UTC day, or the snapshot's pricing_as_of. */
    fun asOf(p: Priced): String = p.at?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() } ?: cat.pricingAsOf

    fun fresh(at: Long, now: Long): Boolean = now >= at && now - at < cat.refreshHours * HOUR

    /** What is stored, without the network: the cached prices (fresh or stale) or the snapshot. */
    fun stored(): Priced {
        val hit = cache.get(KEY) ?: return Priced(null, emptySet(), null, Origin.SNAPSHOT)
        val p = runCatching { decode(hit.body) }.getOrNull() ?: return Priced(null, emptySet(), null, Origin.SNAPSHOT)
        return p.copy(at = hit.at, origin = if (fresh(hit.at, clock())) Origin.CACHED else Origin.STALE)
    }

    /** Fresh cache as is; otherwise one refresh, and when that fails, the stored prices. */
    fun load(): Priced {
        val s = stored()
        if (s.origin == Origin.CACHED) return s
        return refresh() ?: s
    }

    /** Every catalogue a section prices from, then each curated row's own endpoints; null when no catalogue answered. */
    fun refresh(): Priced? {
        val used = cat.sections.map { it.source }.toMutableSet()
        if (Source.IMAGES in used) used += Source.VIDEOS
        val lists = used.mapNotNull { s -> cat.urls.list(s)?.let { u -> fetch(u)?.let { s to it } } }.toMap()
        if (lists.isEmpty()) return null
        val tokens = lists.filterKeys { it == Source.CHAT || it == Source.EMBEDDINGS }.mapValues { (_, j) -> runCatching { OpenRouterPrices.list(j) }.getOrNull() }
        val images = lists[Source.IMAGES]?.let { runCatching { OpenRouterPrices.ids(it) }.getOrNull() }
        val videos = lists[Source.VIDEOS]?.let { runCatching { OpenRouterPrices.videos(it) }.getOrNull() }
        val videoIds = lists[Source.VIDEOS]?.let { runCatching { OpenRouterPrices.ids(it) }.getOrNull() }
        val prices = mutableMapOf<String, Price>()
        val missing = mutableSetOf<String>()
        val floors = mutableMapOf<String, Pair<Double?, Double?>>()
        for (section in cat.sections) for (row in section.rows) {
            when (section.source) {
                Source.CHAT, Source.EMBEDDINGS -> {
                    val listed = tokens[section.source] ?: continue
                    val p = listed[row.id]
                    if (p == null) { missing += row.id; continue }
                    val floor = floors.getOrPut(row.id) {
                        fetch(cat.urls.modelEndpoints.replace(ModelCatalogue.ID, row.id))?.let { runCatching { OpenRouterPrices.floor(it) }.getOrNull() } ?: (null to null)
                    }
                    prices[row.id] = OpenRouterPrices.withFloor(p, floor)
                }
                Source.IMAGES, Source.VIDEOS -> when {
                    images != null && row.id in images ->
                        fetch(cat.urls.imageEndpoints.replace(ModelCatalogue.ID, row.id))?.let { runCatching { OpenRouterPrices.image(it) }.getOrNull() }?.let { prices[row.id] = it }
                    videos != null && row.id in videos -> prices[row.id] = videos.getValue(row.id)
                    images != null && videoIds != null && row.id !in videoIds -> missing += row.id
                }
                Source.NONE -> Unit
            }
        }
        val now = clock()
        val out = Priced(prices, missing, now, Origin.LIVE)
        cache.put(KEY, encode(out), now)
        return out
    }

    private fun fetch(url: String): String? = runCatching { http.get(url, emptyMap(), timeoutMs) }.getOrNull()?.takeIf { it.code in 200..299 }?.body

    companion object {
        const val KEY = "model-catalogue|v1"
        private const val HOUR = 3_600_000L

        fun encode(p: Priced): String {
            val prices = JSONObject()
            p.prices.orEmpty().forEach { (id, v) ->
                prices.put(id, JSONObject().put("u", v.unit.name).putOpt("i", v.input).putOpt("o", v.output).putOpt("fi", v.floorInput).putOpt("fo", v.floorOutput))
            }
            return JSONObject().put("prices", prices).put("missing", JSONArray(p.missing.sorted())).toString()
        }

        fun decode(s: String): Priced {
            val o = JSONObject(s)
            val pr = o.getJSONObject("prices")
            val prices = pr.keys().asSequence().associateWith { id ->
                val v = pr.getJSONObject(id)
                fun n(k: String): Double? = if (v.has(k) && !v.isNull(k)) v.getDouble(k) else null
                Price(PriceUnit.valueOf(v.getString("u")), n("i"), n("o"), n("fi"), n("fo"))
            }
            val m = o.optJSONArray("missing") ?: JSONArray()
            return Priced(prices, (0 until m.length()).map { m.getString(it) }.toSet(), null, Origin.LIVE)
        }
    }
}
