package com.diegonmarcos.cloudsearch.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * #903 Things: the stores around a place that sell a kind of item, and what each one asks for it.
 * Everything here is pure: the declaration reader, the item-to-shop-type match, the Overpass query
 * and its answer, the Open Prices and price-service answers, and the comparison table. A price is
 * only ever copied from a source's answer; a store with none says why ([Row.note]).
 */
object Things {
    /** A kind of item: the OSM `shop` values that sell it and the words (any language) that name it. */
    data class Category(val id: String, val label: String, val shops: List<String>, val words: List<String>)

    data class Config(
        val defaultRadiusKm: Int, val minRadiusKm: Int, val maxRadiusKm: Int, val radiusStepsKm: List<Int>,
        val overpassUrls: List<String>, val overpassLimit: Int, val geocoderUrl: String,
        val storesTtlHours: Int, val maxStores: Int, val maxRows: Int,
        val shelfUrl: String, val shelfMaxAgeDays: Int,
        val backendUrl: String, val backendTtlMinutes: Int,
        val categories: List<Category>, val fallback: Category,
    ) {
        fun category(id: String): Category? = categories.firstOrNull { it.id == id }
        fun clampRadius(km: Int): Int = km.coerceIn(minRadiusKm, maxRadiusKm)
    }

    /** Where the search is centred: a fix, a typed city resolved by the geocoder, or a declared city. */
    data class Area(val lat: Double, val lon: Double, val radiusKm: Int, val label: String) {
        /** The cache key: one per ~1 km cell and radius, so the same place reuses its stores. */
        val key: String get() = String.format(Locale.ROOT, "%.2f,%.2f,%d", lat, lon, radiusKm)

        /**
         * The area as it leaves the phone: the centre rounded to ~1 km, so a coarse fix is never sent
         * finer than it is, and the radius held to the declared range.
         */
        fun coarse(minKm: Int, maxKm: Int): Area =
            copy(lat = Math.round(lat * 100) / 100.0, lon = Math.round(lon * 100) / 100.0, radiusKm = radiusKm.coerceIn(minKm, maxKm))
    }

    data class Store(
        val osm: String, val name: String, val brand: String?, val shop: String,
        val lat: Double, val lon: Double, val website: String?, val address: String?, val km: Double,
    )

    /** One shelf price Open Prices holds for a place: from a receipt or a price tag, dated. */
    data class Shelf(val osm: String, val title: String, val price: Double, val currency: String, val date: Long?, val proof: Boolean, val url: String?)

    /** One adapter of the price service for the asked item: status ok | no_match | no_offers | blocked | error. */
    data class Offer(
        val adapter: String, val label: String, val status: String, val detail: String,
        val price: Double?, val currency: String?, val title: String?, val url: String?, val at: Long?,
    )

    /** What the price service says about the chains it may read: [brands] are matched against a store's brand/name. */
    data class Adapter(val id: String, val label: String, val enabled: Boolean, val brands: List<String>, val why: String)
    data class Backend(val offers: List<Offer>, val adapters: List<Adapter>)

    enum class Source { SHELF, ONLINE, NONE }

    /** One table row. [price] is null exactly when [source] is NONE, and then [note] says why. */
    data class Row(
        val store: Store, val price: Double?, val currency: String?, val title: String?,
        val source: Source, val url: String?, val at: Long?, val note: String,
    )

    // ── declaration ──────────────────────────────────────────────────────────────────────────────
    fun config(o: JSONObject): Config {
        fun cat(c: JSONObject) = Category(c.getString("id"), c.getString("label"), strings(c.getJSONArray("shops")), strings(c.optJSONArray("words")).map { it.lowercase(Locale.ROOT) })
        val shelf = o.getJSONObject("shelf_prices")
        val backend = o.getJSONObject("price_service")
        return Config(
            defaultRadiusKm = o.getInt("default_radius_km"), minRadiusKm = o.getInt("min_radius_km"), maxRadiusKm = o.getInt("max_radius_km"),
            radiusStepsKm = (o.optJSONArray("radius_steps_km")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList()),
            overpassUrls = strings(o.getJSONArray("overpass_urls")), overpassLimit = o.getInt("overpass_limit"), geocoderUrl = o.getString("geocoder_url"),
            storesTtlHours = o.getInt("stores_ttl_hours"), maxStores = o.getInt("max_stores"), maxRows = o.getInt("max_rows"),
            shelfUrl = shelf.getString("url"), shelfMaxAgeDays = shelf.getInt("max_age_days"),
            backendUrl = backend.getString("url"), backendTtlMinutes = backend.getInt("ttl_minutes"),
            categories = (0 until o.getJSONArray("categories").length()).map { cat(o.getJSONArray("categories").getJSONObject(it)) },
            fallback = cat(o.getJSONObject("fallback")),
        )
    }

    /** Every cross-reference of the declaration, checked like SearchConfig.problems. */
    fun problems(c: Config): List<String> = buildList {
        if (c.minRadiusKm < 1 || c.minRadiusKm > c.defaultRadiusKm || c.defaultRadiusKm > c.maxRadiusKm) add("things radius: need 1 <= min <= default <= max")
        if (c.radiusStepsKm.any { it < c.minRadiusKm || it > c.maxRadiusKm }) add("things radius_steps_km must lie within min..max")
        if (c.categories.isEmpty()) add("things declares no category")
        if (c.categories.map { it.id }.toSet().size != c.categories.size) add("things has duplicate category ids")
        (c.categories + c.fallback).filter { it.shops.isEmpty() }.forEach { add("things category ${it.id} names no shop type") }
        c.categories.filter { it.words.isEmpty() }.forEach { add("things category ${it.id} names no word, so no item could ever match it") }
        if (c.overpassUrls.isEmpty()) add("things names no overpass_urls")
        (c.overpassUrls.map { "overpass_urls entry $it" to it } + listOf("geocoder_url" to c.geocoderUrl, "shelf url" to c.shelfUrl, "price_service url" to c.backendUrl))
            .filter { !it.second.startsWith("https://") }.forEach { add("things ${it.first} must be https") }
        if (!c.geocoderUrl.contains("{q}")) add("things geocoder_url has no {q}")
        if (!c.backendUrl.contains("{q}")) add("things price_service url has no {q}")
        if (c.maxStores < 1 || c.maxRows < 1 || c.overpassLimit < c.maxStores) add("things max_stores / max_rows / overpass_limit are inconsistent")
    }

    // ── what is being asked ──────────────────────────────────────────────────────────────────────
    /** The words of an item search, lowercase, letters and digits only. */
    fun tokens(q: String): List<String> = q.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /**
     * The declared category whose words the item names, most letters matched first. A word matches a
     * token when it is the token, starts it ("bohr" in "bohrmaschine") or ends it ("tisch" in
     * "esstisch", words of five letters or more): a word never matches in the middle of a token.
     */
    fun categoryFor(q: String, c: Config): Category? {
        val toks = tokens(q)
        if (toks.isEmpty()) return null
        fun hit(w: String) = w.length >= MIN_WORD && toks.any { it == w || it.startsWith(w) || (w.length >= MIN_SUFFIX && it.endsWith(w)) }
        return c.categories
            .map { cat -> cat to cat.words.filter { hit(it) }.sumOf { it.length } }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }?.first
    }

    private const val MIN_WORD = 3
    private const val MIN_SUFFIX = 5

    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * 6371.0088 * asin(sqrt(a))
    }

    // ── OpenStreetMap ────────────────────────────────────────────────────────────────────────────
    /** The Overpass QL for every shop of the category's types within the area. */
    fun overpassQuery(area: Area, cat: Category, limit: Int): String {
        val shops = cat.shops.joinToString("|") { it.replace(Regex("[^a-z_]"), "") }
        return String.format(Locale.ROOT, "[out:json][timeout:25];nwr[\"shop\"~\"^(%s)$\"](around:%d,%.5f,%.5f);out center %d;", shops, area.radiusKm * 1000, area.lat, area.lon, limit)
    }

    /** Overpass `elements[]` as stores nearest first; an element with no name, no place or outside the radius is dropped. */
    fun parseStores(body: String, area: Area, max: Int): List<Store> {
        val els = JSONObject(body).optJSONArray("elements") ?: return emptyList()
        return (0 until els.length()).mapNotNull { els.optJSONObject(it) }.mapNotNull { e ->
            val tags = e.optJSONObject("tags") ?: return@mapNotNull null
            val center = e.optJSONObject("center")
            val lat = if (e.has("lat")) e.getDouble("lat") else center?.optDouble("lat") ?: return@mapNotNull null
            val lon = if (e.has("lon")) e.getDouble("lon") else center?.optDouble("lon") ?: return@mapNotNull null
            if (lat.isNaN() || lon.isNaN()) return@mapNotNull null
            val brand = tags.optStr("brand")
            val name = tags.optStr("name") ?: brand ?: return@mapNotNull null
            val km = haversineKm(area.lat, area.lon, lat, lon)
            if (km > area.radiusKm) return@mapNotNull null
            val street = listOfNotNull(tags.optStr("addr:street"), tags.optStr("addr:housenumber")).joinToString(" ").takeIf { it.isNotBlank() }
            val city = listOfNotNull(tags.optStr("addr:postcode"), tags.optStr("addr:city")).joinToString(" ").takeIf { it.isNotBlank() }
            Store(
                osm = "${e.getString("type")}/${e.getLong("id")}", name = name, brand = brand, shop = tags.optString("shop"),
                lat = lat, lon = lon, website = tags.optStr("website") ?: tags.optStr("contact:website"),
                address = listOfNotNull(street, city).joinToString(", ").takeIf { it.isNotBlank() }, km = km,
            )
        }.sortedBy { it.km }.take(max)
    }

    /** The first hit of a Nominatim search as an area of [radiusKm]; null when it found nothing. */
    fun parseGeocode(body: String, radiusKm: Int): Area? {
        val a = JSONArray(body)
        val h = a.optJSONObject(0) ?: return null
        val lat = h.optString("lat").toDoubleOrNull() ?: return null
        val lon = h.optString("lon").toDoubleOrNull() ?: return null
        val label = h.optString("name").ifBlank { h.optString("display_name").substringBefore(',') }.ifBlank { "?" }
        return Area(lat, lon, radiusKm, label)
    }

    // ── Open Prices ──────────────────────────────────────────────────────────────────────────────
    /**
     * The shelf prices in an Open Prices answer that name every word of [q] and are per unit (a price
     * per kilogram is not comparable with one per piece), newer than [maxAgeDays]; each tied to the OSM
     * place it was seen at. A price with no currency is read as EUR (Open Prices leaves it blank for
     * euro prices on receipts it could not classify).
     */
    fun parseShelf(body: String, q: String, now: Long, maxAgeDays: Int): List<Shelf> {
        val words = tokens(q)
        val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { items.optJSONObject(it) }.mapNotNull { p ->
            val perUnit = p.optStr("price_per")
            if (perUnit != null && perUnit != "UNIT") return@mapNotNull null
            val product = p.optJSONObject("product")
            val name = product?.optStr("product_name") ?: p.optStr("product_name") ?: return@mapNotNull null
            val hay = (name + " " + (product?.optStr("brands") ?: "")).lowercase(Locale.ROOT)
            if (words.isEmpty() || !words.all { hay.contains(it) }) return@mapNotNull null
            val price = p.optNum("price") ?: return@mapNotNull null
            val type = p.optStr("location_osm_type")?.lowercase(Locale.ROOT) ?: return@mapNotNull null
            val id = p.optNum("location_osm_id")?.toLong() ?: return@mapNotNull null
            val date = Parsers.isoDay(p.optStr("date"))
            if (date != null && now - date > maxAgeDays * 86_400_000L) return@mapNotNull null
            Shelf(
                osm = "$type/$id", title = name, price = price, currency = p.optStr("currency") ?: "EUR", date = date,
                proof = p.optStr("proof_id") != null,
                url = p.optStr("product_code")?.let { "https://prices.openfoodfacts.org/products/$it" },
            )
        }
    }

    // ── the price service (scrappers-api GET /prices) ───────────────────────────────────────────
    fun parseBackend(body: String): Backend {
        val o = JSONObject(body)
        val results = o.optJSONArray("results")
        val adapters = o.optJSONArray("adapters")
        return Backend(
            offers = (0 until (results?.length() ?: 0)).mapNotNull { results?.optJSONObject(it) }.map { r ->
                val price = if (r.optString("status") == "ok") r.optNum("price") else null
                Offer(
                    adapter = r.getString("adapter"), label = r.optString("label"), status = r.getString("status"), detail = r.optString("detail"),
                    price = price, currency = if (price == null) null else r.optStr("currency") ?: "EUR", title = r.optStr("title"),
                    url = r.optStr("url") ?: r.optStr("search_url"), at = r.optNum("fetched_at")?.toLong(),
                )
            },
            adapters = (0 until (adapters?.length() ?: 0)).mapNotNull { adapters?.optJSONObject(it) }.map { a ->
                Adapter(
                    a.getString("adapter"), a.optString("label"), a.optBoolean("enabled"),
                    (a.optJSONArray("brands")?.let { b -> (0 until b.length()).map { b.getString(it).lowercase(Locale.ROOT) } } ?: emptyList()), a.optString("why"),
                )
            },
        )
    }

    /** True when the store's brand (or, with no brand tag, its name) is one of the adapter's chains, as whole words. */
    fun chainOf(store: Store, adapter: Adapter): Boolean {
        val who = (store.brand ?: store.name).lowercase(Locale.ROOT)
        return adapter.brands.any { b -> Regex("(^|[^\\p{L}\\p{N}])" + Regex.escape(b) + "([^\\p{L}\\p{N}]|$)").containsMatchIn(who) }
    }

    /** The brands to ask the price service about: the distinct brand (or name) of the stores found. */
    fun brandsOf(stores: List<Store>): List<String> = stores.map { (it.brand ?: it.name).lowercase(Locale.ROOT) }.distinct().sorted()

    // ── the comparison ───────────────────────────────────────────────────────────────────────────
    /**
     * One row per store. A store's price is the cheapest of what is really known for it: a shelf price
     * seen at that very place, or the online price its chain's own site publishes. [backend] null means the price
     * service was not asked or did not answer ([backendWhy] says why); every store without a price carries the reason.
     * Priced rows first, cheapest first (nearest breaks a tie), then the unpriced, nearest first.
     */
    fun compare(stores: List<Store>, shelf: List<Shelf>, backend: Backend?, backendWhy: String, max: Int): List<Row> {
        val rows = stores.map { s ->
            val here = shelf.filter { it.osm == s.osm }.minByOrNull { it.price }
            val chain = backend?.adapters?.firstOrNull { chainOf(s, it) }
            val offer = chain?.let { a -> backend.offers.firstOrNull { it.adapter == a.id } }
            val online = offer?.takeIf { it.price != null }
            val shelfRow = here?.let { Row(s, it.price, it.currency, it.title, Source.SHELF, it.url, it.date, "") }
            val onlineRow = online?.let { Row(s, it.price, it.currency, it.title, Source.ONLINE, it.url, it.at, "") }
            listOfNotNull(shelfRow, onlineRow).minByOrNull { it.price!! } ?: Row(s, null, null, null, Source.NONE, s.website, null, whyNone(backend, chain, offer, backendWhy))
        }
        return rows.sortedWith(compareBy<Row> { it.price == null }.thenBy { it.price ?: 0.0 }.thenBy { it.store.km }).take(max)
    }

    private fun whyNone(backend: Backend?, chain: Adapter?, offer: Offer?, backendWhy: String): String = when {
        chain == null && backend == null -> "no shelf price here; $backendWhy"
        chain == null -> "no price source for this chain"
        !chain.enabled -> "${chain.label}: ${chain.why}"
        offer == null -> "${chain.label}: not asked"
        else -> "${chain.label}: " + when (offer.status) {
            "no_match" -> "no product of that name on the site"
            "no_offers" -> "the site published no prices"
            "blocked" -> "the site does not allow automated reading"
            else -> "lookup failed"
        }
    }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
}
