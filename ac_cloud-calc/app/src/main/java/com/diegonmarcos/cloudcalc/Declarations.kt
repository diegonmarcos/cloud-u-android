package com.diegonmarcos.cloudcalc

import com.diegonmarcos.superapp.bottomnav.NavDecl
import org.json.JSONArray
import org.json.JSONObject

/**
 * build.json::ui, decoded once. app/build.gradle bakes ui.bottom_nav + ui.sections (with their
 * `pages`) + ui.default_section and ui.modes into BuildConfig; this object is their only reader,
 * so no section id, page id, mode id, key, form or category is spelled anywhere in Kotlin. #868
 * the navigation is libs:bottomnav's [NavDecl]: the sections are the island, a section's pages
 * are the old "tabs". [parseNav] / [parseModes] are pure so the JVM suite runs them against this
 * repository's own build.json.
 */
object Declarations {
    /** #770/#868 a top-level section (Calculator, Measure, Jev): a bottom-nav item; its pages (tabs) are the top strip. */
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
    /** #772 the sample rate and the calibration offset are build.json::sound's, shared by every sound mode. */
    data class Meter(val fftSize: Int, val refreshMs: Int, val aWeighting: Boolean)

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
        /** The currencies pinned first, shown in the matrix and kept fresh (build.json::ui.modes[currency].favourites). */
        val favourites: List<String>,
        val defaults: Map<String, String>,
        val catalog: List<CatalogSource>,
        val plot: Plot?,
        val meter: Meter?,
        val historyMax: Int,
        /** A Clock mode's `clock` object as JSON (#768); ClockDecl reads it, nothing else does. */
        val clock: String,
        /** The mode-row icon name (IconCatalog) and its short label; blank = the page's icon and the label. */
        val icon: String = "",
        val short: String = "",
    )

    val nav: NavDecl by lazy {
        NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
    }
    /** The bar's sections, in `ui.bottom_nav` order. */
    val sections: List<Section> by lazy { sectionsOf(nav) }
    /** Every page of every section, in declared order (what #770 called ui.tabs). */
    val tabs: List<Tab> by lazy { tabsIn(nav) }
    val modes: List<Mode> by lazy { parseModes(decode(BuildConfig.UI_MODES_B64)) }
    /** Where the app opens: the default section's first page. */
    val defaultTab: String get() = nav.default()?.pages?.firstOrNull()?.id ?: tabs.first().id

    fun modesOf(tab: String): List<Mode> = modes.filter { it.tab == tab }
    fun tabsOf(section: String): List<Tab> = tabs.filter { it.section == section }
    fun sectionOf(tab: String): String = tabs.firstOrNull { it.id == tab }?.section ?: sections.first().id
    fun mode(id: String): Mode? = modes.firstOrNull { it.id == id }

    private fun decode(b64: String): String = String(java.util.Base64.getDecoder().decode(b64), Charsets.UTF_8)

    fun sectionsOf(nav: NavDecl): List<Section> = nav.bottomSections().map { Section(it.id, it.label, it.icon) }

    fun tabsIn(nav: NavDecl): List<Tab> =
        nav.sections.flatMap { s -> s.pages.map { Tab(it.id, it.label, it.icon, s.id) } }

    /** [sections] is ui.sections' JSON, [bottomNav] ui.bottom_nav as a comma list. */
    fun parseNav(sections: String, bottomNav: String, defaultSection: String): NavDecl =
        NavDecl.parse(JSONArray(sections), bottomNav, defaultSection)

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
            favourites = (m.optJSONArray("favourites") ?: JSONArray()).strings(),
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
                Meter(it.optInt("fft_size", 4096), it.optInt("refresh_ms", 100), it.optBoolean("a_weighting", true))
            },
            historyMax = m.optJSONObject("history")?.optInt("max_entries", 200) ?: 200,
            clock = (m.optJSONObject("clock") ?: JSONObject()).toString(),
            icon = m.optString("icon"),
            short = m.optString("short"),
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
