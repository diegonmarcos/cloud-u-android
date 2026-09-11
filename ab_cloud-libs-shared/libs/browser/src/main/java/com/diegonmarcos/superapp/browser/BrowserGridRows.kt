package com.diegonmarcos.superapp.browser

/**
 * A row in the tab grid. Groups (item 3) are drawn as a spanning header
 * followed by that group's cards, which is why the grid is a list of
 * rows rather than a list of tabs.
 */
sealed class BrowserGridRow {
    data class GroupHeader(
        val group: String,
        val collapsed: Boolean,
        val count: Int,
    ) : BrowserGridRow()

    data class TabCard(val tab: BrowserTab) : BrowserGridRow()
}

/**
 * Flattens tabs + collapse state into what the grid draws.
 *
 * Pure, so the collapse rule and the section order are assertable
 * without inflating a view.
 *
 * Ungrouped tabs come first — they are the common case and a browser
 * that buried them under group headers would be worse than one with no
 * groups at all. Each group then follows as a header plus its cards,
 * and a collapsed group contributes its header ONLY.
 */
object BrowserGridRows {

    fun build(tabs: List<BrowserTab>, collapsed: Set<String>): List<BrowserGridRow> {
        val sorted = BrowserTabOrder.sort(tabs)
        val out = ArrayList<BrowserGridRow>(sorted.size)

        for (t in sorted.filter { it.group.isBlank() }) {
            out.add(BrowserGridRow.TabCard(t))
        }
        for (g in BrowserTabOrder.groups(sorted)) {
            val members = sorted.filter { it.group == g }
            val isCollapsed = g in collapsed
            out.add(BrowserGridRow.GroupHeader(g, isCollapsed, members.size))
            if (!isCollapsed) members.forEach { out.add(BrowserGridRow.TabCard(it)) }
        }
        return out
    }

    /** The tabs [build] drew, in drawn order — the list a drag reorders. */
    fun visibleTabs(rows: List<BrowserGridRow>): List<BrowserTab> =
        rows.filterIsInstance<BrowserGridRow.TabCard>().map { it.tab }
}
