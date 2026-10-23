package com.diegonmarcos.cloudnav.tracker

import android.content.Context
import com.diegonmarcos.cloudnav.maps.MapsProviderClient

/**
 * Names the places of a day that have coordinates but no name, through the app's active open reverse-geocode provider
 * (OSM Nominatim / Photon via [MapsProviderClient]; no key of any commercial service). Every answer, including "nothing
 * found", is cached by a ~11 m grid cell so a place is asked once; network calls are spaced by [MIN_GAP_MS] and capped
 * per pass ([MAX_PER_PASS]). Blocking: call it off the main thread.
 */
class PlaceNamer(ctx: Context) {
    private val app = ctx.applicationContext
    private val sp = app.getSharedPreferences("tracker_place_names", Context.MODE_PRIVATE)

    /** [places] with every unnamed, located one filled from the cache or (rate-limited) the provider. */
    fun name(places: List<Place?>): List<Place?> {
        var calls = 0
        return places.map { p ->
            if (p == null || !p.located || !p.name.isNullOrBlank()) return@map p
            val lat = p.lat!!; val lon = p.lon!!
            val key = key(lat, lon)
            val hit = sp.getString(key, null)
            val r: Place? = if (hit != null) decode(hit) else if (calls < MAX_PER_PASS) {
                calls++
                gap()
                val res = runCatching { MapsProviderClient.reverseGeocode(app, lat, lon) }.getOrNull()
                sp.edit().putString(key, encode(res)).apply()
                decode(encode(res))
            } else null
            if (r == null) p else p.copy(name = r.name, neighborhood = r.neighborhood ?: p.neighborhood, city = r.city ?: p.city, country = r.country ?: p.country)
        }
    }

    private fun encode(r: MapsProviderClient.ReverseResult?): String =
        listOf(r?.placeName, r?.neighborhood, r?.city, r?.country).joinToString("\u0001") { it ?: "" }

    private fun decode(s: String): Place? {
        val f = s.split("\u0001")
        fun v(i: Int) = f.getOrNull(i)?.takeIf { it.isNotBlank() }
        return Place(null, null, v(0), null, null, v(1), v(2), null, v(3))
    }

    companion object {
        const val MIN_GAP_MS = 1_100L
        const val MAX_PER_PASS = 8
        @Volatile private var lastCallMs = 0L

        /** The cache cell of a coordinate (4 decimals). */
        fun key(lat: Double, lon: Double) = "%.4f,%.4f".format(java.util.Locale.US, lat, lon)

        @Synchronized private fun gap() {
            val wait = MIN_GAP_MS - (System.currentTimeMillis() - lastCallMs)
            if (wait > 0) runCatching { Thread.sleep(wait) }
            lastCallMs = System.currentTimeMillis()
        }
    }
}
