package com.diegonmarcos.capitalfinterm

import org.json.JSONObject

/** One panel of a dashboard (data/dashboards.json). It is live (`fetch`), a static layout (`alloc`) or a placeholder (`placeholder`). */
data class Panel(
    val id: String,
    val title: String,
    val source: String,
    val view: String,
    val derive: String,
    val columns: List<String>,
    val unit: String,
    val fetch: JSONObject?,
    val placeholderColumns: List<String>,
    val placeholderRows: Int,
    /** The panel's whole declaration, for the options a derivation reads (chart_value, change_unit). */
    val raw: JSONObject,
) {
    val isLive: Boolean get() = fetch != null
    val isStatic: Boolean get() = fetch == null && placeholderColumns.isEmpty() && view == Dashboards.VIEW_ALLOC
}

/** One dashboard: the FinTerm entry it belongs to (tab id + entry title) and its panels. */
data class Dashboard(val id: String, val entry: String, val tab: String, val legs: List<Pair<String, Int>>, val panels: List<Panel>)

/** What a dashboard shows right now, by its panels and their sources. */
enum class DashStatus { LIVE, PARTLY_LIVE, NEEDS_KEY, LAYOUT_ONLY }

object Dashboards {
    const val VIEW_CURVE = "curve"
    const val VIEW_BARS = "bars"
    const val VIEW_TABLE = "table"
    const val VIEW_NEWS = "news"
    const val VIEW_ALLOC = "alloc"
    val VIEWS: Set<String> = setOf(VIEW_CURVE, VIEW_BARS, VIEW_TABLE, VIEW_NEWS, VIEW_ALLOC)

    fun parse(json: String): List<Dashboard> {
        val arr = runCatching { JSONObject(json).optJSONArray("dashboards") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { d ->
            val legsArr = d.optJSONObject("model_portfolio")?.optJSONArray("legs")
            val legs = if (legsArr == null) emptyList() else (0 until legsArr.length()).mapNotNull { legsArr.optJSONArray(it) }.map { it.optString(0) to it.optInt(1) }
            val panels = d.optJSONArray("panels")
            Dashboard(
                id = Catalog.str(d, "id"), entry = Catalog.str(d, "entry"), tab = Catalog.str(d, "tab"), legs = legs,
                panels = if (panels == null) emptyList() else (0 until panels.length()).mapNotNull { panels.optJSONObject(it) }.map { panel(it) },
            )
        }
    }

    fun byId(list: List<Dashboard>, id: String): Dashboard? = list.firstOrNull { it.id == id }

    /** Live when every panel is live or static; partly when some are; else needs a key when a placeholder's source wants one. */
    fun status(d: Dashboard, sources: List<Source>): DashStatus {
        val live = d.panels.count { it.isLive || it.isStatic }
        if (live == d.panels.size && live > 0) return DashStatus.LIVE
        val liveFetch = d.panels.count { it.isLive }
        if (liveFetch > 0) return DashStatus.PARTLY_LIVE
        val keyed = d.panels.any { p -> !p.isLive && !p.isStatic && Catalog.byId(sources, p.source)?.needsKey == true }
        return if (keyed) DashStatus.NEEDS_KEY else DashStatus.LAYOUT_ONLY
    }

    private fun panel(o: JSONObject): Panel {
        val ph = o.optJSONObject("placeholder")
        return Panel(
            id = Catalog.str(o, "id"), title = Catalog.str(o, "title"), source = Catalog.str(o, "source"), view = Catalog.str(o, "view"),
            derive = Catalog.str(o, "derive"), columns = list(o, "columns"), unit = Catalog.str(o, "unit"),
            fetch = o.optJSONObject("fetch"),
            placeholderColumns = if (ph == null) emptyList() else list(ph, "columns"), placeholderRows = ph?.optInt("rows", 0) ?: 0, raw = o,
        )
    }

    private fun list(o: JSONObject, key: String): List<String> {
        val a = o.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }
    }
}
