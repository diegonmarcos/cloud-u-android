package com.diegonmarcos.superapp.browser

/**
 * One open browser tab.
 *
 * Deliberately free of every Android type. The ordering, pinning and
 * grouping rules below are the whole point of tabs 1-3 and they are
 * reachable from a plain JVM unit test only while this file imports
 * nothing from android.*. Persistence lives next door in
 * [BrowserTabPrefs]; this file is what that persistence stores.
 */
data class BrowserTab(
    val url: String,
    val title: String,
    val ts: Long,
    val previewPath: String = "",
    /** Sorts ahead of every unpinned tab and refuses [BrowserTabPrefs.remove]. */
    val pinned: Boolean = false,
    /**
     * Explicit drag order. [UNSET_ORDER] means "never dragged", which is
     * every tab that existed before drag-to-reorder shipped — those fall
     * through to recency so an upgrade does not shuffle anyone's tabs.
     */
    val order: Int = UNSET_ORDER,
    /** Group name, "" for ungrouped. One field, so a tab is in at most one group. */
    val group: String = "",
) {
    companion object {
        const val UNSET_ORDER: Int = Int.MAX_VALUE
    }
}

/**
 * The order the tab grid draws, and the constraint a drag may not break.
 *
 * Pure functions over lists. Every rule item 1 and item 2 asked for is
 * decided here so it can be asserted without an emulator.
 */
object BrowserTabOrder {

    /**
     * Pinned first, then explicit drag order, then most-recent-first.
     *
     * The pinned-first key is what makes pinning mean something in the
     * grid; [canMove] is what stops a drag from undoing it.
     */
    fun sort(tabs: List<BrowserTab>): List<BrowserTab> =
        tabs.sortedWith(
            compareByDescending<BrowserTab> { it.pinned }
                .thenBy { it.order }
                .thenByDescending { it.ts }
        )

    /**
     * May the tab at [from] be dropped at [to]?
     *
     * Only within one pinned class, and only within one group.
     *
     * The pinned rule is what makes pinning mean anything: an unpinned
     * tab dragged into the pinned block would sort straight back out on
     * the next redraw, so allowing the gesture would be a lie about what
     * happened.
     *
     * The group rule is the same argument — [sort] draws each group as
     * its own run, so a cross-group drop could not survive a redraw
     * either. Changing a tab's group is an explicit menu action.
     */
    fun canMove(tabs: List<BrowserTab>, from: Int, to: Int): Boolean {
        if (from !in tabs.indices || to !in tabs.indices) return false
        if (from == to) return false
        return tabs[from].pinned == tabs[to].pinned &&
            tabs[from].group == tabs[to].group
    }

    /**
     * Apply a drag. Returns the list unchanged when [canMove] refuses, so
     * a rejected drag is a no-op rather than a silent partial move.
     */
    fun move(tabs: List<BrowserTab>, from: Int, to: Int): List<BrowserTab> {
        if (!canMove(tabs, from, to)) return tabs
        val out = tabs.toMutableList()
        out.add(to, out.removeAt(from))
        return stamp(out)
    }

    /**
     * Freeze a visual order into explicit indices.
     *
     * Without this a reorder would persist nothing: [BrowserTab.order]
     * defaults to UNSET and recency would reassert itself on restart.
     */
    fun stamp(tabs: List<BrowserTab>): List<BrowserTab> =
        tabs.mapIndexed { i, t -> t.copy(order = i) }

    /** Distinct group names in draw order, ungrouped ("") excluded. */
    fun groups(tabs: List<BrowserTab>): List<String> =
        sort(tabs).map { it.group }.filter { it.isNotBlank() }.distinct()
}

/**
 * What the FIRST RUN pins, and — more importantly — what every later run
 * does not.
 *
 * Pure so the "never resurrect a closed default" rule is assertable
 * without a device. The rest of [BrowserTabPrefs.seedOnce] is just the
 * flag read and the write.
 */
object BrowserSeed {

    /**
     * The tabs to add, pinned and in the order [configUrls] lists them.
     *
     * EMPTY once [alreadySeeded] is true, whatever the config says and
     * whatever is currently open. That is the whole rule: if he closes
     * or unpins one of the defaults, the next launch leaves it closed.
     * A default that comes back every launch is an irritation.
     *
     * Also empty for a URL that is already open, so seeding cannot
     * duplicate a tab he opened himself before the flag was set.
     */
    fun plan(
        alreadySeeded: Boolean,
        openUrls: Set<String>,
        configUrls: List<String>,
        now: Long,
    ): List<BrowserTab> {
        if (alreadySeeded) return emptyList()
        return configUrls
            .mapIndexed { i, url -> i to url.trim() }
            .filter { (_, url) -> url.isNotEmpty() && url !in openUrls }
            .map { (i, url) -> BrowserTab(url, url, now + i, pinned = true, order = i) }
    }
}

/**
 * List operations whose RULES have to hold no matter which screen calls
 * them. Pure, so the rules are assertable rather than merely stated.
 */
object BrowserTabOps {

    /**
     * Close [url], or refuse.
     *
     * @return the remaining tabs, or NULL if the tab is pinned or absent.
     *
     * Null rather than "the list unchanged" on purpose: a caller that
     * ignores the distinction still cannot silently drop a pinned tab,
     * and one that honours it can say why nothing happened. This is the
     * constraint item 2 is actually about — not the flag, the refusal.
     */
    fun close(tabs: List<BrowserTab>, url: String): List<BrowserTab>? {
        val tab = tabs.firstOrNull { it.url == url } ?: return null
        if (tab.pinned) return null
        return tabs.filterNot { it.url == url }
    }
}
