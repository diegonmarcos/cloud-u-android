package com.diegonmarcos.cloudcalc

import org.json.JSONArray
import org.json.JSONObject

/**
 * build.json::ui, decoded once. app/build.gradle bakes ui.tabs and ui.modes into BuildConfig
 * as base64 JSON; this object is their only reader, so no tab id, mode id, key, form or
 * category is spelled anywhere in Kotlin. [parseTabs] / [parseModes] are pure so the JVM suite
 * runs them against this repository's own build.json.
 */
object Declarations {
    /** #770 a top-level section (Calculator, Measure, Jev): the bottom nav shows its tabs. */
    data class Section(val id: String, val label: String, val icon: String)
    data class Tab(val id: String, val label: String, val icon: String, val section: String)

    /** A keypad key: [label] is drawn, [insert] is typed. AC, DEL and = are the three actions. */
    data class Key(val label: String, val insert: String) {
        val action: Action? get() = when (label) {
            "AC" -> Action.CLEAR
            "DEL" -> Action.DELETE
            "=" -> Action.EVALUATE
            else -> null
        }
    }
    enum class Action { CLEAR, DELETE, EVALUATE }

    data class Field(val id: String, val label: String, val default: String)
    data class Output(val label: String, val expr: String)
    data class Form(val id: String, val label: String, val fields: List<Field>, val outputs: List<Output>)

    /** One chip of a choice row: picking it sets eval option [key] to [value]. */
    data class Choice(val label: String, val key: String, val value: Int)
    data class CatalogSource(val kind: String, val category: String)
    data class Plot(val default: String, val xmin: Double, val xmax: Double, val steps: Int)
    data class Meter(
        val sampleRate: Int, val fftSize: Int, val refreshMs: Int,
        val calibrationDb: Double, val aWeighting: Boolean,
    )

    data class Mode(
        val id: String,
        val label: String,
        val tab: String,
        val kind: String,
        /** The engine's eval options, as the JSON the engine parses (libs/calc EvalOptions). */
        val options: String,
        val keys: List<List<Key>>,
        val angleChoices: List<Choice>,
        val baseChoices: List<Choice>,
        val showBases: List<Choice>,
        val forms: List<Form>,
        val categories: List<String>,
        val rates: Boolean,
        val defaults: Map<String, String>,
        val catalog: List<CatalogSource>,
        val plot: Plot?,
        val meter: Meter?,
        val historyMax: Int,
        /** A Clock mode's `clock` object as JSON (#768); ClockDecl reads it, nothing else does. */
        val clock: String,
    )

    val sections: List<Section> by lazy { parseSections(decode(BuildConfig.UI_SECTIONS_B64)) }
    val tabs: List<Tab> by lazy { parseTabs(decode(BuildConfig.UI_TABS_B64)) }
    val modes: List<Mode> by lazy { parseModes(decode(BuildConfig.UI_MODES_B64)) }
    val defaultTab: String get() = BuildConfig.UI_DEFAULT_TAB

    fun modesOf(tab: String): List<Mode> = modes.filter { it.tab == tab }
    fun tabsOf(section: String): List<Tab> = tabs.filter { it.section == section }
    fun sectionOf(tab: String): String = tabs.firstOrNull { it.id == tab }?.section ?: sections.first().id
    fun mode(id: String): Mode? = modes.firstOrNull { it.id == id }

    private fun decode(b64: String): String = String(java.util.Base64.getDecoder().decode(b64), Charsets.UTF_8)

    fun parseSections(json: String): List<Section> = JSONArray(json).objects().map {
        Section(it.getString("id"), it.getString("label"), it.optString("icon"))
    }

    fun parseTabs(json: String): List<Tab> = JSONArray(json).objects().map {
        Tab(it.getString("id"), it.getString("label"), it.optString("icon"), it.getString("section"))
    }

    fun parseModes(json: String): List<Mode> = JSONArray(json).objects().map { m ->
        Mode(
            id = m.getString("id"),
            label = m.getString("label"),
            tab = m.getString("tab"),
            kind = m.getString("kind"),
            options = (m.optJSONObject("options") ?: JSONObject()).toString(),
            keys = (m.optJSONArray("keys") ?: JSONArray()).let { rows ->
                (0 until rows.length()).map { r -> rows.getJSONArray(r).let { row -> (0 until row.length()).map { key(row.get(it)) } } }
            },
            angleChoices = choices(m.optJSONArray("angle_choices")),
            baseChoices = choices(m.optJSONArray("base_choices")),
            showBases = choices(m.optJSONArray("show_bases")),
            forms = (m.optJSONArray("forms") ?: JSONArray()).objects().map { f ->
                Form(
                    f.getString("id"), f.getString("label"),
                    f.getJSONArray("fields").objects().map { Field(it.getString("id"), it.getString("label"), it.optString("default")) },
                    f.getJSONArray("outputs").objects().map { Output(it.getString("label"), it.getString("expr")) },
                )
            },
            categories = (m.optJSONArray("categories") ?: JSONArray()).strings(),
            rates = m.optBoolean("rates", false),
            defaults = (m.optJSONObject("default") ?: JSONObject()).let { d ->
                d.keys().asSequence().associateWith { d.get(it).toString() }
            },
            catalog = (m.optJSONArray("catalog") ?: JSONArray()).objects().map {
                CatalogSource(it.getString("kind"), it.getString("category"))
            },
            plot = m.optJSONObject("plot")?.let {
                Plot(it.optString("default"), it.optDouble("xmin", -10.0), it.optDouble("xmax", 10.0), it.optInt("steps", 200))
            },
            meter = m.optJSONObject("meter")?.let {
                Meter(
                    it.optInt("sample_rate", 44100), it.optInt("fft_size", 4096), it.optInt("refresh_ms", 100),
                    it.optDouble("calibration_db", 0.0), it.optBoolean("a_weighting", true),
                )
            },
            historyMax = m.optJSONObject("history")?.optInt("max_entries", 200) ?: 200,
            clock = (m.optJSONObject("clock") ?: JSONObject()).toString(),
        )
    }

    private fun key(v: Any): Key =
        if (v is JSONObject) Key(v.getString("label"), v.optString("insert")) else Key(v.toString(), v.toString())

    /** A choice object is {label, <option>: value}; the one non-label field names the option. */
    private fun choices(a: JSONArray?): List<Choice> = (a ?: JSONArray()).objects().map { c ->
        val k = c.keys().asSequence().first { it != "label" }
        Choice(c.getString("label"), k, c.getInt(k))
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
}
