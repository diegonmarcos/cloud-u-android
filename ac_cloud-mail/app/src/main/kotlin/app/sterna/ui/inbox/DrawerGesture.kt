package app.sterna.ui.inbox

import app.sterna.core.data.settings.SwipeAction

/*
 * Who owns a horizontal drag on a list row: the row (swipe actions) or the navigation drawer (#30).
 * The drawer's drag covers the whole content area and only loses because the rows consume every
 * horizontal drag first, so handing one back is enough.
 */

/** Width of the start-edge strip the rows hand back to the drawer, in dp. Deliberately narrow:
 *  wider would swallow legitimate action swipes started near the edge. */
internal const val DRAWER_EDGE_BAND_DP = 20

/** Is the start edge free for the drawer? Only in three-button navigation: gesture navigation
 *  reserves it and reports a non-zero system-gesture inset, which is how the two are told apart. */
internal fun edgeIsFree(systemGestureInsetPx: Int): Boolean = systemGestureInsetPx <= 0

/** Effective width of the strip: [bandPx] where the edge is free, 0 otherwise — and 0 where there
 *  is no drawer to slide ([drawerCanOpen] false, from 1 200 dp), or it would hand the action swipe
 *  of every row begun near the edge to a drawer that cannot move. */
internal fun drawerBandPx(systemGestureInsetPx: Int, bandPx: Float, drawerCanOpen: Boolean): Float =
    if (drawerCanOpen && edgeIsFree(systemGestureInsetPx)) bandPx.coerceAtLeast(0f) else 0f

/** Does a touch landing at [xInWindowPx] (window coordinates, so indented conversation children
 *  measure from the same edge as top-level rows) start inside the strip? */
internal fun startsInDrawerBand(xInWindowPx: Float, bandPx: Float): Boolean =
    bandPx > 0f && xInWindowPx >= 0f && xInWindowPx < bandPx

/** Does the row keep a horizontal drag of [dx], or hand it to the drawer? With [drawerCanOpen]
 *  false the row keeps every drag: a direction with no action then does nothing instead of falling
 *  through unconsumed. */
internal fun rowKeepsDrag(
    dx: Float,
    rightAction: SwipeAction,
    leftAction: SwipeAction,
    drawerCanOpen: Boolean,
): Boolean = when {
    !drawerCanOpen -> true
    dx >= 0f -> rightAction != SwipeAction.NONE
    else -> leftAction != SwipeAction.NONE
}
