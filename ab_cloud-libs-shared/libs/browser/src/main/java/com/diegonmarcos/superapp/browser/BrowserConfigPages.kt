package com.diegonmarcos.superapp.browser

import org.json.JSONArray
import org.json.JSONObject

/** One GLOBAL action row of a Configs page (build.json::ui.browser.configs.rows[], or an add-on entry with scope:global). */
data class BrowserConfigRow(
    val id: String,
    val label: String,
    /** The Configs page (a settings_sections id = a top tab) the row sits on. */
    val page: String,
    /** Facts that must all be true for the row to be usable (resolved like the page menu's). */
    val requires: List<String> = emptyList(),
)

/**
 * #900 THE CONFIGS PAGES AS DATA. Configs is a nav section whose `pages` are the top tabs
 * (ui.sections[configs].pages, drawn by libs:bottomnav PageTabs); each tab is one id of
 * build.json::ui.browser.settings_sections. A page is: the catalogue settings whose `section` is
 * its id, then its [rows] (global actions that used to sit in the page dropdown), then its
 * [native] blocks (the Autocomplete, Passwords, Cards, Vault and About sections libs:browser
 * draws itself). Nothing here names a setting or a page: add one to build.json and it appears.
 * Pure: no android.*, JVM-tested.
 */
class BrowserConfigPages(
    /** (id, title) in tab order. */
    val pages: List<Pair<String, String>>,
    val rows: List<BrowserConfigRow>,
    /** page id → native block ids, in order. */
    val native: Map<String, List<String>>,
) {
    fun title(page: String): String = pages.firstOrNull { it.first == page }?.second ?: page.replaceFirstChar { it.uppercase() }

    fun rowsFor(page: String): List<BrowserConfigRow> = rows.filter { it.page == page }

    fun nativeFor(page: String): List<String> = native[page].orEmpty()

    /** The first page, where Configs opens. */
    fun first(): String? = pages.firstOrNull()?.first

    /** Every row resolved against [facts]: enabled, and the words for why not. */
    fun resolve(page: String, facts: Map<String, Boolean>, whys: Map<String, String>): List<SheetRow> =
        rowsFor(page).map { r ->
            val missing = r.requires.firstOrNull { facts[it] != true }
            SheetRow(r.id, r.label, enabled = missing == null, why = missing?.let { whys[it] ?: "needs $it" })
        }

    companion object {
        val EMPTY = BrowserConfigPages(emptyList(), emptyList(), emptyMap())

        fun parse(configs: JSONObject?, pages: List<Pair<String, String>>, addons: BrowserAddons): BrowserConfigPages {
            val declared = configs?.optJSONArray("rows").objects().map {
                BrowserConfigRow(
                    id = it.optString("id").trim(), label = it.optString("label", it.optString("id")), page = it.optString("page"),
                    requires = it.optJSONArray("requires").let { a -> if (a == null) emptyList() else (0 until a.length()).map { i -> a.optString(i) } },
                )
            }
            val native = HashMap<String, List<String>>()
            configs?.optJSONObject("native")?.let { n ->
                n.keys().forEach { k -> native[k] = n.optJSONArray(k).let { a -> if (a == null) emptyList() else (0 until a.length()).map { i -> a.optString(i) } } }
            }
            val ids = pages.map { it.first }.toSet()
            // A row on no declared page would never be drawn: dropped here, loudly in the tests.
            val rows = (declared + addons.all.flatMap { it.configRows }).filter { it.id.isNotEmpty() && it.page in ids }
            return BrowserConfigPages(pages, rows, native.filterKeys { it in ids })
        }

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    }
}
