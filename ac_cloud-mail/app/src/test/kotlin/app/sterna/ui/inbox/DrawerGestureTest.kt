package app.sterna.ui.inbox

import app.sterna.core.data.settings.SwipeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gesture ownership between a list row and the navigation drawer (Codeberg #30). */
class DrawerGestureTest {

    // ---- is the start edge free? (three-button nav vs gesture nav) ----

    @Test fun `edge is free when the system claims no gesture inset (three-button nav)`() {
        assertTrue(edgeIsFree(0))
    }

    @Test fun `edge is not free under gesture navigation`() {
        assertFalse(edgeIsFree(48))
        assertFalse(edgeIsFree(1))
    }

    @Test fun `the strip has its width only where the edge is free`() {
        assertEquals(60f, drawerBandPx(systemGestureInsetPx = 0, bandPx = 60f, drawerCanOpen = true), 0f)
    }

    @Test fun `the strip is inert under gesture navigation`() {
        assertEquals(0f, drawerBandPx(systemGestureInsetPx = 48, bandPx = 60f, drawerCanOpen = true), 0f)
    }

    // ---- does a drag start in the strip? ----

    @Test fun `a touch on the very edge starts in the strip`() {
        assertTrue(startsInDrawerBand(xInWindowPx = 0f, bandPx = 60f))
        assertTrue(startsInDrawerBand(xInWindowPx = 59.9f, bandPx = 60f))
    }

    @Test fun `a touch past the strip does not`() {
        assertFalse(startsInDrawerBand(xInWindowPx = 60f, bandPx = 60f))
        assertFalse(startsInDrawerBand(xInWindowPx = 400f, bandPx = 60f))
    }

    @Test fun `an inert strip never matches, even at the edge`() {
        assertFalse(startsInDrawerBand(xInWindowPx = 0f, bandPx = 0f))
    }

    @Test fun `the strip is measured from the window edge, so an indented child row agrees`() {
        // A conversation child sits 16dp in; the row adds its own window offset, so a
        // touch at 40px from the screen edge is inside a 60px strip either way.
        val topLevelRowLeft = 0f
        val childRowLeft = 42f
        assertTrue(startsInDrawerBand(topLevelRowLeft + 40f, bandPx = 60f))
        assertFalse(startsInDrawerBand(childRowLeft + 40f, bandPx = 60f))
    }

    // ---- does the row keep the drag, or hand it to the drawer? ----

    @Test fun `a right swipe with no action assigned goes to the drawer`() {
        assertFalse(rowKeepsDrag(dx = 30f, rightAction = SwipeAction.NONE, leftAction = SwipeAction.DELETE, drawerCanOpen = true))
    }

    @Test fun `a right swipe with an action stays on the row`() {
        assertTrue(rowKeepsDrag(dx = 30f, rightAction = SwipeAction.TOGGLE_READ, leftAction = SwipeAction.NONE, drawerCanOpen = true))
    }

    @Test fun `a left swipe is judged on the left action, not the right one`() {
        assertTrue(rowKeepsDrag(dx = -30f, rightAction = SwipeAction.NONE, leftAction = SwipeAction.DELETE, drawerCanOpen = true))
        assertFalse(rowKeepsDrag(dx = -30f, rightAction = SwipeAction.DELETE, leftAction = SwipeAction.NONE, drawerCanOpen = true))
    }

    @Test fun `the default configuration keeps both directions on the row`() {
        assertTrue(rowKeepsDrag(dx = 30f, rightAction = SwipeAction.TOGGLE_READ, leftAction = SwipeAction.DELETE, drawerCanOpen = true))
        assertTrue(rowKeepsDrag(dx = -30f, rightAction = SwipeAction.TOGGLE_READ, leftAction = SwipeAction.DELETE, drawerCanOpen = true))
    }

    @Test fun `with both directions unassigned the row never takes a drag`() {
        assertFalse(rowKeepsDrag(dx = 30f, rightAction = SwipeAction.NONE, leftAction = SwipeAction.NONE, drawerCanOpen = true))
        assertFalse(rowKeepsDrag(dx = -30f, rightAction = SwipeAction.NONE, leftAction = SwipeAction.NONE, drawerCanOpen = true))
    }

    // ---- nothing to open: the permanent drawer from 1 200 dp (#103, volet 6) ----

    @Test fun `the strip is inert when the drawer is permanent`() {
        assertEquals(
            "from 1 200 dp the folder drawer is drawn beside the list and there is nothing to " +
                "slide open: a strip that still gave the start edge away would swallow the " +
                "action swipe of every row begun near the left edge and hand it to a drawer " +
                "that cannot move",
            0f,
            drawerBandPx(systemGestureInsetPx = 0, bandPx = 60f, drawerCanOpen = false),
            0f,
        )
    }

    @Test fun `the strip keeps its width while the drawer can open`() {
        assertEquals(
            "under 1 200 dp the modal drawer is unchanged, to the pixel: the strip is still the " +
                "only way to drag it open in three-button navigation",
            60f,
            drawerBandPx(systemGestureInsetPx = 0, bandPx = 60f, drawerCanOpen = true),
            0f,
        )
    }

    @Test fun `a row keeps every drag when there is no drawer to open`() {
        assertTrue(
            "swipe right = none, permanent drawer: handing the drag back opens nothing, so the " +
                "row keeps it and it does nothing (or the list scrolls). Unconsumed instead, the " +
                "gesture falls through to a drawer that is already on screen",
            rowKeepsDrag(dx = 10f, rightAction = SwipeAction.NONE, leftAction = SwipeAction.ARCHIVE, drawerCanOpen = false),
        )
        assertTrue(
            "and the same in the other direction: swipe left = none with the drawer permanent",
            rowKeepsDrag(dx = -10f, rightAction = SwipeAction.ARCHIVE, leftAction = SwipeAction.NONE, drawerCanOpen = false),
        )
    }

    @Test fun `a dead direction still goes to the drawer while the drawer can open`() {
        assertFalse(
            "under 1 200 dp nothing changes: with no action on the right, swiping right anywhere " +
                "on the list is how the modal drawer is opened (#30)",
            rowKeepsDrag(dx = 10f, rightAction = SwipeAction.NONE, leftAction = SwipeAction.ARCHIVE, drawerCanOpen = true),
        )
    }

    @Test fun `the strip stays narrow enough not to swallow action swipes`() {
        assertTrue(DRAWER_EDGE_BAND_DP in 1..24)
    }
}
