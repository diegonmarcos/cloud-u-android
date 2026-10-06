package com.diegonmarcos.superapp.browser

import java.net.URI

/**
 * #886 tab groups as pure rules: what a drop does, what a group is called and coloured, how it
 * is renamed. [BrowserTab.group] stays the one field (a tab is in at most one group); the colour
 * of a group is the only extra fact, kept in a name → ARGB map ([BrowserTabPrefs.groupColors]).
 *
 * THE DROP RULES (long-press-drag a tab, release it over another):
 *  - over an UNGROUPED tab: a new group holding both, named by [nameFor], coloured by [nextColor];
 *  - over a tab that is in a group, or over that group's header: the dragged tab joins that group;
 *  - over a tab of the SAME group: not a regroup (the grid reorders instead) -> null;
 *  - over itself: null.
 * A group that loses its last tab simply stops existing, and its colour is pruned.
 */
object BrowserTabGroups {

    /** Eight distinguishable accents, ARGB; a new group takes the first one no group uses. */
    val PALETTE: List<Int> = listOf(
        0xFF7C3AED.toInt(), 0xFF2563EB.toInt(), 0xFF059669.toInt(), 0xFFD97706.toInt(),
        0xFFDC2626.toInt(), 0xFFDB2777.toInt(), 0xFF0891B2.toInt(), 0xFF65A30D.toInt(),
    )

    /** The outcome of a drop: the new tab list and colour map, the group involved, and whether it is new. */
    data class Change(val tabs: List<BrowserTab>, val colors: Map<String, Int>, val group: String, val created: Boolean)

    /** A readable site label for a url: `https://www.github.com/x` -> `Github`; "" when there is no host. */
    fun siteLabel(url: String): String {
        val host = runCatching { URI(url).host }.getOrNull().orEmpty().lowercase().removePrefix("www.")
        val parts = host.split('.').filter { it.isNotEmpty() }
        val core = when {
            parts.isEmpty() -> ""
            parts.size == 1 -> parts[0]
            else -> parts[parts.size - 2]
        }
        return core.replaceFirstChar { it.uppercase() }
    }

    /** `Github` for two github pages, `Github & Wikipedia` for two sites, `Group` when neither has a host. */
    fun baseName(a: BrowserTab, b: BrowserTab): String {
        val x = siteLabel(a.url); val y = siteLabel(b.url)
        return when {
            x.isEmpty() && y.isEmpty() -> "Group"
            x == y || y.isEmpty() -> x
            x.isEmpty() -> y
            else -> "$x & $y"
        }
    }

    /** [base], or `base 2`, `base 3`… so a new group never lands on a name already in use. */
    fun unique(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        var n = 2
        while ("$base $n" in taken) n++
        return "$base $n"
    }

    fun nameFor(a: BrowserTab, b: BrowserTab, taken: Set<String>) = unique(baseName(a, b), taken)

    /** The first palette colour no existing group uses, else the palette cycled by group count. */
    fun nextColor(colors: Map<String, Int>, groups: Collection<String>): Int {
        val used = groups.mapNotNull { colors[it] }.toSet()
        return PALETTE.firstOrNull { it !in used } ?: PALETTE[groups.size % PALETTE.size]
    }

    /** A group's colour: its stored one, else a stable palette pick by name (a group from before colours). */
    fun colorOf(colors: Map<String, Int>, group: String): Int =
        colors[group] ?: PALETTE[(group.hashCode() and 0x7fffffff) % PALETTE.size]

    private fun groupsOf(tabs: List<BrowserTab>) = tabs.map { it.group }.filter { it.isNotBlank() }.toSet()

    private fun pruned(tabs: List<BrowserTab>, colors: Map<String, Int>): Map<String, Int> {
        val live = groupsOf(tabs)
        return colors.filterKeys { it in live }
    }

    /** [dragId] dropped on the tab [targetId]. Null when this is not a regroup (see the rules above). */
    fun dropOnTab(tabs: List<BrowserTab>, colors: Map<String, Int>, dragId: String, targetId: String): Change? {
        val drag = tabs.firstOrNull { it.key == dragId } ?: return null
        val target = tabs.firstOrNull { it.key == targetId } ?: return null
        if (drag.key == target.key) return null
        if (target.group.isNotBlank()) {
            if (drag.group == target.group) return null
            return join(tabs, colors, dragId, target.group)
        }
        val taken = groupsOf(tabs)
        val name = nameFor(target, drag, taken)
        val color = nextColor(colors, taken)
        val next = tabs.map { if (it.key == dragId || it.key == targetId) it.copy(group = name, id = it.key) else it }
        return Change(next, pruned(next, colors + (name to color)), name, created = true)
    }

    /** [dragId] dropped on [group]'s header (or sent to it from a menu). Null when it is already there. */
    fun dropOnGroup(tabs: List<BrowserTab>, colors: Map<String, Int>, dragId: String, group: String): Change? {
        val drag = tabs.firstOrNull { it.key == dragId } ?: return null
        if (group.isBlank() || drag.group == group) return null
        return join(tabs, colors, dragId, group)
    }

    private fun join(tabs: List<BrowserTab>, colors: Map<String, Int>, dragId: String, group: String): Change {
        val next = tabs.map { if (it.key == dragId) it.copy(group = group, id = it.key) else it }
        return Change(next, pruned(next, colors + (group to colorOf(colors, group))), group, created = false)
    }

    /** A NEW tab opened inside [group] (the strip's +): same group, same colour. */
    fun withGroup(tab: BrowserTab, group: String) = tab.copy(group = group)

    /**
     * The strip's + on [current]: a tab already in a group joins that group; one that is not starts a
     * group holding it and [fresh] (so ANY page can start a group). Returns the change with [fresh]
     * placed in the group; [fresh] must already be in [tabs].
     */
    fun startOrJoin(tabs: List<BrowserTab>, colors: Map<String, Int>, currentId: String, freshId: String): Change? {
        val cur = tabs.firstOrNull { it.key == currentId } ?: return null
        return if (cur.group.isNotBlank()) dropOnGroup(tabs, colors, freshId, cur.group)
        else dropOnTab(tabs, colors, freshId, currentId)
    }

    /** Rename [from] to [to] (and recolour it when [color] is given). Refused (null) for a blank or taken [to]. */
    fun rename(tabs: List<BrowserTab>, colors: Map<String, Int>, from: String, to: String, color: Int? = null): Change? {
        val name = to.trim()
        if (name.isEmpty() || from !in groupsOf(tabs)) return null
        if (name != from && name in groupsOf(tabs)) return null
        val kept = colorOf(colors, from)
        val next = tabs.map { if (it.group == from) it.copy(group = name, id = it.key) else it }
        return Change(next, pruned(next, colors - from + (name to (color ?: kept))), name, created = false)
    }

    /** Dissolve [group]: its tabs stay open, ungrouped. */
    fun ungroup(tabs: List<BrowserTab>, colors: Map<String, Int>, group: String): Change {
        val next = tabs.map { if (it.group == group) it.copy(group = "", id = it.key) else it }
        return Change(next, pruned(next, colors), "", created = false)
    }

    /**
     * What the drag is hovering: the id of the card (or the group name of the header) whose rectangle
     * holds ([x], [y]), skipping [dragKey]. Pure geometry so the hit rule is tested without a view.
     */
    data class Slot(val tabKey: String?, val group: String?, val left: Int, val top: Int, val right: Int, val bottom: Int)

    fun hit(slots: List<Slot>, x: Int, y: Int, dragKey: String): Slot? =
        slots.firstOrNull { it.tabKey != dragKey && x >= it.left && x < it.right && y >= it.top && y < it.bottom }
}
