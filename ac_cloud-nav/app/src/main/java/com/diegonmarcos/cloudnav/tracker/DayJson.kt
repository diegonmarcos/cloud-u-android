package com.diegonmarcos.cloudnav.tracker

import org.json.JSONArray
import org.json.JSONObject

/**
 * The wire shape of the fleet door's `tracker/segments` and `tracker/daily` (contract version [VERSION]). Fields are only ever
 * added within a version; a rename or removal bumps it. Boards ME, MyHealth and MyTrips parse exactly this.
 */
object DayJson {
    const val VERSION = 1

    fun place(p: Place?): JSONObject? = p?.let {
        JSONObject().apply {
            if (it.lat != null && it.lon != null) put("lat", it.lat).put("lon", it.lon)
            put("name", it.name ?: ""); put("street", it.street ?: ""); put("postcode", it.postcode ?: "")
            put("neighborhood", it.neighborhood ?: ""); put("city", it.city ?: ""); put("state", it.state ?: ""); put("country", it.country ?: "")
            put("address", it.address); put("category", it.category ?: "")
        }
    }

    fun segment(s: DaySeg): JSONObject = when (s) {
        is DaySeg.Stay -> JSONObject().put("type", "stay").put("kind", s.kind.name.lowercase())
            .put("start", s.startMs).put("end", s.endMs).put("confirmed", s.confirmed).put("missing", s.missing)
            .put("location", place(s.place) ?: JSONObject.NULL)
        is DaySeg.Move -> JSONObject().put("type", "move").put("start", s.startMs).put("end", s.endMs)
            .put("distance_m", Math.round(s.distanceM)).put("steps", s.steps).put("avg_speed_mps", Math.round(s.avgSpeedMps * 100) / 100.0)
            .put("moving_ms", s.movingMs).put("mode", s.mode.name.lowercase())
            .put("from", place(s.from) ?: JSONObject.NULL).put("to", place(s.to) ?: JSONObject.NULL)
    }

    fun segments(day: List<DaySeg>, fromMs: Long, toMs: Long): JSONObject {
        val arr = JSONArray(); day.forEach { arr.put(segment(it)) }
        return JSONObject().put("version", VERSION).put("from", fromMs).put("to", toMs).put("segments", arr)
    }

    fun daily(stats: List<DayStats>): JSONObject {
        val arr = JSONArray()
        for (d in stats) {
            val modes = JSONObject()
            d.summary.byMode.forEach { (m, st) -> modes.put(m.name.lowercase(), JSONObject().put("distance_m", Math.round(st.distanceM)).put("moving_ms", st.movingMs)) }
            val veh = JSONObject(); d.vehicleM.forEach { (m, v) -> veh.put(m.name.lowercase(), Math.round(v)) }
            arr.put(JSONObject().put("date", d.date.toString()).put("sleep_ms", d.sleepMs)
                .put("sleep_h", Math.round(d.sleepMs / 3_600_000.0 * 10) / 10.0).put("sleep_city", d.sleepCity ?: "")
                .put("walk_m", Math.round(d.walkM)).put("run_m", Math.round(d.runM)).put("vehicle_m", veh)
                .put("by_mode", modes).put("visits", d.summary.visits))
        }
        return JSONObject().put("version", VERSION).put("days", arr)
    }
}
