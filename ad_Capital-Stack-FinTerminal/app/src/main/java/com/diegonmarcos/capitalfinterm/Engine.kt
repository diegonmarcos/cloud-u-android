package com.diegonmarcos.capitalfinterm

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** A point of a chart: [label] names it, [x] is its position on a line chart, [y] its value. */
data class ChartPoint(val label: String, val x: Double, val y: Double)

/** What a panel shows: a table (columns, rows, optional link per row), a chart, the date of the data and a note. */
data class PanelData(
    val columns: List<String>,
    val rows: List<List<String>>,
    val links: List<String>,
    val chart: List<ChartPoint>,
    val asOf: String,
    val note: String,
    /** The name of the source that served it when that is not the panel's own (the primary), else empty. */
    val servedBy: String = "",
)

/**
 * Loads a live panel: builds its requests from the source's declared endpoint and the panel's declared fetch, reads the answers through
 * [Http], and derives the rows and the chart named by the panel's `derive`. Pure apart from the [Http] it is given.
 */
object Engine {
    /** Fetches and derives [panel] from [source]. Throws IOException when nothing came back. */
    fun load(panel: Panel, source: Source, http: Http, today: LocalDate, route: PrimaryRoute? = null): PanelData {
        val f = panel.fetch ?: throw IOException("panel ${panel.id} has no fetch")
        val data = when (panel.derive) {
            "latest_curve", "forward_grid", "level_change", "fx_spot", "fx_cross" -> fredPanel(panel, source, f, http, today, route)
            "latest_per_series" -> perSeries(panel, source, f, http)
            "imf_latest" -> imf(panel, source, f, http)
            "worldbank_latest" -> worldBank(panel, source, f, http)
            "articles" -> articles(panel, source, f, http)
            else -> throw IOException("unknown derive ${panel.derive}")
        }
        if (data.rows.isEmpty() && data.chart.isEmpty()) throw IOException("no data")
        return data
    }

    /** The static allocation of a model portfolio: its legs as bars and rows. */
    fun alloc(panel: Panel, legs: List<Pair<String, Int>>): PanelData = PanelData(
        panel.columns, legs.map { listOf(it.first, "${it.second}%") }, emptyList(),
        legs.mapIndexed { i, l -> ChartPoint(l.first, i.toDouble(), l.second.toDouble()) }, "", "",
    )

    // ------------------------------------------------------------------ FRED

    private class FredSeries(val spec: JSONObject, val obs: List<Obs>, val viaPrimary: Boolean = false) {
        val label: String get() = Catalog.str(spec, "label")
        val last: Obs get() = obs.last()
        val prev: Obs? get() = obs.getOrNull(obs.size - 2)
    }

    private fun fredPanel(panel: Panel, source: Source, f: JSONObject, http: Http, today: LocalDate, route: PrimaryRoute?): PanelData {
        val series = fredSeries(source, f, http, today, route)
        val viaPrimary = series.count { it.viaPrimary }
        val by = when {
            route == null || viaPrimary == 0 -> ""
            viaPrimary == series.size -> route.source.name
            else -> route.source.name + " + " + source.name
        }
        val asOf = series.maxOf { it.last.date }
        val missing = (f.getJSONArray("series").length() - series.size).let { if (it > 0) "$it series unavailable" else "" }
        return derived(panel, f, series, asOf, missing).copy(servedBy = by)
    }

    private fun derived(panel: Panel, f: JSONObject, series: List<FredSeries>, asOf: String, missing: String): PanelData {
        return when (panel.derive) {
            "latest_curve" -> {
                val pts = series.map { ChartPoint(it.label, it.spec.optDouble("years"), it.last.value) }.sortedBy { it.x }
                val rows = series.map { listOf(it.label, num(it.last.value, 2), change(it.last, it.prev, 100.0, 1), it.last.date) }
                PanelData(panel.columns, rows, emptyList(), pts, asOf, missing)
            }
            "forward_grid" -> forwardGrid(panel, f, series, asOf, missing)
            "level_change" -> {
                val bp = panel.raw.optString("change_unit") == "bp"
                val byLevel = panel.raw.optString("chart_value") == "level"
                val rows = series.map { s ->
                    val chg = if (bp) change(s.last, s.prev, 100.0, 0) else pctChange(s.last, s.prev)
                    listOf(s.label, group(s.last.value), chg, s.last.date)
                }
                val chart = series.mapIndexed { i, s -> ChartPoint(s.label, i.toDouble(), if (byLevel) s.last.value else pct(s.last, s.prev) ?: 0.0) }
                PanelData(panel.columns, rows, emptyList(), chart, asOf, missing)
            }
            "fx_spot" -> {
                val rows = series.map { listOf(Catalog.str(it.spec, "pair"), sig(it.last.value), pctChange(it.last, it.prev), it.last.date) }
                PanelData(panel.columns, rows, emptyList(), emptyList(), asOf, missing)
            }
            else -> fxCross(panel, series, asOf, missing)
        }
    }

    private fun fredSeries(source: Source, f: JSONObject, http: Http, today: LocalDate, route: PrimaryRoute?): List<FredSeries> {
        val specs = f.getJSONArray("series")
        val lookback = f.optInt("lookback_days", 30)
        val tasks = (0 until specs.length()).map { i ->
            val spec = specs.getJSONObject(i)
            Callable<FredSeries?> {
                val id = spec.getString("id")
                val from = today.minusDays(lookback.toLong()).toString()
                val primary = if (route == null) emptyList() else try {
                    val url = Requests.url(route.base, route.peekPath, emptyList(), mapOf("topic" to route.topic.replace("{id}", id)))
                    Providers.finceptSeries(http.get(url)).filter { it.date >= from }
                } catch (e: IOException) {
                    emptyList()
                }
                if (primary.isNotEmpty()) return@Callable FredSeries(spec, primary, true)
                val obs = try {
                    FredCsv.parse(http.get(Requests.fred(source.endpoint, id, today, lookback)))[id].orEmpty()
                } catch (e: IOException) {
                    emptyList()
                }
                if (obs.isEmpty()) null else FredSeries(spec, obs)
            }
        }
        val pool = Executors.newFixedThreadPool(minOf(6, maxOf(1, tasks.size)))
        try {
            val got = pool.invokeAll(tasks).mapNotNull { it.get() }
            if (got.isEmpty()) throw IOException("no data")
            return got
        } finally {
            pool.shutdown()
        }
    }

    private fun forwardGrid(panel: Panel, f: JSONObject, series: List<FredSeries>, asOf: String, note: String): PanelData {
        val fw = f.getJSONObject("forward")
        val curve = series.map { it.spec.optDouble("years") to it.last.value }.sortedBy { it.first }
        val grid = fw.getJSONArray("grid")
        val rows = (0 until grid.length()).map { grid.getJSONArray(it) }.map { g ->
            val s = g.getDouble(0)
            val l = g.getDouble(1)
            listOf("${years(s)}Y x ${years(l)}Y", num(Forward.rate(curve, s, l), 2))
        }
        val pts = Forward.curve(curve, fw.optDouble("curve_length", 1.0), fw.optInt("curve_max_start", 29))
            .map { ChartPoint("${years(it.first)}Y", it.first, it.second) }
        return PanelData(panel.columns, rows, emptyList(), pts, asOf, note)
    }

    private fun fxCross(panel: Panel, series: List<FredSeries>, asOf: String, note: String): PanelData {
        val usd = LinkedHashMap<String, Double>()
        usd["USD"] = 1.0
        series.forEach { s ->
            val v = s.last.value
            if (v > 0.0) usd[Catalog.str(s.spec, "ccy")] = if (Catalog.str(s.spec, "quote") == "usd_per") v else 1.0 / v
        }
        val names = usd.keys.toList()
        val rows = names.map { i -> listOf(i) + names.map { j -> if (i == j) "-" else sig(usd.getValue(i) / usd.getValue(j)) } }
        return PanelData(panel.columns + names, rows, emptyList(), emptyList(), asOf, note)
    }

    // -------------------------------------------------------------- DBnomics

    private fun perSeries(panel: Panel, source: Source, f: JSONObject, http: Http): PanelData {
        val body = http.get(Requests.url(source.endpoint, f.optString("path"), params(f)))
        val labels = f.optJSONObject("labels")
        val found = Providers.dbnomics(body).mapNotNull { s ->
            val (period, value, _) = s.latest() ?: return@mapNotNull null
            Triple(labels?.optString(s.code)?.takeIf { it.isNotBlank() } ?: s.name, value, period)
        }.sortedByDescending { it.second }
        val rows = found.map { listOf(it.first, num(it.second, 2), it.third) }
        val chart = if (panel.view == Dashboards.VIEW_BARS) found.mapIndexed { i, t -> ChartPoint(t.first, i.toDouble(), t.second) } else emptyList()
        return PanelData(panel.columns, rows, emptyList(), chart, found.maxOfOrNull { it.third }.orEmpty(), "")
    }

    // ------------------------------------------------------------- IMF, World Bank

    private fun imf(panel: Panel, source: Source, f: JSONObject, http: Http): PanelData {
        val body = http.get(Requests.url(source.endpoint, f.optString("path"), params(f)))
        val names = f.optJSONObject("country_names")
        val nameMap = HashMap<String, String>()
        if (names != null) names.keys().forEach { nameMap[it] = names.optString(it) }
        val got = Providers.imf(body, f.optString("path").trim('/'), strings(f.optJSONArray("countries")), nameMap).sortedByDescending { it.value }
        return PanelData(panel.columns, got.map { listOf(it.name, num(it.value, 1), it.year) }, emptyList(), emptyList(), got.maxOfOrNull { it.year }.orEmpty(), "")
    }

    private fun worldBank(panel: Panel, source: Source, f: JSONObject, http: Http): PanelData {
        val url = Requests.url(source.endpoint, f.optString("path"), params(f), mapOf("countries" to strings(f.optJSONArray("countries")).joinToString(";")))
        val got = Providers.worldBank(http.get(url)).sortedByDescending { it.value }
        return PanelData(panel.columns, got.map { listOf(it.name, num(it.value, 1), it.year) }, emptyList(), emptyList(), got.maxOfOrNull { it.year }.orEmpty(), "")
    }

    // ----------------------------------------------------------------- GDELT

    private fun articles(panel: Panel, source: Source, f: JSONObject, http: Http): PanelData {
        val got = Providers.gdelt(http.get(Requests.url(source.endpoint, f.optString("path"), params(f))))
        return PanelData(panel.columns, got.map { listOf(it.title, it.domain, it.seen) }, got.map { it.url }, emptyList(), got.maxOfOrNull { it.seen }.orEmpty(), "")
    }

    // --------------------------------------------------------------- helpers

    /** The declared `params` as ordered pairs; an object or array value is sent as its JSON text. */
    fun params(f: JSONObject): List<Pair<String, String>> {
        val p = f.optJSONObject("params") ?: return emptyList()
        return p.keys().asSequence().map { k -> k to p.opt(k)?.toString().orEmpty() }.toList()
    }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }

    private fun years(v: Double): String = if (v == Math.floor(v)) v.toInt().toString() else num(v, 1)

    fun num(v: Double, digits: Int): String = if (v.isFinite()) String.format(Locale.ROOT, "%.${digits}f", v) else "-"

    fun group(v: Double): String = String.format(Locale.ROOT, "%,.2f", v)

    /** Four significant-ish digits: 156.87, 1.1199, 0.00638. */
    fun sig(v: Double): String = when {
        !v.isFinite() -> "-"
        Math.abs(v) >= 100.0 -> String.format(Locale.ROOT, "%.2f", v)
        Math.abs(v) >= 1.0 -> String.format(Locale.ROOT, "%.4f", v)
        else -> String.format(Locale.ROOT, "%.5f", v)
    }

    private fun change(last: Obs, prev: Obs?, scale: Double, digits: Int): String =
        if (prev == null) "-" else String.format(Locale.ROOT, "%+.${digits}f", (last.value - prev.value) * scale)

    private fun pct(last: Obs, prev: Obs?): Double? = if (prev == null || prev.value == 0.0) null else (last.value / prev.value - 1.0) * 100.0

    private fun pctChange(last: Obs, prev: Obs?): String = pct(last, prev)?.let { String.format(Locale.ROOT, "%+.2f", it) } ?: "-"
}
