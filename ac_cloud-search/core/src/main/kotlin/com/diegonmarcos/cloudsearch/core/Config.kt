package com.diegonmarcos.cloudsearch.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * build.json::search, read once. It is the app's ONE declaration of what it shows and where the
 * data comes from: verticals, their subpages and filter chips, every source (an API it reads, a
 * site it only opens, or one it may not touch and why), feeds, web engines, calculators and the AI
 * chat. app/build.gradle bakes it into BuildConfig; the JVM suite reads the same file from disk.
 * Nothing here names a vertical, source or URL: they are all in the JSON.
 */
data class SearchConfig(
    val userAgent: String,
    val timeoutMs: Int,
    val cacheTtlMinutes: Int,
    val recentDays: Int,
    val maxResults: Int,
    val defaultVertical: String,
    val defaultCity: String,
    val cities: List<City>,
    val subpages: List<Subpage>,
    val verticals: List<Vertical>,
    val sources: Map<String, Source>,
    val feeds: Map<String, Feed>,
    val series: Map<String, Series>,
    val engines: List<Engine>,
    val calculators: Map<String, Calculator>,
    val social: Social,
    val ai: Ai,
) {
    data class City(val id: String, val label: String, val lat: Double, val lon: Double, val radiusKm: Int, val aliases: List<String>)
    data class Subpage(val id: String, val label: String, val kind: String)
    data class ChipSpec(val id: String, val label: String, val flag: String, val tag: String)
    data class Vertical(
        val id: String, val label: String, val title: String, val blurb: String, val icon: String,
        val placeholder: String, val subpages: List<String>, val sources: List<String>, val chips: List<ChipSpec>,
        val feeds: List<String>, val feedKeywords: List<String>, val calculators: List<String>, val analysis: String,
        val series: List<String>, val chart: String, val chartPoints: Int, val chartColor: String,
    )

    /**
     * kind `api`: fetched and parsed by [parser] when [enabled]. kind `link`: never fetched — the
     * site's own search opens in cloud-browser. [why] says why a source is a link or disabled; it
     * is shown to the user, so no source is silently missing.
     */
    data class Source(
        val id: String, val label: String, val kind: String, val enabled: Boolean, val parser: String,
        val url: String, val headers: Map<String, String>, val queryRequired: Boolean, val localFilter: Boolean,
        val cityFilter: Boolean, val verified: Boolean, val itemUrl: String, val why: String, val terms: String,
    ) {
        val fetches: Boolean get() = kind == KIND_API && enabled
    }

    data class Feed(val id: String, val label: String, val url: String)

    /** One official time series (a `market` analysis reads them); [change] is pct (an index) or pp (a rate). */
    data class Series(
        val id: String, val label: String, val source: String, val detail: String, val parser: String,
        val url: String, val headers: Map<String, String>, val unit: String, val change: String, val terms: String,
    )
    /** A Search-page engine box: [icon] is a Phosphor name, [accent] a colour name in the app's colors.xml. */
    data class Engine(val id: String, val label: String, val url: String, val icon: String, val accent: String)
    data class Field(val id: String, val label: String, val default: Double, val kind: String, val options: List<Pair<String, Double>>)
    /** [tone]: "" (a plain row), total, minus (a deduction), result (the answer) or detail (a sub-line). */
    data class Output(val id: String, val label: String, val format: String, val tone: String)
    data class Calculator(
        val id: String, val label: String, val blurb: String, val icon: String, val fields: List<Field>, val outputs: List<Output>,
        val notes: List<String>, val warnings: Map<String, String>,
    )

    /** The 2026 social-insurance parameters the payslip applies (rates in percent, limits in euro per year). */
    data class Social(
        val bbgKvPvYear: Double, val bbgRvAvYear: Double,
        val kvGeneralPct: Double, val rvPct: Double, val avPct: Double,
        val pvPct: Double, val pvChildlessPct: Double, val pvPerChildPct: Double, val pvMaxReductions: Int,
        val pvSaxonyEmployeePct: Double, val pvSaxonyEmployerPct: Double,
        val insolvencyPct: Double, val minijobLimitMonth: Double, val midijobUpperMonth: Double,
    )

    data class Ai(
        val accountProvider: String, val chatUrl: String, val modelsUrl: String, val defaultModel: String,
        val referer: String, val title: String, val timeoutMs: Int, val catalogTtlHours: Int, val historyTurns: Int,
        val webPlugin: String, val nativeWebParam: String, val titleChars: Int,
    )

    fun vertical(id: String): Vertical? = verticals.firstOrNull { it.id == id }
    fun city(id: String?): City = cities.firstOrNull { it.id == id } ?: cities.first { it.id == defaultCity }
    fun subpage(id: String): Subpage? = subpages.firstOrNull { it.id == id }

    companion object {
        const val KIND_API = "api"
        const val KIND_LINK = "link"

        /** What a vertical's Analysis page may compute: nothing, the jobs statistics, or official market series. */
        val ANALYSES = setOf("none", "jobs", "market")
        val CHANGES = setOf("pct", "pp")
        val TONES = setOf("", "total", "minus", "result", "detail")

        /** [json] is build.json::search. Throws on a declaration that would draw a broken app. */
        fun parse(json: String): SearchConfig = parse(JSONObject(json))

        fun parse(o: JSONObject): SearchConfig {
            val cfg = SearchConfig(
                userAgent = o.getString("user_agent"),
                timeoutMs = o.getInt("timeout_ms"),
                cacheTtlMinutes = o.getInt("cache_ttl_minutes"),
                recentDays = o.getInt("recent_days"),
                maxResults = o.getInt("max_results"),
                defaultVertical = o.getString("default_vertical"),
                defaultCity = o.getString("default_city"),
                cities = objects(o.getJSONArray("cities")).map {
                    City(it.getString("id"), it.getString("label"), it.getDouble("lat"), it.getDouble("lon"), it.getInt("radius_km"), strings(it.optJSONArray("aliases")))
                },
                subpages = objects(o.getJSONArray("subpages")).map { Subpage(it.getString("id"), it.getString("label"), it.getString("kind")) },
                verticals = objects(o.getJSONArray("verticals")).map { v ->
                    Vertical(
                        id = v.getString("id"), label = v.getString("label"), title = v.getString("title"),
                        blurb = v.optString("blurb"), icon = v.getString("icon"), placeholder = v.optString("placeholder"),
                        subpages = strings(v.getJSONArray("subpages")), sources = strings(v.optJSONArray("sources")),
                        chips = objects(v.optJSONArray("chips")).map { ChipSpec(it.getString("id"), it.getString("label"), it.optString("flag"), it.optString("tag")) },
                        feeds = strings(v.optJSONArray("feeds")), feedKeywords = strings(v.optJSONArray("feed_keywords")),
                        calculators = strings(v.optJSONArray("calculators")), analysis = v.optString("analysis", "none"),
                        series = strings(v.optJSONArray("series")), chart = v.optString("chart"), chartPoints = v.optInt("chart_points", 0),
                        chartColor = v.optString("chart_color"),
                    )
                },
                sources = members(o.getJSONObject("sources")).associate { (id, s) ->
                    id to Source(
                        id = id, label = s.getString("label"), kind = s.getString("kind"), enabled = s.optBoolean("enabled", false),
                        parser = s.optString("parser"), url = s.optString("url"), headers = stringMap(s.optJSONObject("headers")),
                        queryRequired = s.optBoolean("query_required", false), localFilter = s.optBoolean("local_filter", false),
                        cityFilter = s.optBoolean("city_filter", false), verified = s.optBoolean("verified", false),
                        itemUrl = s.optString("item_url"), why = s.optString("why"), terms = s.optString("terms"),
                    )
                },
                feeds = members(o.getJSONObject("feeds")).associate { (id, f) -> id to Feed(id, f.getString("label"), f.getString("url")) },
                series = (o.optJSONObject("series")?.let { members(it) } ?: emptyList()).associate { (id, x) ->
                    id to Series(
                        id = id, label = x.getString("label"), source = x.getString("source"), detail = x.optString("detail"),
                        parser = x.getString("parser"), url = x.getString("url"), headers = stringMap(x.optJSONObject("headers")),
                        unit = x.getString("unit"), change = x.getString("change"), terms = x.optString("terms"),
                    )
                },
                engines = objects(o.getJSONArray("engines")).map { Engine(it.getString("id"), it.getString("label"), it.getString("url"), it.getString("icon"), it.getString("accent")) },
                calculators = members(o.getJSONObject("calculators")).associate { (id, c) ->
                    id to Calculator(
                        id = id, label = c.getString("label"), blurb = c.optString("blurb"), icon = c.getString("icon"),
                        fields = objects(c.getJSONArray("fields")).map { f ->
                            Field(
                                f.getString("id"), f.getString("label"), f.getDouble("default"), f.optString("kind", "number"),
                                objects(f.optJSONArray("options")).map { it.getString("label") to it.getDouble("value") },
                            )
                        },
                        outputs = objects(c.getJSONArray("outputs")).map { Output(it.getString("id"), it.getString("label"), it.optString("format", "eur"), it.optString("tone")) },
                        notes = strings(c.optJSONArray("notes")),
                        warnings = stringMap(c.optJSONObject("warnings")),
                    )
                },
                social = o.getJSONObject("social_2026").let { s ->
                    Social(
                        bbgKvPvYear = s.getDouble("bbg_kv_pv_year"), bbgRvAvYear = s.getDouble("bbg_rv_av_year"),
                        kvGeneralPct = s.getDouble("kv_general_pct"), rvPct = s.getDouble("rv_pct"), avPct = s.getDouble("av_pct"),
                        pvPct = s.getDouble("pv_pct"), pvChildlessPct = s.getDouble("pv_childless_pct"),
                        pvPerChildPct = s.getDouble("pv_per_child_pct"), pvMaxReductions = s.getInt("pv_max_reductions"),
                        pvSaxonyEmployeePct = s.getDouble("pv_saxony_employee_pct"), pvSaxonyEmployerPct = s.getDouble("pv_saxony_employer_pct"),
                        insolvencyPct = s.getDouble("insolvency_pct"), minijobLimitMonth = s.getDouble("minijob_limit_month"),
                        midijobUpperMonth = s.getDouble("midijob_upper_month"),
                    )
                },
                ai = o.getJSONObject("ai").let { a ->
                    Ai(
                        accountProvider = a.getString("account_provider"), chatUrl = a.getString("chat_url"),
                        modelsUrl = a.getString("models_url"), defaultModel = a.getString("default_model"),
                        referer = a.getString("referer"), title = a.getString("title"), timeoutMs = a.getInt("timeout_ms"),
                        catalogTtlHours = a.getInt("catalog_ttl_hours"), historyTurns = a.getInt("history_turns"),
                        webPlugin = a.getString("web_plugin"), nativeWebParam = a.getString("native_web_param"),
                        titleChars = a.getInt("title_chars"),
                    )
                },
            )
            val problems = cfg.problems()
            require(problems.isEmpty()) { "build.json::search is inconsistent: " + problems.joinToString("; ") }
            return cfg
        }

        private fun objects(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it) }
        private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
        private fun members(o: JSONObject): List<Pair<String, JSONObject>> =
            o.keys().asSequence().filterNot { it.startsWith("_") }.sorted().map { it to o.getJSONObject(it) }.toList()
        private fun stringMap(o: JSONObject?): Map<String, String> =
            if (o == null) emptyMap() else o.keys().asSequence().associateWith { o.getString(it) }
    }

    /** Every cross-reference the declaration makes, checked: an id that points nowhere is a dead tab or a dead source. */
    fun problems(): List<String> {
        val bad = mutableListOf<String>()
        if (verticals.none { it.id == defaultVertical }) bad += "default_vertical $defaultVertical is not a vertical"
        if (cities.none { it.id == defaultCity }) bad += "default_city $defaultCity is not a city"
        if (verticals.map { it.id }.toSet().size != verticals.size) bad += "duplicate vertical ids"
        for (v in verticals) {
            if (v.subpages.isEmpty()) bad += "vertical ${v.id} has no subpage"
            v.subpages.filter { subpage(it) == null }.forEach { bad += "vertical ${v.id} names subpage $it, which is not declared" }
            v.sources.filter { it !in sources }.forEach { bad += "vertical ${v.id} names source $it, which is not declared" }
            v.feeds.filter { it !in feeds }.forEach { bad += "vertical ${v.id} names feed $it, which is not declared" }
            v.calculators.filter { it !in calculators }.forEach { bad += "vertical ${v.id} names calculator $it, which is not declared" }
            val kinds = v.subpages.mapNotNull { subpage(it)?.kind }
            if ("listing" in kinds && v.sources.isEmpty()) bad += "vertical ${v.id} has a listing but no source"
            if ("calculators" in kinds && v.calculators.isEmpty()) bad += "vertical ${v.id} has a calculators page but no calculator"
            if ("feed" in kinds && v.feeds.isEmpty()) bad += "vertical ${v.id} has a feed page but no feed"
            v.chips.filter { it.flag.isBlank() == it.tag.isBlank() }.forEach { bad += "chip ${v.id}/${it.id} must set exactly one of flag or tag" }
            if (v.analysis !in ANALYSES) bad += "vertical ${v.id} analysis ${v.analysis} is none of $ANALYSES"
            v.series.filter { it !in series }.forEach { bad += "vertical ${v.id} names series $it, which is not declared" }
            if (v.analysis == "market") {
                if (v.series.isEmpty()) bad += "vertical ${v.id} has a market analysis but no series"
                if (v.chart !in v.series) bad += "vertical ${v.id} charts ${v.chart.ifBlank { "nothing" }}, which is not one of its series"
                if (v.chartPoints < 2) bad += "vertical ${v.id} chart_points must be at least 2"
            }
            if (v.analysis != "none" && v.chartColor.isBlank()) bad += "vertical ${v.id} has an analysis chart but no chart_color"
        }
        for (x in series.values) {
            if (x.change !in CHANGES) bad += "series ${x.id} change ${x.change} is none of $CHANGES"
        }
        for (s in sources.values) {
            if (s.kind != KIND_API && s.kind != KIND_LINK) bad += "source ${s.id} kind ${s.kind} is neither api nor link"
            if (s.kind == KIND_API && s.enabled && s.parser.isBlank()) bad += "source ${s.id} is enabled but names no parser"
            if (s.enabled && s.url.isBlank()) bad += "source ${s.id} is enabled but has no url"
            if ((s.kind == KIND_LINK || !s.enabled) && s.why.isBlank()) bad += "source ${s.id} is not fetched but does not say why"
        }
        for (c in calculators.values) {
            if (c.outputs.isEmpty()) bad += "calculator ${c.id} declares no output"
            c.outputs.filter { it.tone !in TONES }.forEach { bad += "calculator ${c.id} output ${it.id} tone ${it.tone} is none of $TONES" }
            c.fields.filter { it.kind == "choice" && it.options.none { o -> o.second == it.default } }
                .forEach { bad += "calculator ${c.id} field ${it.id} default is not one of its options" }
        }
        return bad
    }
}
