package com.diegonmarcos.superapp.bottomnav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * The user's runtime choice of how each section's child pages are navigated, and whether the side menu is expanded or a rail. The
 * declared mode (`ui.sections[].nav_mode`) is the default; an entry here overrides it per section. Pure: the Android store
 * ([NavModePrefs]) and the tests implement the same interface.
 */
public interface NavModeStore {
    /** The user's override for [sectionId], or null (the declaration stands). */
    public fun mode(sectionId: String): SecondRowMode?
    public fun setMode(sectionId: String, mode: SecondRowMode?)

    /** True: the side menu shows icon + label; false: the semi-collapsed rail; null: never chosen (a wide screen then docks the menu, a narrow one the rail). Remembered. */
    public fun sideExpanded(): Boolean?
    public fun setSideExpanded(expanded: Boolean)
}

/** An in-memory [NavModeStore] (tests, previews); its reads are Compose state, so a change redraws. */
public class MemoryNavModeStore(expanded: Boolean? = null) : NavModeStore {
    private val modes = androidx.compose.runtime.mutableStateMapOf<String, SecondRowMode>()
    private var expanded: Boolean? by androidx.compose.runtime.mutableStateOf(expanded)
    override fun mode(sectionId: String): SecondRowMode? = modes[sectionId]
    override fun setMode(sectionId: String, mode: SecondRowMode?) { if (mode == null) modes.remove(sectionId) else modes[sectionId] = mode }
    override fun sideExpanded(): Boolean? = expanded
    override fun setSideExpanded(expanded: Boolean) { this.expanded = expanded }
}

/** [section] drawn in the mode the user chose, else the one it declares. */
public fun NavSection.effective(store: NavModeStore?): NavSection {
    val chosen = store?.mode(id) ?: return this
    return if (chosen == rows) this else copy(rows = chosen)
}

/** How the side menu is on screen right now: gone (HIDDEN scroll state), the semi-collapsed rail, or the full menu. */
public enum class SideMenuPresence { Away, Rail, Expanded }

/**
 * The side menu follows the chrome's scroll machine like every other bar: HIDDEN takes it away, SHRUNK folds it to the rail (the
 * non-selected names are hidden), DEFAULT shows what the user chose ([expandedPref]).
 */
public fun sideMenuPresence(expandedPref: Boolean, scroll: ChromeScrollState): SideMenuPresence = when (scroll) {
    ChromeScrollState.Hidden -> SideMenuPresence.Away
    ChromeScrollState.Shrunk -> SideMenuPresence.Rail
    ChromeScrollState.Default -> if (expandedPref) SideMenuPresence.Expanded else SideMenuPresence.Rail
}

/** The rail's caption under an icon: the first [n] letters of the name (whole code points), trimmed. */
public fun railLabel(label: String, n: Int = 3): String {
    val t = label.trim()
    val out = StringBuilder()
    var count = 0
    var i = 0
    while (i < t.length && count < n) {
        val cp = t.codePointAtCompat(i)
        out.append(t.substring(i, i + cpLen(cp)))
        i += cpLen(cp)
        count++
    }
    return out.toString()
}

private fun cpLen(cp: Int): Int = if (cp >= 0x10000) 2 else 1

private fun String.codePointAtCompat(i: Int): Int {
    val hi = this[i]
    if (hi.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
        return 0x10000 + ((hi.code - 0xD800) shl 10) + (this[i + 1].code - 0xDC00)
    }
    return hi.code
}

/** One row of the side menu: a group heading, or a page at an indent [depth]. */
public data class SideMenuItem(val id: String, val label: String, val icon: String, val depth: Int, val heading: Boolean = false, val page: NavPage? = null)

/**
 * The rows of the side menu for [section]: with declared groups, each group is a heading followed by its pages; otherwise the pages
 * depth-first (a container's children indented). Pure, so the menu's content is testable without drawing.
 */
public fun sideMenuItems(section: NavSection): List<SideMenuItem> {
    val out = ArrayList<SideMenuItem>()
    fun walk(pages: List<NavPage>, depth: Int) {
        for (p in pages) { out += SideMenuItem(p.id, p.label, p.icon, depth, page = p); walk(p.pages, depth + 1) }
    }
    if (section.groups.isNotEmpty()) {
        for (g in section.rowGroups()) {
            out += SideMenuItem("group:" + g.id, g.label, g.icon, 0, heading = true)
            walk(g.pages, 1)
        }
    } else walk(section.pages, 0)
    return out
}
