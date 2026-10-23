package com.diegonmarcos.cloudnav.tracker

import android.content.Context
import com.diegonmarcos.cloudnav.maps.MapsApiKeyPrefs
import com.diegonmarcos.cloudnav.maps.MapsProviderPrefs
import com.diegonmarcos.cloudnav.maps.MapsProviders
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fills the places of a day that have coordinates but a thin address: street, postcode, city, state, country, plus the POI
 * name and its category, through the app's active open providers (reverse: OSM Nominatim / Photon; POI: Overpass; no key of
 * any commercial service). Every answer, including "nothing found", is cached by a ~11 m grid cell so a place is asked once;
 * network calls are spaced by [MIN_GAP_MS] and capped per pass ([MAX_PER_PASS]). Blocking: call it off the main thread.
 */
class PlaceNamer(ctx: Context, private val net: Net = HttpNet) {
    private val app = ctx.applicationContext
    private val sp = app.getSharedPreferences("tracker_place_addr_v2", Context.MODE_PRIVATE)

    /** The two network reads, injectable so the cache and the rate limit are tested without a network. */
    interface Net {
        fun reverse(providerId: String, url: String): String?
        fun overpass(url: String, ql: String): String?
    }

    /** [places] with every located one that still lacks an address filled from the cache or (rate-limited) the providers. */
    fun name(places: List<Place?>): List<Place?> {
        var calls = 0
        return places.map { p ->
            if (p == null || !p.needsAddress) return@map p
            val lat = p.lat!!; val lon = p.lon!!
            val key = key(lat, lon)
            val hit = sp.getString(key, null)
            val g: Geocoded? = if (hit != null) Geocoded.fromJson(hit) else if (calls < MAX_PER_PASS) {
                calls++
                val res = lookup(lat, lon)
                sp.edit().putString(key, (res ?: Geocoded()).toJson()).apply()
                res
            } else null
            if (g == null) p else p.withGeocoded(g)
        }
    }

    private fun lookup(lat: Double, lon: Double): Geocoded? {
        val providers = MapsProviders.loadFromBuildConfig()
        val prefs = MapsProviderPrefs(app)
        val rev = providers.firstOrNull { it.id == prefs.activeReverse && it.endpoint.isNotEmpty() } ?: return null
        val url = rev.endpoint.replace("{lat}", lat.toString()).replace("{lon}", lon.toString()).replace("{key}", MapsApiKeyPrefs(app).get(rev.id))
        gap()
        val base = net.reverse(rev.id, url)?.let { AddressParser.parse(rev.id, it) }
        if (base?.isPoi == true) return base
        // no POI in the reverse answer: ask Overpass for the nearest named amenity / shop / tourism object
        val poiProvider = providers.firstOrNull { it.id == prefs.activePoi && it.endpoint.isNotEmpty() } ?: return base
        gap()
        val poi = net.overpass(poiProvider.endpoint, AddressParser.overpassQuery(lat, lon))?.let { runCatching { AddressParser.overpass(it) }.getOrNull() }
        return AddressParser.merge(base, poi)
    }

    object HttpNet : Net {
        private fun call(url: String, post: String?): String? = runCatching {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 12000
                setRequestProperty("User-Agent", "CloudNav-Timeline/1 (open-geocoder client, cached, 1 req/s)")
                setRequestProperty("Accept", "application/json")
                if (post != null) { requestMethod = "POST"; doOutput = true; setRequestProperty("Content-Type", "application/x-www-form-urlencoded") }
            }
            try {
                if (post != null) c.outputStream.use { it.write(post.toByteArray()) }
                if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().use { it.readText() }
            } finally { c.disconnect() }
        }.getOrNull()

        override fun reverse(providerId: String, url: String) = call(url, null)
        override fun overpass(url: String, ql: String) = call(url, "data=" + URLEncoder.encode(ql, "UTF-8"))
    }

    companion object {
        const val MIN_GAP_MS = 1_100L
        const val MAX_PER_PASS = 8
        @Volatile private var lastCallMs = 0L

        /** The cache cell of a coordinate (4 decimals). */
        fun key(lat: Double, lon: Double) = "%.4f,%.4f".format(java.util.Locale.US, lat, lon)

        /** How long to wait before the next network call, given the previous one at [lastMs]. */
        fun waitMs(lastMs: Long, nowMs: Long): Long = (MIN_GAP_MS - (nowMs - lastMs)).coerceAtLeast(0)

        @Synchronized private fun gap() {
            val wait = waitMs(lastCallMs, System.currentTimeMillis())
            if (wait > 0) runCatching { Thread.sleep(wait) }
            lastCallMs = System.currentTimeMillis()
        }
    }
}
