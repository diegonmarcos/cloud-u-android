package com.diegonmarcos.superapp.themebg

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared theme: Cloud Search's palette, a subtle gradient (not flat black), accessible ink, the SuperApp violet. */
class ThemeBackgroundTest {
    private fun spread(c: Color) = maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)

    @Test fun `the dark page is Cloud Search's slate gradient, not flat black`() {
        val d = ThemeBackground.colors(true)
        assertEquals(Color(0xFF0F172A), d.top)
        assertEquals(Color(0xFF1E293B), d.bottom)
        assertNotEquals(Color.Black, d.top)
        assertNotEquals(d.top, d.bottom)
    }

    @Test fun `the gradient is subtle in both modes`() {
        for (dark in listOf(true, false)) {
            val c = ThemeBackground.colors(dark)
            val ratio = ThemeBackground.contrast(c.top, c.bottom)
            assertTrue("gradient ends contrast $ratio (dark=$dark) should be subtle", ratio < 1.5)
        }
    }

    @Test fun `ink and dim ink are accessible on both ends of the page`() {
        for (dark in listOf(true, false)) {
            val c = ThemeBackground.colors(dark)
            for (end in listOf(c.top, c.bottom)) {
                assertTrue("ink on page dark=$dark", ThemeBackground.contrast(c.ink, end) >= 7.0)
                assertTrue("dim ink on page dark=$dark", ThemeBackground.contrast(c.inkDim, end) >= 4.5)
            }
        }
    }

    @Test fun `the island is a darker-gray surface than the page with readable text`() {
        for (dark in listOf(true, false)) {
            val c = ThemeBackground.colors(dark)
            assertTrue(ThemeBackground.contrast(c.onIsland, c.island) >= 7.0)
            assertTrue("island is gray-ish, not coloured", spread(c.island) < 0.2f)
        }
        val d = ThemeBackground.colors(true)
        assertTrue("dark island is darker than the page top", d.island.red + d.island.green + d.island.blue < d.top.red + d.top.green + d.top.blue)
    }

    @Test fun `the selected tab is the opaque SuperApp violet with accessible text, not gray`() {
        assertEquals(Color(0xFF7C3AED), ThemeBackground.TAB_SELECTED)
        val d = ThemeBackground.colors(true)
        assertEquals(1f, d.tabSelected.alpha, 0f)
        assertTrue("violet, not gray", spread(d.tabSelected) > 0.5f)
        assertTrue(ThemeBackground.contrast(d.onTabSelected, d.tabSelected) >= 4.5)
        val l = ThemeBackground.colors(false)
        assertTrue(spread(l.tabSelected) > 0.5f)
        assertTrue(ThemeBackground.contrast(l.onTabSelected, l.tabSelected) >= 4.5)
    }

    @Test fun `contrast helper matches the WCAG extremes`() {
        assertEquals(21.0, ThemeBackground.contrast(Color.White, Color.Black), 0.01)
        assertEquals(1.0, ThemeBackground.contrast(Color.White, Color.White), 0.0001)
    }
}
