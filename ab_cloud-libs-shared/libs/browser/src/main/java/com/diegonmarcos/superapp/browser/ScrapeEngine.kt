package com.diegonmarcos.superapp.browser

import org.json.JSONArray
import org.json.JSONObject

/**
 * #802 the Web Scraper's pure half: what to extract ([Plan]), and what to do with the rows
 * (merge pages, drop duplicates, cap, CSV/JSON). The device half runs assets/browser/scrape.js
 * on each page; this decides everything that can be decided without a page. JVM-tested.
 */
object ScrapeEngine {

    /** The last finished scrape ({url, columns, rows, pages}), for /api/browser/scraper/last and Export. */
    @Volatile var last: JSONObject? = null

    /** One column: rows come from [css]; the cell is the element's text, or its [attr]. */
    data class Column(val name: String, val css: String, val attr: String? = null)

    data class Plan(val columns: List<Column>, val nextCss: String? = null, val maxPages: Int = 1) {
        fun toJson(): JSONObject = JSONObject()
            .put("columns", JSONArray(columns.map { JSONObject().put("name", it.name).put("css", it.css).put("attr", it.attr ?: JSONObject.NULL) }))
            .put("next_css", nextCss ?: JSONObject.NULL)
    }

    /** The plan a one-selector request means: one `text` column, plus `attr` when asked. */
    fun simple(css: String, attr: String?, pages: Int, nextCss: String?, cap: Int): Plan {
        val cols = listOf(Column("text", css)) + listOfNotNull(attr?.takeIf { it.isNotBlank() }?.let { Column(it, css, it) })
        return Plan(cols, nextCss?.takeIf { it.isNotBlank() }, pages.coerceIn(1, cap))
    }

    /** Pages' rows into one table: in order, a row seen before dropped, at most [maxRows]. */
    fun merge(pages: List<List<Map<String, String>>>, maxRows: Int): List<Map<String, String>> =
        pages.flatten().distinct().take(maxRows)

    /** A page's scrape.js answer → rows (missing cells are ""). */
    fun rows(page: JSONObject?, plan: Plan): List<Map<String, String>> {
        val arr = page?.optJSONArray("rows") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { o ->
            plan.columns.associate { c -> c.name to o.optString(c.name) }
        }
    }

    /** RFC 4180: a cell with a comma, quote or newline is quoted, quotes doubled. */
    fun csv(rows: List<Map<String, String>>, columns: List<String>): String {
        fun cell(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        return (listOf(columns.joinToString(",") { cell(it) }) + rows.map { r -> columns.joinToString(",") { cell(r[it].orEmpty()) } })
            .joinToString("\n") + "\n"
    }

    fun json(rows: List<Map<String, String>>): JSONArray = JSONArray(rows.map { JSONObject(it) })
}
