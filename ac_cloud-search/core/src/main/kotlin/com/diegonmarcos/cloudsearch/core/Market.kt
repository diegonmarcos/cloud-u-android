package com.diegonmarcos.cloudsearch.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Official time series (build.json::search.series) and what a `market` analysis makes of them:
 * each series' latest value and its change against the same period one year before, and the
 * charted series' last values. Every figure is a published statistic; a series that did not answer
 * is shown as missing with its status, never filled in.
 */
object Series {
    data class Point(val period: String, val value: Double)

    /** The one dispatch: test/test-search-shell.sh holds every declared series' `parser` to a branch here. */
    fun parse(parser: String, body: String): List<Point> = when (parser) {
        "jsonstat" -> jsonStat(body)
        "sdmx_json" -> sdmxJson(body)
        else -> throw IllegalArgumentException("no series parser '$parser'")
    }

    /**
     * Eurostat JSON-stat 2.0 with every dimension but `time` pinned to one value, so a value's flat
     * index is its time index. Periods without a value (not yet published) are dropped.
     */
    fun jsonStat(body: String): List<Point> {
        val d = JSONObject(body)
        val ids = d.getJSONArray("id")
        val size = d.getJSONArray("size")
        for (i in 0 until ids.length()) {
            require(ids.getString(i) == "time" || size.getInt(i) == 1) { "more than one series: dimension ${ids.getString(i)} has ${size.getInt(i)} values" }
        }
        val index = d.getJSONObject("dimension").getJSONObject("time").getJSONObject("category").getJSONObject("index")
        val values = d.get("value")
        return index.keys().asSequence().mapNotNull { period ->
            val i = index.getInt(period)
            val v = when (values) {
                is JSONObject -> values.optNum(i.toString())
                is JSONArray -> if (i < values.length() && !values.isNull(i)) values.getDouble(i) else null
                else -> null
            }
            v?.let { Point(period, it) }
        }.sortedBy { it.period }.toList()
    }

    /**
     * SDMX-JSON 1.0 holding exactly one series: the Bundesbank wraps it in `data`, the ECB does
     * not. Observation values come as numbers (ECB) or strings (Bundesbank).
     */
    fun sdmxJson(body: String): List<Point> {
        val o = JSONObject(body)
        val root = o.optJSONObject("data") ?: o
        val dims = root.getJSONObject("structure").getJSONObject("dimensions").getJSONArray("observation")
        val time = (0 until dims.length()).map { dims.getJSONObject(it) }.firstOrNull { it.getString("id") == "TIME_PERIOD" }
            ?: throw IllegalArgumentException("no TIME_PERIOD dimension")
        val periods = time.getJSONArray("values").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }
        val series = root.getJSONArray("dataSets").getJSONObject(0).getJSONObject("series")
        require(series.length() == 1) { "expected one series, got ${series.length()}" }
        val obs = series.getJSONObject(series.keys().next()).getJSONObject("observations")
        return obs.keys().asSequence().mapNotNull { k ->
            val v = when (val raw = obs.getJSONArray(k).opt(0)) {
                is Number -> raw.toDouble()
                is String -> raw.toDoubleOrNull()
                else -> null
            }
            v?.let { Point(periods[k.toInt()], it) }
        }.sortedBy { it.period }.toList()
    }

    /** 2026-Q2 -> 2025-Q2, 2026-08 -> 2025-08, 2026 -> 2025; null when the period does not start with a year. */
    fun yearBefore(period: String): String? =
        Regex("^(\\d{4})(.*)$").matchEntire(period)?.let { m -> "${m.groupValues[1].toInt() - 1}${m.groupValues[2]}" }
}

object Market {
    data class Stat(
        val id: String, val label: String, val source: String, val detail: String, val unit: String, val change: String,
        val latest: Series.Point?, val yearAgo: Series.Point?,
    ) {
        /** pp: rate minus rate; pct: index growth in percent; null without both ends. */
        val delta: Double? get() {
            val now = latest ?: return null
            val then = yearAgo ?: return null
            return if (change == "pp") now.value - then.value else (now.value / then.value - 1) * 100
        }
    }

    data class Result(val stats: List<Stat>, val chart: Stat?, val chartPoints: List<Series.Point>, val statuses: List<SearchEngine.SourceStatus>) {
        fun toJson(): JSONObject = JSONObject()
            .put("stats", JSONArray(stats.map { statJson(it) }))
            .put("chart", chart?.id ?: JSONObject.NULL)
            .put("chart_points", JSONArray(chartPoints.map { JSONObject().put("period", it.period).put("value", it.value) }))
    }

    private fun statJson(s: Stat): JSONObject = JSONObject().put("id", s.id).put("label", s.label).put("source", s.source)
        .put("unit", s.unit).put("change", s.change)
        .put("period", s.latest?.period ?: JSONObject.NULL).put("value", s.latest?.value ?: JSONObject.NULL)
        .put("year_ago_period", s.yearAgo?.period ?: JSONObject.NULL).put("delta", s.delta ?: JSONObject.NULL)

    fun stat(s: SearchConfig.Series, points: List<Series.Point>): Stat {
        val latest = points.lastOrNull()
        val before = latest?.let { Series.yearBefore(it.period) }
        return Stat(s.id, s.label, s.source, s.detail, s.unit, s.change, latest, points.firstOrNull { it.period == before })
    }
}
