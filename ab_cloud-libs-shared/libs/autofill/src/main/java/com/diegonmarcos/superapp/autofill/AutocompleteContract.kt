package com.diegonmarcos.superapp.autofill

import org.json.JSONArray
import org.json.JSONObject

/** #1103 Cloud Account to Cloud Keyboard: the Autocomplete rows over Account's `accountdata` provider (CONSTELLATION_DATA, grant `autocomplete.read`). */
object AutocompleteContract {
    const val AUTHORITY = "com.diegonmarcos.cloudaccount.accountdata"
    const val METHOD = "autocomplete"
    const val GRANT = "autocomplete.read"
    const val KEY_OK = "ok"
    const val KEY_JSON = "autocomplete_json"
    /** The pages, in tab order. */
    val PAGES = listOf("ids", "addresses", "urls", "secrets")
    const val MAX_PER_PAGE = 500

    data class Row(val page: String, val label: String, val value: String)

    /** `{"v":1,"ids":[{"label","value"}],"addresses":[...],"urls":[...],"secrets":[...]}`; unknown pages, blank values and rows past the cap are dropped. */
    fun parse(json: String?): List<Row> {
        val o = runCatching { JSONObject(json.orEmpty()) }.getOrNull() ?: return emptyList()
        return PAGES.flatMap { p ->
            val a = o.optJSONArray(p) ?: JSONArray()
            (0 until minOf(a.length(), MAX_PER_PAGE)).mapNotNull { i ->
                a.optJSONObject(i)?.let { Row(p, it.optString("label").trim(), it.optString("value")) }?.takeIf { it.value.isNotBlank() }
            }
        }
    }

    fun toJson(rows: List<Row>): String = JSONObject().put("v", 1).also { o ->
        PAGES.forEach { p ->
            o.put(p, JSONArray().also { a -> rows.filter { it.page == p }.take(MAX_PER_PAGE).forEach { a.put(JSONObject().put("label", it.label).put("value", it.value)) } })
        }
    }.toString()
}
