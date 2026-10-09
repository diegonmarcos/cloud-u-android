package com.diegonmarcos.superapp.browser

/**
 * The Tabs view's top row, as data (no android.*, so JVM-tested in BrowserIncognitoTabsTest):
 *
 *   New Tab, New Incognito │ Normal Tabs, Incognito Tabs │ History
 *
 * "│" is a bare inline divider ([Item.Sep]): it has no click target, no background, no icon cell.
 * Normal Tabs / Incognito Tabs are a toggle ([Filter]) over the grid. Incognito reuses the #802
 * private tab ([BrowserTab.isPrivate]); nothing here invents a second private mode.
 */
object BrowserTabsBar {
    const val SEP = "│"

    enum class Filter { NORMAL, INCOGNITO }
    enum class Id { NEW_TAB, NEW_INCOGNITO, NORMAL, INCOGNITO, HISTORY }

    sealed class Item {
        data class Action(val id: Id, val label: String, val active: Boolean = false) : Item()
        object Sep : Item()
    }

    fun items(filter: Filter): List<Item> = listOf(
        Item.Action(Id.NEW_TAB, "New Tab"),
        Item.Action(Id.NEW_INCOGNITO, "New Incognito"),
        Item.Sep,
        Item.Action(Id.NORMAL, "Normal Tabs", filter == Filter.NORMAL),
        Item.Action(Id.INCOGNITO, "Incognito Tabs", filter == Filter.INCOGNITO),
        Item.Sep,
        Item.Action(Id.HISTORY, "History"),
    )

    /** The row read as text: buttons space-separated, dividers as [SEP]. */
    fun text(filter: Filter): String =
        items(filter).joinToString(" ") { if (it is Item.Action) it.label else SEP }

    /** The grid shows one kind at a time. */
    fun filter(tabs: List<BrowserTab>, f: Filter): List<BrowserTab> =
        tabs.filter { it.isPrivate == (f == Filter.INCOGNITO) }

    fun count(tabs: List<BrowserTab>, f: Filter): Int = filter(tabs, f).size
}

/**
 * What closing a tab must undo. Normal tabs: nothing beyond their saved state. A private tab never
 * reaches history/previews ([BrowserSitePolicy.shouldRecord]); the last one to close also drops the
 * site storage of every origin it visited and, when no normal tab is open to lose them, the session
 * cookies (cookies are process-wide in Android WebView, see [BrowserClearData.endPrivateSession]).
 */
class PrivateSession {
    private val origins = HashSet<String>()

    data class ClosePlan(val clearOrigins: Set<String>, val clearSessionCookies: Boolean)

    /** A page of [tab] finished loading; true = the caller may record it (history, preview). */
    fun visit(tab: BrowserTab?, origin: String?): Boolean {
        if (BrowserSitePolicy.shouldRecord(tab)) return true
        if (!origin.isNullOrBlank()) origins.add(origin)
        return false
    }

    /** [closed] was just removed; [left] are the tabs still open. Null = nothing to clear yet. */
    fun close(closed: BrowserTab, left: List<BrowserTab>): ClosePlan? {
        if (!closed.isPrivate || left.any { it.isPrivate }) return null
        return ClosePlan(origins.toSet(), clearSessionCookies = left.isEmpty()).also { origins.clear() }
    }
}
