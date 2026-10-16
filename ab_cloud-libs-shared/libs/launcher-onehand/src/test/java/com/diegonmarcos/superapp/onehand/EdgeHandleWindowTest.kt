package com.diegonmarcos.superapp.onehand

import android.view.WindowManager.LayoutParams as LP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The idle window is the only touchable thing we own: thin, short, NOT_TOUCH_MODAL. */
class EdgeHandleWindowTest {
    private val d = 3f // density

    @Test fun declaredFullHeightWideStripIsClampedThin() {
        val s = EdgeHandleWindow.size(OneHandConfig.Edge.RIGHT, lengthPx = 2400, thicknessPx = (32 * d).toInt(), density = d)
        assertTrue("width ${s.widthPx}", s.widthPx <= (24 * d).toInt())
        assertEquals((EdgeHandleWindow.MAX_LENGTH_DP * d).toInt(), s.heightPx)
    }

    @Test fun smallDeclaredSizeIsKept() {
        val s = EdgeHandleWindow.size(OneHandConfig.Edge.LEFT, (100 * d).toInt(), (8 * d).toInt(), d)
        assertEquals(24, s.widthPx); assertEquals(300, s.heightPx)
    }

    @Test fun bottomEdgeIsWideAndShort() {
        val s = EdgeHandleWindow.size(OneHandConfig.Edge.BOTTOM, 1080, (40 * d).toInt(), d)
        assertTrue(s.heightPx <= (24 * d).toInt()); assertEquals((160 * d).toInt(), s.widthPx)
    }

    @Test fun stripSpansTheSystemBackGestureBand() {
        // ~24dp is the AOSP back-gesture inset: a thinner border strip is wholly inside it and never reachable.
        assertTrue(EdgeHandleWindow.MAX_THICKNESS_DP >= 24)
        val s = EdgeHandleWindow.size(OneHandConfig.Edge.LEFT, 400, (32 * d).toInt(), d)
        assertEquals((24 * d).toInt(), s.widthPx)
    }

    @Test fun idleFlags() {
        val f = EdgeHandleWindow.FLAGS
        assertTrue(f and LP.FLAG_NOT_TOUCH_MODAL != 0)
        assertTrue(f and LP.FLAG_NOT_FOCUSABLE != 0)
        assertEquals(0, f and LP.FLAG_NOT_TOUCHABLE) // the strip itself must be touchable
    }

    @Test fun previewVeilIsNeverTouchable() {
        val f = EdgeHandleWindow.PREVIEW_FLAGS
        assertTrue(f and LP.FLAG_NOT_TOUCHABLE != 0)
        assertTrue(f and LP.FLAG_NOT_TOUCH_MODAL != 0)
    }
}
