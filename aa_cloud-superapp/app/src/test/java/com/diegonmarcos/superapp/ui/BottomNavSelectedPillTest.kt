package com.diegonmarcos.superapp.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.diegonmarcos.superapp.bottomnav.BottomNavTags
import com.diegonmarcos.superapp.launcher.Sections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.google.android.material.R as MR

/**
 * #512 / #531 — the selected bottom-nav pill, proven on what the shell PAINTS.
 *
 * The selected pill once vanished for a whole release while every XML tester stayed green, so
 * nothing here reads a file. The bar is now libs:bottomnav's Compose island (#531); the part
 * that is superapp's own is the THEME: ShellBottomNav carries Theme.Superapp's inverse pair into
 * the island. So this draws the configured host and reads pixels back: the selected capsule is
 * the theme's colorSurfaceInverse, every other capsule is the island's fill, and the ink is the
 * theme's colorOnSurfaceInverse on the pill and colorOnSurfaceVariant elsewhere. Then it taps,
 * and checks the pill follows the tap and a re-tap is routed as a re-tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BottomNavSelectedPillTest : ShellIslandHarness() {

    private val centre get() = Sections.bottomNavIds().let { it[it.size / 2] }
    private val other get() = Sections.bottomNavIds().first { it != centre }

    /** Inside the capsule's left end at mid-height: clear of the centred icon and label, so
     *  only the capsule's own fill can have painted it. */
    private fun capsuleProbe(bmp: Bitmap, id: String): Color {
        val c = cell(id)
        return bmp.at(c.left + 3 * res.displayMetrics.density, c.center.y)
    }

    /** How many of the icon's pixels are [ink]: the glyph's solid strokes are drawn in exactly
     *  the tint, so a correctly tinted icon has many and a wrongly tinted one has none. */
    private fun inkPixels(bmp: Bitmap, box: Rect, ink: Color): Int {
        var n = 0
        for (x in box.left.toInt() until box.right.toInt()) for (y in box.top.toInt() until box.bottom.toInt()) {
            if (distance(bmp.at(x.toFloat(), y.toFloat()), ink) <= 3f / 255f) n++
        }
        return n
    }

    @Test
    fun `exactly one capsule is selected and it is the selected section`() {
        showShellNav(centre)
        compose.onAllNodes(isSelected()).assertCountEquals(1)
        val selected = compose.onAllNodes(isSelected()).onFirst().fetchSemanticsNode().boundsInRoot
        assertEquals("the selected node is not $centre's capsule", cell(centre), selected)
    }

    @Test
    fun `the selected capsule is the theme's light pill and the rest are the island fill`() {
        showShellNav(centre)
        val pill = themeColor(MR.attr.colorSurfaceInverse)
        val bmp = paint()
        for (id in ids) {
            val probe = capsuleProbe(bmp, id)
            val want = if (id == centre) pill else islandFill
            assertTrue("capsule $id painted $probe, expected ${if (id == centre) "Theme.Superapp colorSurfaceInverse" else "the island fill"} $want",
                distance(probe, want) <= 3f / 255f)
        }
        assertTrue("the theme's pill colour IS the island fill — the probe could not tell them apart",
            distance(pill, islandFill) > 16f / 255f)
    }

    @Test
    fun `the ink is the theme's inverse pair on the pill and onSurfaceVariant elsewhere`() {
        showShellNav(centre)
        val onPill = themeColor(MR.attr.colorOnSurfaceInverse)
        val offPill = themeColor(MR.attr.colorOnSurfaceVariant)
        assertTrue("the two inks are indistinguishable", distance(onPill, offPill) > 16f / 255f)
        val bmp = paint()
        val sel = inkPixels(bmp, icon(centre), onPill)
        val unsel = inkPixels(bmp, icon(other), offPill)
        println("#531 ink pixels: selected $centre=$sel in $onPill, unselected $other=$unsel in $offPill")
        assertTrue("the selected icon has no pixel in colorOnSurfaceInverse $onPill", sel > 0)
        assertTrue("the unselected icon has no pixel in colorOnSurfaceVariant $offPill", unsel > 0)
        // Not zero: anti-aliased glyph edges blend the ink into the pill, and a handful of those
        // blends land on the other ink by coincidence (6 of 1556 in CI run 36059377093). The
        // selected glyph must be DOMINATED by its own ink; swapping the two inks inverts this.
        val stray = inkPixels(bmp, icon(centre), offPill)
        assertTrue("the selected icon has $stray px in the unselected ink against $sel in its own", sel >= 10 * maxOf(stray, 1))
    }

    @Test
    fun `a tap moves the pill and a re-tap is routed as a re-tap`() {
        showShellNav(centre)
        compose.onNodeWithTag(BottomNavTags.item(other), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals("a tap on $other did not reach onSelect", listOf(other), picked)
        val bmp = paint()
        assertTrue("the pill did not move to $other", distance(capsuleProbe(bmp, other), themeColor(MR.attr.colorSurfaceInverse)) <= 3f / 255f)
        assertTrue("$centre still paints the pill", distance(capsuleProbe(bmp, centre), islandFill) <= 3f / 255f)

        compose.onNodeWithTag(BottomNavTags.item(other), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals("a re-tap on the selected $other was not routed to onReselect", listOf(other), repicked)
        assertEquals("a re-tap was also routed as a new pick", listOf(other), picked)
    }
}
