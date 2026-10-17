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
        assertTrue("width ${s.widthPx}", s.widthPx <= (EdgeHandleWindow.MAX_THICKNESS_DP * d).toInt())
        assertEquals((EdgeHandleWindow.MAX_LENGTH_DP * d).toInt(), s.heightPx)
    }

    /** REGRESSION GUARD: a thin declared/pref'd strip is raised to the minimum hit area, never 0 or 8dp. */
    @Test fun thinDeclaredSizeIsRaisedToTheMinimumHitArea() {
        val s = EdgeHandleWindow.size(OneHandConfig.Edge.LEFT, (100 * d).toInt(), (8 * d).toInt(), d)
        assertEquals((EdgeHandleWindow.MIN_HIT_DP * d).toInt(), s.widthPx); assertEquals(300, s.heightPx)
        val zero = EdgeHandleWindow.size(OneHandConfig.Edge.RIGHT, 0, 0, d)
        assertTrue(zero.widthPx >= (26 * d).toInt() && zero.heightPx > 0)
    }

    @Test fun hitAreaIsAtLeast26dpAndDefaultIs28() {
        assertTrue(EdgeHandleWindow.MIN_HIT_DP >= 26)
        assertTrue(EdgeHandleWindow.MAX_THICKNESS_DP >= EdgeHandleWindow.MIN_HIT_DP)
        assertTrue(OneHandPrefs.STRIP_DEFAULT_DP >= 26)
        assertTrue(OneHandPrefs.STRIP_MIN_DP >= 26)
    }

    @Test fun bottomEdgeIsWideAndShort() {
        val s = EdgeHandleWindow.size(OneHandConfig.Edge.BOTTOM, 1080, (40 * d).toInt(), d)
        assertTrue(s.heightPx <= (EdgeHandleWindow.MAX_THICKNESS_DP * d).toInt()); assertEquals((160 * d).toInt(), s.widthPx)
    }

    @Test fun stripSpansTheSystemBackGestureBand() {
        // ~24dp is the AOSP back-gesture inset: a thinner border strip is wholly inside it and never reachable.
        assertTrue(EdgeHandleWindow.MAX_THICKNESS_DP >= 26)
        val s = EdgeHandleWindow.size(OneHandConfig.Edge.LEFT, 400, (32 * d).toInt(), d)
        assertEquals((EdgeHandleWindow.MAX_THICKNESS_DP * d).toInt(), s.widthPx)
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
