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
class PrivateSession(private val profileSupported: Boolean = false) {
    private val origins = HashSet<String>()

    /**
     * [deleteProfile]: the isolated [PrivateProfile.NAME] profile goes, taking its cookies, storage
     * and cache with it; then nothing is left in the default jar to clear.
     */
    data class ClosePlan(val clearOrigins: Set<String>, val clearSessionCookies: Boolean, val deleteProfile: Boolean = false)

    /** A page of [tab] finished loading; true = the caller may record it (history, preview). */
    fun visit(tab: BrowserTab?, origin: String?): Boolean {
        if (BrowserSitePolicy.shouldRecord(tab)) return true
        if (!origin.isNullOrBlank()) origins.add(origin)
        return false
    }

    /** [closed] was just removed; [left] are the tabs still open. Null = nothing to clear yet. */
    fun close(closed: BrowserTab, left: List<BrowserTab>): ClosePlan? {
        if (!closed.isPrivate || left.any { it.isPrivate }) return null
        if (profileSupported) { origins.clear(); return ClosePlan(emptySet(), clearSessionCookies = false, deleteProfile = true) }
        return ClosePlan(origins.toSet(), clearSessionCookies = left.isEmpty()).also { origins.clear() }
    }
}

/**
 * Full isolation for private tabs where the WebView implements androidx.webkit multi-profile
 * (WebViewFeature.MULTI_PROFILE): a private tab runs in the [NAME] profile, with its own cookie jar,
 * storage and cache; a normal tab never does. Pure decisions only, the WebView calls live in the host.
 * setProfile must run before the WebView loads anything.
 */
object PrivateProfile {
    const val NAME = "incognito"
    const val NOTICE = "Private tabs share cookies on this WebView version; update Android System WebView for full isolation"

    /** The profile [tab] must be bound to, or null for the default one (normal tab, or no multi-profile). */
    fun nameFor(tab: BrowserTab?, supported: Boolean): String? = if (supported && tab?.isPrivate == true) NAME else null

    /** The one-line notice shows only in the incognito grid, only where isolation is unavailable. */
    fun showNotice(supported: Boolean, filter: BrowserTabsBar.Filter): Boolean =
        !supported && filter == BrowserTabsBar.Filter.INCOGNITO

    /** Which jar a request reads: the profile's when [name] is a profile, else the default one. */
    fun <T> jarFor(name: String?, profileJar: (String) -> T, defaultJar: () -> T): T =
        if (name != null) profileJar(name) else defaultJar()

    /** At start: a profile left by a previous run is deleted when no private tab survives. */
    fun staleAtStart(supported: Boolean, tabs: List<BrowserTab>): Boolean = supported && tabs.none { it.isPrivate }
}
