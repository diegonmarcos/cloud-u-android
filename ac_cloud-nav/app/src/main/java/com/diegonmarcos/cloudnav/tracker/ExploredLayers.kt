package com.diegonmarcos.cloudnav.tracker

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A pin for a place or a stay: [kind] = sleep | stay. */
data class MapPin(val at: LatLon, val label: String, val kind: String)
/** A route: a trip's sleep stops joined, or one move; [mode] picks the colour. */
data class MapRoute(val points: List<LatLon>, val mode: String, val label: String)
/** A drawn area: [level] = city | region | country; [ring] is a closed polygon ring (first == last). */
data class MapArea(val label: String, val level: String, val ring: List<LatLon>)

data class ExploredLayers(val pins: List<MapPin>, val routes: List<MapRoute>, val areas: List<MapArea>) {
    val points: List<LatLon> get() = pins.map { it.at } + routes.flatMap { it.points } + areas.flatMap { it.ring }
    val isEmpty: Boolean get() = pins.isEmpty() && routes.isEmpty() && areas.isEmpty()
}

/**
 * Explored > Maps (#1156): the Trips data on a map. Pure: `tracker/trips` + a few days of `tracker/segments` (contract v1 JSON, the
 * same objects the fleet door serves) become pins, routes and areas; [ExploredLayers.pinsJson] / [routesJson] / [areasJson] are the
 * GeoJSON the MapLibre layers draw. Areas have no boundary data behind them: a city is a disc around its located points, a region
 * or country the convex hull of its cities' discs (an honest approximation, labelled as such on the page).
 */
object ExploredMapData {
    const val CITY_MIN_KM = 2.0
    const val CITY_MAX_KM = 25.0

    private fun JSONObject.str(k: String) = optString(k, "").takeIf { it != "null" } ?: ""
    private fun JSONObject.at(): LatLon? = if (has("lat") && has("lon") && !isNull("lat") && !isNull("lon")) LatLon(optDouble("lat"), optDouble("lon")) else null

    /** One located spot with the admin names it sits in (city / state / country, any may be blank). */
    private data class Spot(val at: LatLon, val city: String, val state: String, val country: String)

    fun layers(trips: JSONObject, days: List<JSONObject>): ExploredLayers {
        val pins = LinkedHashMap<String, MapPin>(); val routes = ArrayList<MapRoute>(); val spots = ArrayList<Spot>()
        fun pin(p: MapPin) { pins.putIfAbsent("%.4f,%.4f".format(java.util.Locale.US, p.at.lat, p.at.lon) + p.kind, p) }

        val ta = trips.optJSONArray("trips") ?: JSONArray()
        for (i in 0 until ta.length()) {
            val t = ta.getJSONObject(i)
            val stops = t.optJSONArray("route") ?: continue
            val pts = ArrayList<LatLon>()
            for (k in 0 until stops.length()) {
                val s = stops.getJSONObject(k); val at = s.at() ?: continue
                pin(MapPin(at, s.str("city"), "sleep")); spots += Spot(at, s.str("city"), "", "")
                if (pts.lastOrNull() != at) pts += at
            }
            val veh = t.optJSONObject("vehicle_m")
            val mode = if (veh == null) "unknown" else veh.keys().asSequence().maxByOrNull { veh.optLong(it) } ?: "unknown"
            val cities = t.optJSONArray("cities")?.let { a -> (0 until a.length()).joinToString(", ") { a.getString(it) } } ?: ""
            if (pts.size >= 2) routes += MapRoute(pts, mode, cities)
        }

        for (d in days) {
            val sa = d.optJSONArray("segments") ?: continue
            for (i in 0 until sa.length()) {
                val s = sa.getJSONObject(i)
                if (s.optString("type") == "stay") {
                    val loc = s.optJSONObject("location") ?: continue; val at = loc.at() ?: continue
                    val label = loc.str("name").ifBlank { loc.str("city") }
                    pin(MapPin(at, label, if (s.optString("kind") == "sleep") "sleep" else "stay"))
                    spots += Spot(at, loc.str("city"), loc.str("state"), loc.str("country"))
                } else {
                    val a = s.optJSONObject("from")?.at(); val b = s.optJSONObject("to")?.at()
                    if (a != null && b != null && a != b) routes += MapRoute(listOf(a, b), s.str("mode"), "")
                }
            }
        }

        val areas = ArrayList<MapArea>()
        for ((_, group) in spots.filter { it.city.isNotBlank() }.groupBy { it.city.trim().lowercase() }) {
            areas += MapArea(group.first().city.trim(), "city", disc(group.map { it.at }))
        }
        areas += hulls(spots, "region") { it.state.trim() }
        areas += hulls(spots, "country") { it.country.trim() }
        return ExploredLayers(pins.values.toList(), routes, areas)
    }

    /** A region / country: the convex hull of its cities' discs (or of its bare points when no city is named). */
    private fun hulls(spots: List<Spot>, level: String, key: (Spot) -> String): List<MapArea> =
        spots.filter { key(it).isNotBlank() }.groupBy { key(it).lowercase() }.map { (_, g) ->
            val perCity = g.groupBy { it.city.trim().lowercase() }.flatMap { (c, cs) -> if (c.isBlank()) cs.flatMap { disc(listOf(it.at)) } else disc(cs.map { it.at }) }
            MapArea(key(g.first()), level, hull(perCity))
        }

    /** A disc ring around the centre of [pts], radius = spread of the points, clamped to [CITY_MIN_KM]..[CITY_MAX_KM]. */
    fun disc(pts: List<LatLon>, segments: Int = 24): List<LatLon> {
        val c = LatLon(pts.map { it.lat }.average(), pts.map { it.lon }.average())
        val spread = pts.maxOf { km(c, it) } + 0.5
        val r = min(CITY_MAX_KM, max(CITY_MIN_KM, spread))
        val dLat = r / 111.32; val dLon = r / (111.32 * max(0.2, cos(Math.toRadians(c.lat))))
        val ring = (0 until segments).map { i -> val a = 2 * Math.PI * i / segments; LatLon(c.lat + dLat * sin(a), c.lon + dLon * cos(a)) }
        return ring + ring.first()
    }

    private fun km(a: LatLon, b: LatLon): Double {
        val dy = (a.lat - b.lat) * 111.32; val dx = (a.lon - b.lon) * 111.32 * cos(Math.toRadians((a.lat + b.lat) / 2))
        return Math.sqrt(dx * dx + dy * dy)
    }

    /** Andrew's monotone chain, closed ring; fewer than three distinct points are returned as they are (closed). */
    fun hull(points: List<LatLon>): List<LatLon> {
        val p = points.distinct().sortedWith(compareBy({ it.lon }, { it.lat }))
        if (p.size < 3) return if (p.isEmpty()) p else p + p.first()
        fun cross(o: LatLon, a: LatLon, b: LatLon) = (a.lon - o.lon) * (b.lat - o.lat) - (a.lat - o.lat) * (b.lon - o.lon)
        val lo = ArrayList<LatLon>(); for (q in p) { while (lo.size >= 2 && cross(lo[lo.size - 2], lo.last(), q) <= 0) lo.removeAt(lo.size - 1); lo += q }
        val up = ArrayList<LatLon>(); for (q in p.asReversed()) { while (up.size >= 2 && cross(up[up.size - 2], up.last(), q) <= 0) up.removeAt(up.size - 1); up += q }
        val h = lo.dropLast(1) + up.dropLast(1)
        return h + h.first()
    }

    private fun coord(p: LatLon) = JSONArray().put(p.lon).put(p.lat)
    private fun fc(f: JSONArray) = JSONObject().put("type", "FeatureCollection").put("features", f).toString()
    private fun feature(g: JSONObject, props: JSONObject) = JSONObject().put("type", "Feature").put("geometry", g).put("properties", props)

    fun pinsJson(l: ExploredLayers): String = fc(JSONArray().also { f ->
        l.pins.forEach { f.put(feature(JSONObject().put("type", "Point").put("coordinates", coord(it.at)), JSONObject().put("label", it.label).put("kind", it.kind).put("color", if (it.kind == "sleep") "#6366F1" else "#475569"))) }
    })

    fun routesJson(l: ExploredLayers): String = fc(JSONArray().also { f ->
        l.routes.forEach { r ->
            f.put(feature(JSONObject().put("type", "LineString").put("coordinates", JSONArray().also { a -> r.points.forEach { a.put(coord(it)) } }),
                JSONObject().put("mode", r.mode).put("label", r.label).put("color", RouteGeo.modeHex(Mode.parse(r.mode)))))
        }
    })

    fun areasJson(l: ExploredLayers): String = fc(JSONArray().also { f ->
        l.areas.filter { it.ring.size >= 4 }.forEach { a ->
            f.put(feature(JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(JSONArray().also { r -> a.ring.forEach { r.put(coord(it)) } })),
                JSONObject().put("label", a.label).put("level", a.level)))
        }
    })
}
