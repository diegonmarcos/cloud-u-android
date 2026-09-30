package com.diegonmarcos.superapp.ui

import android.app.Application
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.compose.ui.test.onAllNodesWithTag
import com.diegonmarcos.superapp.bottomnav.BottomNavTags
import com.diegonmarcos.superapp.launcher.Sections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #673 the shell's bottom nav collapses to icons when the content scrolls, and comes back when it
 * scrolls up — the behaviour cloud-drive has had since #603 and this shell has not.
 *
 * WHY THESE ASSERTIONS AND NOT A SIMPLER ONE: #532 added the collapse to libs:bottomnav, wired it
 * through to the island, documented it in three places, and shipped it INERT in every View-based
 * shell, because `collapsed` was a settable property and no View shell ever wrote it. A test that
 * checked the parameter existed, or that setting it by hand shrank the bar, passed throughout. So
 * these tests never set `collapsed`. They scroll the content host that ShellBottomNav was given
 * and assert the RENDERED bar changed — which fails unless something is actually driving it.
 *
 * The bar is measured through ShellIslandHarness, so what is under test is the island ShellActivity
 * configures, not a nav built for the test (#531).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShellBottomNavCollapseTest : ShellIslandHarness() {

    private fun firstId() = Sections.bottomNav().first().id
    private fun islandHeight() = island().height
    /** How many of the bar's items currently draw a label node. */
    private fun labelCount() = nav.items.count {
        compose.onAllNodesWithTag(BottomNavTags.label(it.id), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun `the shell hands the bar a scroll source, so scrolling the content collapses it`() {
        showShellNav(firstId())
        givePageToScroll()
        val expanded = islandHeight()
        assertFalse("the bar started collapsed, so the scroll proves nothing", nav.collapsed)
        assertTrue("no labels are drawn before any scroll", labelCount() > 0)

        scrollContentBy(Scroll.PX)

        assertTrue(
            "scrolling the content host did not collapse the bar: nothing is driving " +
                "BottomNavIslandView.collapsed in this shell, which is exactly how #532 " +
                "shipped inert",
            nav.collapsed,
        )
        assertEquals("labels survived the collapse", 0, labelCount())
        val collapsed = islandHeight()
        assertTrue(
            "the collapse did not shrink the island: $expanded px expanded, $collapsed px collapsed",
            collapsed < expanded - 1f,
        )
    }

    @Test
    fun `scrolling back up restores the labels`() {
        showShellNav(firstId())
        givePageToScroll()
        val expanded = islandHeight()
        scrollContentBy(Scroll.PX)
        assertTrue("the bar never collapsed, so restoring it proves nothing", nav.collapsed)

        scrollContentBy(-Scroll.PX)

        assertFalse("scrolling up left the bar collapsed", nav.collapsed)
        assertEquals("the labels did not come back", nav.items.size, labelCount())
        near("the island did not return to its expanded height", expanded, islandHeight())
    }

    /**
     * The driver watches the window's observer, so this pins that it is SCOPED to the content
     * host: the shell's drawer and its overlay host scroll too, and neither is the page the user
     * is reading. A scroll outside [content] measures a zero delta and must leave the bar alone.
     */
    @Test
    fun `a scroll outside the content host does not move the bar`() {
        showShellNav(firstId())
        givePageToScroll()
        // A scrolling view in the window but OUTSIDE the content host, like the drawer's list.
        compose.runOnUiThread {
            val outsider = ScrollView(themed).apply { addView(page(), pageLayout()) }
            frame.addView(outsider, FrameLayout.LayoutParams(VIEWPORT_PX, VIEWPORT_PX))
            layOut(outsider)
            outsider.scrollTo(0, Scroll.PX)
            // The scroll this test is about has to have HAPPENED, or the assertions below hold for
            // a bar that was simply never disturbed and the scoping claim is worth nothing.
            assertEquals("the outside view did not scroll", Scroll.PX, outsider.scrollY)
        }
        compose.waitForIdle()

        assertFalse(
            "a scroll outside the content host collapsed the bar: the driver is watching the " +
                "whole window instead of the page",
            nav.collapsed,
        )
        assertEquals("labels were dropped by an unrelated scroll", nav.items.size, labelCount())
    }

    private object Scroll { const val PX = 400 }
}
