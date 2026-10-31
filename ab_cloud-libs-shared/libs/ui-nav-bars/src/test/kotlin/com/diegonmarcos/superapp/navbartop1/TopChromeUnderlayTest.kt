package com.diegonmarcos.superapp.navbartop1

import androidx.compose.ui.graphics.Color
import com.diegonmarcos.superapp.themebg.ThemeBackground
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scaffold paints [topChromeUnderlay] behind the transparent first row and the status-bar band. It must be opaque (a
 * translucent fill lets the host's light window through again), dark (the status icons are light), and hold the row's
 * onIsland ink at WCAG AA. Mutation: nav-mutation.yml `top-underlay` swaps it for the light Material surface; this goes red.
 */
class TopChromeUnderlayTest {
    /** Material 3's default light surface: what a WebView host's window showed behind the bar. */
    private val m3LightSurface = Color(0xFFFEF7FF)

    private fun readable(fill: Color, ink: Color): Boolean =
        fill.alpha == 1f && ThemeBackground.contrast(ink, fill) >= 4.5 && ThemeBackground.contrast(Color.White, fill) >= 4.5

    @Test fun `the underlay is the palette's opaque island in both modes`() {
        for (dark in listOf(true, false)) {
            val u = topChromeUnderlay(dark)
            assertEquals(ThemeBackground.colors(dark).island, u)
            assertEquals("opaque ($dark)", 1f, u.alpha, 0f)
        }
    }

    @Test fun `the row's ink and the light status icons read on the underlay`() {
        for (dark in listOf(true, false)) {
            val c = ThemeBackground.colors(dark)
            assertTrue("dark=$dark", readable(topChromeUnderlay(dark), c.onIsland))
        }
    }

    @Test fun `the bug's fill fails the same check`() {
        val ink = ThemeBackground.colors(true).onIsland
        assertFalse(readable(m3LightSurface, ink))
        assertFalse(readable(Color.Transparent, ink))
    }
}
