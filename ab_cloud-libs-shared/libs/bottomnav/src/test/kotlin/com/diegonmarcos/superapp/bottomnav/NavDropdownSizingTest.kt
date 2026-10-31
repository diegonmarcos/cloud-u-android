package com.diegonmarcos.superapp.bottomnav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The page-list dropdown's height rule ([NavDropdownSizing]): content height, capped at the room below the anchor. Plain JVM. */
class NavDropdownSizingTest {
    private val t = NavTokens()
    private val margin = t.dropdownScreenMargin.value.toInt()
    private val min = t.dropdownMinHeight.value.toInt()

    // portrait phone at 1x: 800 tall, 24 status bar, 48 gesture bar, anchor (the title) ends at 80
    private fun cap(window: Int, anchor: Int = 80, top: Int = 24, bottom: Int = 48) =
        NavDropdownSizing.maxHeightPx(window, anchor, top, bottom, margin, min)

    @Test fun `the tokens are the declared margin and floor`() {
        assertEquals(16, margin); assertEquals(96, min)
    }

    @Test fun `the cap is the window below the anchor, minus the bottom bar and the margin`() {
        assertEquals(800 - 80 - 48 - 16, cap(800))
    }

    @Test fun `content smaller than the cap is drawn at its own height and does not scroll`() {
        val c = cap(800)
        assertEquals(200, NavDropdownSizing.heightPx(200, c))
        assertFalse(NavDropdownSizing.scrolls(200, c))
    }

    @Test fun `content larger than the cap is drawn at the cap and scrolls, never a small fixed height`() {
        val c = cap(800)
        assertEquals(c, NavDropdownSizing.heightPx(2000, c))
        assertTrue(NavDropdownSizing.scrolls(2000, c))
        assertTrue("a long list gets more than the old 520 cap on a tall window", NavDropdownSizing.heightPx(2000, cap(1400)) > 520)
        assertFalse(NavDropdownSizing.scrolls(c, c))
    }

    @Test fun `rotation re-caps it, landscape has less room than portrait and the list still fits on screen`() {
        val portrait = cap(800)
        val landscape = cap(400, anchor = 70, top = 24, bottom = 0)
        assertEquals(400 - 70 - 0 - 16, landscape)
        assertTrue(landscape < portrait)
        assertTrue("its bottom stays above the bottom bar + margin", 70 + NavDropdownSizing.heightPx(2000, landscape) <= 400 - 0 - margin)
        assertEquals("the same list does not scroll in portrait", 300, NavDropdownSizing.heightPx(300, portrait))
        assertTrue("but scrolls in landscape", NavDropdownSizing.scrolls(330, landscape))
    }

    @Test fun `split screen with the anchor low keeps the floor, and the popup is lifted to stay on screen`() {
        val c = cap(300, anchor = 250, top = 0, bottom = 0)
        assertEquals("floor", min, c)
        assertEquals(300 - 0 - 16 - min, NavDropdownSizing.topPx(250, min, 300, 0, margin))
        assertEquals("a popup that fits stays under the anchor", 80, NavDropdownSizing.topPx(80, 200, 800, 48, margin))
    }

    @Test fun `the anchor above the status bar is measured from the bar, and a tiny window never goes negative`() {
        assertEquals(800 - 24 - 48 - 16, cap(800, anchor = 0))
        assertEquals(0, cap(50, anchor = 40, top = 24, bottom = 20))
        assertEquals(0, NavDropdownSizing.topPx(10, 500, 300, 0, margin))
    }
}
