package com.diegonmarcos.cloudnav.routes

/** One stop of the Routes list: a label and, once resolved, its place. The first stop starts as the user's own location. */
data class Stop(val label: String, val latLon: Pair<Double, Double>?)

/** The Routes list's rules, apart from the screen. */
object RouteStops {
    const val YOUR_LOCATION = "Your location"

    /** [stops] with the one at [index] moved by [delta] (-1 up, +1 down); unchanged when that runs off either end. */
    fun moved(stops: List<Stop>, index: Int, delta: Int): List<Stop> {
        val to = index + delta
        if (index !in stops.indices || to !in stops.indices) return stops
        return stops.toMutableList().also { val t = it[index]; it[index] = it[to]; it[to] = t }
    }

    /** What the router gets: every resolved stop, with "Your location" standing in for [here] while it is known. */
    fun coords(stops: List<Stop>, here: Pair<Double, Double>?): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        for (s in stops) {
            val ll = s.latLon ?: if (s.label == YOUR_LOCATION) here else null
            if (ll != null) out.add(doubleArrayOf(ll.first, ll.second))
        }
        return out
    }

    fun droppedPinLabel(lat: Double, lon: Double) = "Dropped pin (%.4f, %.4f)".format(java.util.Locale.US, lat, lon)

    /** The summary line while there is no route to draw. */
    fun idleSummary(stops: List<Stop>) =
        if (stops.any { it.latLon != null }) "Tap the locate button to set your start" else "Add a destination to see the route"
}
