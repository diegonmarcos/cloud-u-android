package com.diegonmarcos.superapp.browser

import kotlin.math.abs

/**
 * Swipe-to-close on a tab card, as rules (no android.*, JVM-tested in BrowserTabCardTest).
 *
 * The grid already owns two gestures: vertical scroll (the RecyclerView) and long-press drag (#886,
 * reorder / drop-on-tab / drop-on-group). A horizontal swipe is the third, so it is allowed only when
 * it cannot be either of those: movement must clear the touch slop, be clearly horizontal, and no drag
 * may be in progress. ItemTouchHelper applies the same dominance rule itself before it selects a swipe;
 * [lock] is that rule written down, and the grid also uses it to keep parents from stealing a swipe.
 */
object SwipeGesture {
    enum class Axis { HORIZONTAL, VERTICAL, NONE }

    /** Fraction of the card's width a swipe must travel to count as a close. */
    const val THRESHOLD = 0.4f
    /** Horizontal movement must beat vertical by this factor to lock to a swipe. */
    const val DOMINANCE = 1.5f

    fun lock(dx: Float, dy: Float, slop: Float, dragging: Boolean): Axis {
        if (dragging) return Axis.NONE           // a long-press drag always wins: no close mid-drag
        val ax = abs(dx); val ay = abs(dy)
        if (ax < slop && ay < slop) return Axis.NONE
        return if (ax >= ay * DOMINANCE) Axis.HORIZONTAL else if (ay >= ax) Axis.VERTICAL else Axis.NONE
    }

    /** Only tab cards swipe; group headers (a divider, not a tab) do not. */
    fun swipeable(row: BrowserGridRow?): Boolean = row is BrowserGridRow.TabCard

    /**
     * A pinned tab is never closed by one swipe. Choice: CONFIRM, not resist-then-unpin. A swipe cannot tell an
     * accidental flick from intent, so the card snaps back and the owner confirms "Unpin and close". Resist-then-unpin
     * would need a custom partial-drag state that fights scroll and drag, and abandoning it halfway leaves a tab
     * silently unpinned; a confirm either happens or leaves the tab exactly as it was.
     */
    fun needsConfirm(tab: BrowserTab): Boolean = tab.pinned
}

/**
 * A swipe-closed tab waits here for the undo window. Nothing about it is deleted or torn down until
 * [commit]: undo restores the very same record, and its saved page state and preview are still on disk.
 * The private-profile teardown of [PrivateSession] runs at commit, so undoing the last incognito tab
 * never meets a profile that is already gone.
 */
class SwipeClose(private val session: PrivateSession) {
    data class Commit(val tab: BrowserTab, val plan: PrivateSession.ClosePlan?)

    var pending: BrowserTab? = null
        private set

    /** [tab] was just removed from the store; it is held for undo. A previous pending one must be committed first. */
    fun begin(tab: BrowserTab) { check(pending == null) { "commit the previous close first" }; pending = tab }

    /** Undo: the tab comes back as it was. */
    fun undo(tabs: List<BrowserTab>): List<BrowserTab> {
        val t = pending ?: return tabs
        pending = null
        return BrowserTabOps.restore(tabs, t)
    }

    /** The window is over. [left] = tabs still open. Returns what to delete (state, preview) and the private teardown plan. */
    fun commit(left: List<BrowserTab>): Commit? {
        val t = pending ?: return null
        pending = null
        return Commit(t, session.close(t, left))
    }
}
