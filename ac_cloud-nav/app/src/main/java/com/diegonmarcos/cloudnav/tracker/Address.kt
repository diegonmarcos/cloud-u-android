package com.diegonmarcos.cloudnav.tracker

import org.json.JSONArray
import org.json.JSONObject

/** One reverse-geocode / POI answer, provider-neutral: the full address plus the POI name and its OSM tag (class, type). */
data class Geocoded(
    val name: String? = null, val street: String? = null, val postcode: String? = null, val neighborhood: String? = null,
    val city: String? = null, val state: String? = null, val country: String? = null,
    val osmClass: String? = null, val osmType: String? = null,
) {
    val category: PoiCategory get() = PoiCategory.of(osmClass, osmType)
    val isPoi: Boolean get() = !name.isNullOrBlank() && category != PoiCategory.OTHER
    fun toJson(): String = JSONObject().put("v", 2).put("name", name ?: "").put("street", street ?: "").put("postcode", postcode ?: "")
        .put("neighborhood", neighborhood ?: "").put("city", city ?: "").put("state", state ?: "").put("country", country ?: "")
        .put("class", osmClass ?: "").put("type", osmType ?: "").toString()

    companion object {
        fun fromJson(s: String): Geocoded? = runCatching {
            val o = JSONObject(s)
            fun v(k: String) = o.optString(k, "").takeIf { it.isNotBlank() }
            Geocoded(v("name"), v("street"), v("postcode"), v("neighborhood"), v("city"), v("state"), v("country"), v("class"), v("type"))
        }.getOrNull()
    }
}

/** Parses the open geocoders' answers (OSM Nominatim, Photon, Overpass). Pure: no Android, tested on the JVM. */
object AddressParser {
    private val POI_KEYS = listOf("amenity", "shop", "tourism", "leisure", "office", "historic", "healthcare", "railway", "aeroway", "public_transport")

    private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k).trim().takeIf { it.isNotEmpty() } else null

    private fun street(road: String?, number: String?): String? = when {
        road == null -> null
        number == null -> road
        else -> "$road $number"
    }

    fun parse(providerId: String, body: String): Geocoded? = runCatching {
        when {
            providerId.startsWith("nominatim") || providerId == "locationiq" -> nominatim(body)
            providerId.startsWith("photon") -> photon(body)
            else -> null
        }
    }.getOrNull()

    /** Nominatim /reverse (format=json, addressdetails=1): `address` plus the top-level `class`/`type` (or `category`) and `name`. */
    fun nominatim(body: String): Geocoded? {
        val root = JSONObject(body)
        val a = root.optJSONObject("address") ?: return null
        val cls = root.str("class") ?: root.str("category")
        val type = root.str("type")
        // the POI's own name: top-level name, else the address key named after its class (amenity: "Cafe X")
        val name = root.str("name") ?: cls?.let { a.str(it) }
        return Geocoded(
            name = name, street = street(a.str("road") ?: a.str("pedestrian") ?: a.str("footway") ?: a.str("path"), a.str("house_number")),
            postcode = a.str("postcode"), neighborhood = a.str("neighbourhood") ?: a.str("suburb") ?: a.str("quarter"),
            city = a.str("city") ?: a.str("town") ?: a.str("village") ?: a.str("municipality") ?: a.str("hamlet"),
            state = a.str("state") ?: a.str("region") ?: a.str("province") ?: a.str("county"), country = a.str("country"),
            osmClass = cls, osmType = type,
        )
    }

    /** Photon /reverse: first feature's properties (name, street, housenumber, postcode, city, state, country, osm_key, osm_value). */
    fun photon(body: String): Geocoded? {
        val f = JSONObject(body).optJSONArray("features") ?: return null
        if (f.length() == 0) return null
        val p = f.getJSONObject(0).optJSONObject("properties") ?: return null
        return Geocoded(
            name = p.str("name"), street = street(p.str("street"), p.str("housenumber")), postcode = p.str("postcode"),
            neighborhood = p.str("district") ?: p.str("locality"), city = p.str("city"), state = p.str("state"), country = p.str("country"),
            osmClass = p.str("osm_key"), osmType = p.str("osm_value"),
        )
    }

    /** Overpass `out tags center` answer: the nearest named POI (first element with a name and a POI key). */
    fun overpass(body: String): Geocoded? {
        val els: JSONArray = JSONObject(body).optJSONArray("elements") ?: return null
        for (i in 0 until els.length()) {
            val t = els.getJSONObject(i).optJSONObject("tags") ?: continue
            val name = t.str("name") ?: continue
            val key = POI_KEYS.firstOrNull { t.str(it) != null } ?: continue
            return Geocoded(name = name, street = street(t.str("addr:street"), t.str("addr:housenumber")), postcode = t.str("addr:postcode"),
                city = t.str("addr:city"), osmClass = key, osmType = t.str(key))
        }
        return null
    }

    /** The Overpass QL for the nearest named POI within [radiusM] of a point. */
    fun overpassQuery(lat: Double, lon: Double, radiusM: Int = 40): String =
        "[out:json][timeout:10];nwr(around:$radiusM,${"%.6f".format(java.util.Locale.US, lat)},${"%.6f".format(java.util.Locale.US, lon)})" +
            "[name][~\"^(${POI_KEYS.joinToString("|")})$\"~\".\"];out tags center 1;"

    /** [base] (street / postcode / city / state / country answer) with the POI [poi] folded in: its name and tag win, the address fills gaps. */
    fun merge(base: Geocoded?, poi: Geocoded?): Geocoded? {
        if (base == null) return poi
        if (poi == null) return base
        return base.copy(name = poi.name ?: base.name, osmClass = poi.osmClass ?: base.osmClass, osmType = poi.osmType ?: base.osmType,
            street = base.street ?: poi.street, postcode = base.postcode ?: poi.postcode, city = base.city ?: poi.city)
    }
}

/** Fills a [Place]'s empty address fields from [g]; what is already there (owner edits, earlier answers) is kept. */
fun Place.withGeocoded(g: Geocoded): Place = copy(
    name = name?.takeIf { it.isNotBlank() } ?: g.name?.takeIf { g.isPoi } ?: g.name,
    street = street.orBlankTo(g.street), postcode = postcode.orBlankTo(g.postcode), neighborhood = neighborhood.orBlankTo(g.neighborhood),
    city = city.orBlankTo(g.city), state = state.orBlankTo(g.state), country = country.orBlankTo(g.country),
    category = category.orBlankTo(g.category.takeIf { it != PoiCategory.OTHER }?.name),
)

private fun String?.orBlankTo(other: String?): String? = this?.takeIf { it.isNotBlank() } ?: other?.takeIf { it.isNotBlank() }
