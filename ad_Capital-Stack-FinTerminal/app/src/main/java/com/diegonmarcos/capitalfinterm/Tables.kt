package com.diegonmarcos.capitalfinterm

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.util.Locale

/**
 * The per-item tables (data/dashboards.json `derive`): `performance` (one row per symbol, columns from [Performance.row]),
 * `portfolio_performance` (a model portfolio's legs through declared proxies, daily rebalanced, then [Performance.row]) and
 * `yields_matrix` (country x tenor, spot or forward via [Forward], GDP-weighted World / DM / EM averages).
 *
 * Every item is fetched on its own: the container's /v1 route first (fetch.primary.api with {each}), then the panel's own source,
 * then each declared fallback. A route that declares `symbol_map` serves only the items it maps (its symbol for that item); one
 * without serves every item under the item's own name. A missing value is "—" and its reason goes to the panel note.
 */
object Tables {
    const val PERFORMANCE = "performance"
    const val PORTFOLIO = "portfolio_performance"
    const val MATRIX = "yields_matrix"
    val DERIVES: Set<String> = setOf(PERFORMANCE, PORTFOLIO, MATRIX)

    /** Extra (non-return) columns a performance table may declare after the return columns. */
    const val COL_VOL = "Vol 1Y"
    const val COL_MDD = "MaxDD 1Y"

    /** One item's answer: what came back, the source name that served it (empty: none) and why not, when nothing did. */
    data class ItemGot(val got: Got, val by: String, val why: String)

    fun load(panel: Panel, source: Source, f: JSONObject, http: Http, today: LocalDate, c: ModelCtx): PanelData = when (panel.derive) {
        PERFORMANCE -> performance(panel, f, Engine.eachOf(f, today, c.user).associateWith { gather(it, source, f, http, today, c) })
        PORTFOLIO -> {
            val proxies = f.optJSONObject("proxies") ?: JSONObject()
            val legs = c.dash?.legs.orEmpty()
            portfolio(panel, f, legs, legs.associate { (name, _) -> name to Catalog.str(proxies, name).let { sym -> if (sym.isEmpty()) ItemGot(Got(), "", "no proxy declared") else gather(sym, source, f, http, today, c) } })
        }
        MATRIX -> matrix(panel, f, strings(f.optJSONArray("each")).associateWith { gather(it, source, f, http, today, c) })
        else -> throw IOException("unknown derive ${panel.derive}")
    }

    // ------------------------------------------------------------------ fetch

    /** The symbol a route uses for [item]: its `symbol_map` entry, the item itself when the route maps nothing, null when it maps others only. */
    fun symbolFor(route: JSONObject, item: String): String? {
        val m = route.optJSONObject("symbol_map") ?: return item
        return Catalog.str(m, item).ifEmpty { null }
    }

    fun gather(item: String, source: Source, f: JSONObject, http: Http, today: LocalDate, c: ModelCtx): ItemGot {
        val why = ArrayList<String>()
        val p = f.optJSONObject("primary")
        if (p != null && Catalog.str(p, "api").isNotEmpty()) {
            val sym = symbolFor(p, item)
            if (c.api == null) why += "mesh off" else if (sym != null) {
                try {
                    val sl = Engine.slots(f, today, listOf(sym)) + ("each" to sym)
                    val g = Feeds.parse(Catalog.str(p, "parse").ifEmpty { "finterm_v1" }, http.get(Requests.url(c.api.base, Catalog.str(p, "api"), filled(p, sl), sl)), Feeds.Opt(f, sym))
                    if (!g.isEmpty) return ItemGot(g, c.api.source.name, "")
                    why += "${c.api.source.name}: empty"
                } catch (e: IOException) {
                    why += "${c.api.source.name}: no answer"
                }
            }
        }
        val routes = ArrayList<Pair<Source, JSONObject>>()
        routes += source to f
        val fbs = f.optJSONArray("fallbacks")
        if (fbs != null) for (i in 0 until fbs.length()) {
            val fb = fbs.optJSONObject(i) ?: continue
            val s = Catalog.byId(c.sources, Catalog.str(fb, "source")) ?: continue
            routes += s to Engine.route(f, fb)
        }
        for ((s, spec) in routes) {
            val sym = symbolFor(spec, item) ?: continue
            try {
                val sl = Engine.slots(spec, today, listOf(sym)) + ("each" to sym)
                val conn = Catalog.str(spec, "provider").ifEmpty { s.connector }
                val g = Feeds.parse(conn, http.get(Requests.url(s.endpoint, Catalog.str(spec, "path"), filled(spec, sl), sl)), Feeds.Opt(spec, sym, s.paths))
                if (!g.isEmpty) return ItemGot(g, s.name, "")
                why += "${s.name}: empty"
            } catch (e: IOException) {
                why += "${s.name}: no answer"
            }
        }
        if (why.isEmpty()) why += "no source for $item"
        return ItemGot(Got(), "", why.joinToString("; "))
    }

    private fun filled(spec: JSONObject, sl: Map<String, String>) = Engine.params(spec).map { (k, v) -> k to sl.entries.fold(v) { a, (n, x) -> a.replace("{$n}", x) } }

    // ------------------------------------------------------------------ performance

    /** The return value of one declared column: a [Performance.COLUMNS] name, a whole-years "NY" (CAGR), or vol / max drawdown over 1y. */
    fun column(col: String, closes: List<Pair<LocalDate, Double>>, row: List<Double?>): Double? {
        val i = Performance.COLUMNS.indexOf(col)
        if (i >= 0) return row[i]
        return when {
            col == COL_VOL -> vol1y(closes)
            col == COL_MDD -> maxDrawdown1y(closes)
            col.endsWith("Y") && col.dropLast(1).toIntOrNull() != null -> Performance.trailing(closes, col.dropLast(1).toInt())
            else -> null
        }
    }

    /** Annualised volatility of daily log returns over the last year (fraction); null with fewer than 20 returns. */
    fun vol1y(closes: List<Pair<LocalDate, Double>>): Double? {
        val s = closes.filter { it.second > 0.0 }.sortedBy { it.first }
        val asOf = s.lastOrNull()?.first ?: return null
        val y = s.filter { !it.first.isBefore(asOf.minusYears(1)) }.map { it.second }
        val r = y.zipWithNext { a, b -> Math.log(b / a) }
        if (r.size < 20) return null
        val m = r.average()
        return Math.sqrt(r.sumOf { (it - m) * (it - m) } / (r.size - 1) * 252.0)
    }

    /** Largest peak-to-trough fall over the last year, as a negative fraction (0.0 when it only rose); null with no data. */
    fun maxDrawdown1y(closes: List<Pair<LocalDate, Double>>): Double? {
        val s = closes.filter { it.second > 0.0 }.sortedBy { it.first }
        val asOf = s.lastOrNull()?.first ?: return null
        var peak = 0.0
        var dd = 0.0
        s.filter { !it.first.isBefore(asOf.minusYears(1)) }.forEach { (_, v) -> peak = maxOf(peak, v); dd = minOf(dd, v / peak - 1.0) }
        return dd
    }

    fun closes(g: Got): List<Pair<LocalDate, Double>> = g.bars.mapNotNull { b -> runCatching { LocalDate.parse(b.date.take(10)) }.getOrNull()?.let { it to b.c } }
        .distinctBy { it.first }.sortedBy { it.first }

    fun fmt(col: String, v: Double?): String = if (col == COL_VOL && v != null && v.isFinite()) String.format(Locale.US, "%.2f%%", v * 100.0) else Performance.format(v)

    /** Rows: the declared label (full name, ticker in the detail) and last close, then each declared return column. */
    fun performance(panel: Panel, f: JSONObject, got: Map<String, ItemGot>): PanelData {
        val labels = f.optJSONObject("labels") ?: JSONObject()
        val perf = panel.columns.drop(2)
        val missing = ArrayList<String>()
        var asOf = ""
        val by = LinkedHashSet<String>()
        val rows = got.map { (item, ig) ->
            val cl = closes(ig.got)
            val name = Catalog.str(labels, item).ifEmpty { item }
            if (cl.isEmpty()) { missing += "$name: ${ig.why}"; return@map listOf(name, FinFormat.DASH) + perf.map { FinFormat.DASH } }
            by += ig.by
            if (cl.last().first.toString() > asOf) asOf = cl.last().first.toString()
            val row = Performance.row(cl)
            val short = perf.filter { column(it, cl, row) == null }
            if (short.isNotEmpty()) missing += "$name ${short.joinToString("/")}: history from ${cl.first().first}"
            listOf(name, Engine.sig(cl.last().second)) + perf.map { fmt(it, column(it, cl, row)) }
        }
        val note = if (missing.isEmpty()) "" else "— " + missing.joinToString(" · ")
        return PanelData(panel.columns, rows, emptyList(), emptyList(), asOf, note, servedBy = by.filter { it.isNotEmpty() }.joinToString(" + "))
    }

    // ------------------------------------------------------------------ model portfolio

    /** A constant-weight portfolio rebalanced daily over the dates every leg has: V0 = 100, Vt = Vt-1 * sum(w * Pt / Pt-1). */
    fun rebalanced(legs: List<Pair<Double, List<Pair<LocalDate, Double>>>>): List<Pair<LocalDate, Double>> {
        if (legs.isEmpty() || legs.any { it.second.isEmpty() }) return emptyList()
        val maps = legs.map { it.second.toMap() }
        val dates = maps.map { it.keys }.reduce { a, b -> a.intersect(b) }.sorted()
        if (dates.isEmpty()) return emptyList()
        val tw = legs.sumOf { it.first }
        val out = ArrayList<Pair<LocalDate, Double>>()
        var v = 100.0
        out += dates[0] to v
        for (i in 1 until dates.size) {
            v *= legs.indices.sumOf { k -> legs[k].first / tw * (maps[k][dates[i]]!! / maps[k][dates[i - 1]]!!) }
            out += dates[i] to v
        }
        return out
    }

    fun portfolio(panel: Panel, f: JSONObject, legs: List<Pair<String, Int>>, got: Map<String, ItemGot>): PanelData {
        val proxies = f.optJSONObject("proxies") ?: JSONObject()
        val perf = panel.columns.drop(2)
        val series = legs.map { (name, w) -> Triple(name, w, closes(got[name]?.got ?: Got())) }
        val port = rebalanced(series.map { it.second.toDouble() to it.third })
        val missing = series.filter { it.third.isEmpty() }.map { "${it.first}: ${got[it.first]?.why.orEmpty()}" }
        fun line(label: String, cl: List<Pair<LocalDate, Double>>, last: String): List<String> =
            if (cl.isEmpty()) listOf(label, FinFormat.DASH) + perf.map { FinFormat.DASH }
            else Performance.row(cl).let { r -> listOf(label, last) + perf.map { fmt(it, column(it, cl, r)) } }
        val rows = listOf(line("Portfolio " + legs.joinToString("/") { it.second.toString() }, port, if (port.isEmpty()) FinFormat.DASH else Engine.sig(port.last().second))) +
            series.map { (name, w, cl) -> line("$name ${w}% (${Catalog.str(proxies, name)})", cl, cl.lastOrNull()?.let { Engine.sig(it.second) } ?: FinFormat.DASH) }
        val note = (if (port.isNotEmpty()) "Daily rebalanced from ${port.first().first}; legs priced by ETF proxies." else "") +
            (if (missing.isEmpty()) "" else " — " + missing.joinToString(" · "))
        return PanelData(panel.columns, rows, emptyList(), emptyList(), port.lastOrNull()?.first?.toString().orEmpty(), note.trim(),
            servedBy = got.values.map { it.by }.filter { it.isNotEmpty() }.distinct().joinToString(" + "))
    }

    // ------------------------------------------------------------------ yields matrix

    data class Tenor(val label: String, val years: Double)

    fun tenors(f: JSONObject): List<Tenor> {
        val a = f.optJSONArray("tenors") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONArray(it) }.map { Tenor(it.optString(0), it.optDouble(1)) }
    }

    /** One country's curve as (years, yield %), sorted: v1 points carry tenor_years; a source's own keys are placed by `years`. */
    fun curve(f: JSONObject, g: Got): List<Pair<Double, Double>> {
        val years = f.optJSONObject("years")
        return g.points.map { p -> (if (years != null && years.has(p.key)) years.getDouble(p.key) else p.x) to p.value }
            .filter { it.first > 0.0 && it.second.isFinite() }.distinctBy { it.first }.sortedBy { it.first }
    }

    /** The cell at [t]: spot = the point at that tenor (none: null); forward = [Forward.rate] from [start] years for [t] years. */
    fun cell(curve: List<Pair<Double, Double>>, t: Double, forward: Boolean, start: Double): Double? =
        if (forward) Forward.rate(curve, start, t).takeIf { curve.isNotEmpty() && it.isFinite() }
        else curve.firstOrNull { Math.abs(it.first - t) < 0.02 }?.second

    /** GDP-weighted mean over the countries that have a value (weights renormalised); null when none does. */
    fun weighted(values: List<Pair<Double, Double?>>): Double? {
        val have = values.filter { it.second != null && it.first > 0.0 }
        val w = have.sumOf { it.first }
        return if (have.isEmpty() || w <= 0.0) null else have.sumOf { it.first * it.second!! } / w
    }

    fun matrix(panel: Panel, f: JSONObject, got: Map<String, ItemGot>): PanelData {
        val forward = panel.raw.optString("mode") == "forward"
        val start = f.optDouble("forward_start_years", 1.0)
        val ts = tenors(f)
        val labels = f.optJSONObject("labels") ?: JSONObject()
        val gdp = f.optJSONObject("gdp_usd_bn") ?: JSONObject()
        val groups = f.optJSONObject("groups") ?: JSONObject()
        val curves = got.mapValues { curve(f, it.value.got) }
        val cells = curves.mapValues { (_, cv) -> ts.map { cell(cv, it.years, forward, start) } }
        fun avg(members: List<String>): List<Double?> = ts.indices.map { i -> weighted(members.map { gdp.optDouble(it, 0.0) to cells[it]?.get(i) }) }
        val dm = strings(groups.optJSONArray("DM"))
        val em = strings(groups.optJSONArray("EM"))
        fun fmtRow(label: String, v: List<Double?>) = listOf(label) + v.map { x -> if (x == null) FinFormat.DASH else String.format(Locale.US, "%.2f", x) }
        val rows = listOf(fmtRow("World avg", avg(dm + em)), fmtRow("DM avg", avg(dm)), fmtRow("EM avg", avg(em))) +
            got.keys.map { fmtRow(Catalog.str(labels, it).ifEmpty { it }, cells[it]!!) }
        val noCurve = got.filter { curves[it.key].isNullOrEmpty() }.map { "${it.key} (${it.value.why})" }
        val gaps = curves.filter { it.value.isNotEmpty() }.mapNotNull { (k, _) ->
            val miss = ts.filterIndexed { i, _ -> cells[k]!![i] == null }.map { it.label }
            if (miss.isEmpty()) null else "$k ${miss.joinToString("/")}"
        }
        val note = listOfNotNull(
            if (forward) "${fmtYears(start)}-forward curve from each spot curve (Forward.rate)." else null,
            "Averages GDP-weighted (IMF, USD), over the countries with a value.",
            if (noCurve.isEmpty()) null else "— no curve: " + noCurve.joinToString(", "),
            if (gaps.isEmpty()) null else "— no point at tenor: " + gaps.joinToString(", "),
        ).joinToString(" ")
        val asOf = got.values.flatMap { it.got.points }.maxOfOrNull { it.date }.orEmpty()
        return PanelData(panel.columns, rows, emptyList(), emptyList(), asOf, note,
            servedBy = got.values.map { it.by }.filter { it.isNotEmpty() }.distinct().joinToString(" + "))
    }

    private fun fmtYears(y: Double) = if (y == Math.floor(y)) "${y.toInt()}y" else "${y}y"

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
}
