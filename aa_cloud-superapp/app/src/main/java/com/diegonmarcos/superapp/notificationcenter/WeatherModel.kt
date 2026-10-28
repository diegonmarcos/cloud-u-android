package com.diegonmarcos.superapp.notificationcenter

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * PURE side of the Weather badge: the MET Norway (api.met.no, the yr.no
 * backend) `locationforecast/2.0/complete` contract and the badge face.
 * No network, no Android — unit tested in WeatherModelTest.
 */
object WeatherModel {

    /** MET Norway's terms REQUIRE an identifying User-Agent; anonymous calls get 403. */
    const val USER_AGENT = "CloudSuperApp/1.0 github.com/diegonmarcos/cloudy-u-android"

    /** How far ahead the rain chance looks: "will I need an umbrella soon". */
    const val RAIN_WINDOW_H = 6

    data class Forecast(
        val currentC: Double,
        val maxC: Double,
        val minC: Double,
        /** Max probability of precipitation (%) over the next [RAIN_WINDOW_H] hours; null = source gave none. */
        val rainPct: Int?,
        /** local hour-of-day → temperature, for the rest of today. */
        val hours: List<Pair<Int, Double>>,
    )

    /** api.met.no URL; coordinates trimmed to 4 decimals as MET asks (cache friendliness). */
    fun apiUrl(lat: Double, lon: Double): String = String.format(Locale.US,
        "https://api.met.no/weatherapi/locationforecast/2.0/complete?lat=%.4f&lon=%.4f", lat, lon)

    /**
     * Parse a `complete` body. Times are UTC; "today" and hour labels are in [zone].
     * Returns null on any contract break — never a guessed number.
     */
    fun parse(body: String?, now: Instant, zone: ZoneId): Forecast? = runCatching {
        val ts = JSONObject(body ?: return null).getJSONObject("properties").getJSONArray("timeseries")
        val today = now.atZone(zone).toLocalDate()
        val nowHour = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val horizon = nowHour.plusSeconds(RAIN_WINDOW_H * 3600L)
        var current: Double? = null
        var rain: Int? = null
        val todayTemps = ArrayList<Pair<Int, Double>>()
        for (i in 0 until ts.length()) {
            val e = ts.getJSONObject(i)
            val t = Instant.parse(e.getString("time"))
            val data = e.getJSONObject("data")
            val temp = data.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature")
            if (t.isBefore(nowHour)) continue
            if (current == null) current = temp
            val local = t.atZone(zone)
            if (local.toLocalDate() == today) todayTemps += local.hour to temp
            if (t.isBefore(horizon)) {
                val p = precipProbability(data)
                if (p != null) rain = maxOf(rain ?: 0, p)
            }
        }
        val cur = current ?: return null
        val temps = todayTemps.map { it.second }.ifEmpty { listOf(cur) }
        Forecast(cur, temps.max(), temps.min(), rain, todayTemps)
    }.getOrNull()

    /** next_1_hours carries it in the Nordics; elsewhere next_6_hours does. */
    private fun precipProbability(data: JSONObject): Int? {
        for (k in listOf("next_1_hours", "next_6_hours")) {
            val d = data.optJSONObject(k)?.optJSONObject("details") ?: continue
            if (d.has("probability_of_precipitation"))
                return Math.round(d.getDouble("probability_of_precipitation")).toInt().coerceIn(0, 100)
        }
        return null
    }

    /** Dense badge head: "18° · 💧40%"; the drop is omitted when the source has no probability. */
    fun headline(f: Forecast): String {
        val t = String.format(Locale.US, "%.0f°", f.currentC).replace("-0°", "0°")
        return if (f.rainPct == null) t else "$t · 💧${f.rainPct}%"
    }
}
