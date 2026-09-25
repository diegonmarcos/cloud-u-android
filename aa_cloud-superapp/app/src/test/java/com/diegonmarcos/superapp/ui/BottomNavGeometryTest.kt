package com.diegonmarcos.superapp.ui

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import com.diegonmarcos.superapp.bottomnav.R as NavR

/**
 * #498 / #531 — the shell's bottom bar GEOMETRY, measured on the Compose island it now is.
 *
 * #462/#473/#477/#498 each nudged a dp and shipped green because every tester read XML text;
 * #511/#512 moved the proof onto the laid-out bar. The View is gone (#531): the bar is
 * libs:bottomnav's island, hosted by BottomNavIslandView and configured by ShellBottomNav. So
 * this test lays out THAT host under Theme.Superapp and reads every number back off the
 * rendered semantics tree. Every expected value comes from the library's own dimens, never a
 * literal here.
 *
 *   M0 the bar shows build.json::ui.bottom_nav, in that order, with each section's label
 *   M1 the island is exactly as tall as the declared item geometry needs
 *   M2 the icon-to-label gap is bottom_nav_icon_label_gap
 *   M3 the icon is centred in its cell (the label box spans the cell, so only the glyph can drift)
 *   M4 the selected pill wraps the ink by bottom_nav_item_vertical_pad, top and bottom,
 *      and sits bottom_nav_pill_inset inside the island
 *   M5 the icon is bottom_nav_icon_size
 *   M6 the end capsules are bottom_nav_end_inset from the island's ends, both sides
 *   M7 the island is bottom_nav_width_fraction of the shell's width, centred
 *   M8 selecting an item does not move any icon or label
 *   M10 every label still fits its capsule at 80% width (no ellipsis, no overflow)
 *   M9 the shell pads for the system bars itself, so the island clears ONE dimen and
 *      not the bar a second time, even when a nav-bar inset arrives
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BottomNavGeometryTest : ShellIslandHarness() {

    private val first get() = Sections.bottomNavIds().first()

    @Test
    fun `M0 the bar is ui_bottom_nav in order, labelled by its sections`() {
        showShellNav(first)
        val declared = Sections.bottomNavIds()
        assertTrue("build.json::ui.bottom_nav is empty — the shell has no bar", declared.isNotEmpty())
        assertEquals("the island's items are not ui.bottom_nav", declared, ids)
        val xs = declared.map { cell(it).left }
        assertEquals("the items are not drawn left to right in ui.bottom_nav order: $xs", xs.sorted(), xs)
        for (id in declared) {
            compose.onNodeWithTag(BottomNavTags.label(id), useUnmergedTree = true)
                .assertTextEquals(Sections.byId(id)!!.label)
        }
    }

    @Test
    fun `M1 the island is exactly as tall as the declared item geometry needs`() {
        showShellNav(first)
        val pad = px(NavR.dimen.bottom_nav_item_vertical_pad)
        val pillInset = px(NavR.dimen.bottom_nav_pill_inset)
        val need = 2 * (pillInset + pad) + px(NavR.dimen.bottom_nav_icon_size) +
            px(NavR.dimen.bottom_nav_icon_label_gap) + label(first).height
        near("M1 island height = 2*(pill inset + pad) + icon + gap + label", need, island().height)
    }

    @Test
    fun `M2 to M5 every item's stack sits where the dimens put it`() {
        showShellNav(first)
        val pad = px(NavR.dimen.bottom_nav_item_vertical_pad)
        val pillInset = px(NavR.dimen.bottom_nav_pill_inset)
        val gap = px(NavR.dimen.bottom_nav_icon_label_gap)
        val iconSize = px(NavR.dimen.bottom_nav_icon_size)
        val isl = island()
        for (id in ids) {
            val c = cell(id); val i = icon(id); val l = label(id)
            near("M2 $id icon-to-label gap", gap, l.top - i.bottom)
            near("M3 $id icon centred in its cell", c.center.x, i.center.x)
            near("M4 $id pill top to icon top", pad, i.top - c.top)
            near("M4 $id label bottom to pill bottom", pad, c.bottom - l.bottom)
            near("M4 $id pill inset from the island top", pillInset, c.top - isl.top)
            near("M4 $id pill inset from the island bottom", pillInset, isl.bottom - c.bottom)
            near("M5 $id icon width", iconSize, i.width)
            near("M5 $id icon height", iconSize, i.height)
        }
    }

    @Test
    fun `M6 the end capsules are inset by the end inset on both sides`() {
        showShellNav(first)
        val end = px(NavR.dimen.bottom_nav_end_inset)
        val isl = island()
        near("M6 first capsule to the island's left end", end, cell(ids.first()).left - isl.left)
        near("M6 last capsule to the island's right end", end, isl.right - cell(ids.last()).right)
    }

    @Test
    fun `M7 the island is the width fraction of the shell, centred`() {
        showShellNav(first)
        val fraction = res.getFraction(NavR.fraction.bottom_nav_width_fraction, 1, 1)
        val isl = island()
        near("M7 island width", nav.width * fraction, isl.width)
        near("M7 island is centred", nav.width / 2f, isl.center.x)
        // #536: 10% of the screen clear on each side, MEASURED and printed so the run log carries
        // the numbers (#498), not just a green.
        val d = res.displayMetrics.density
        val clear = nav.width * (1 - fraction) / 2f
        near("M7 clear space left of the island", clear, isl.left)
        near("M7 clear space right of the island", clear, nav.width - isl.right)
        println(
            "#536 measured: screen ${nav.width}px = ${nav.width / d}dp (density $d); " +
                "gap left ${isl.left}px = ${isl.left / d}dp, right ${nav.width - isl.right}px = " +
                "${(nav.width - isl.right) / d}dp; bar ${isl.width}px = ${isl.width / d}dp " +
                "(${isl.width / nav.width * 100}% of the screen)",
        )
    }

    @Test
    fun `M10 every shell label still fits its capsule in the 80 percent bar`() {
        showShellNav(first)
        for (id in ids) {
            val out = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag(BottomNavTags.label(id), useUnmergedTree = true).fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
            val text = out.single()
            val why = "label ${label(id).width}px in a ${cell(id).width}px capsule, text ${text.size.width}px"
            println("#536 label $id: $why")
            assertFalse("#536 label $id overflows: $why", text.hasVisualOverflow)
            assertFalse("#536 label $id is ellipsized: $why", text.isLineEllipsized(0))
        }
    }

    @Test
    fun `M8 selecting an item moves no icon and no label`() {
        showShellNav(first)
        val before = ids.associateWith { icon(it) to label(it) }
        compose.runOnUiThread { nav.selectedId = ids.last() }
        compose.waitForIdle()
        for (id in ids) assertEquals("M8 $id moved when the selection changed", before.getValue(id), icon(id) to label(id))
    }

    @Test
    fun `M9 the island clears one dimen and not the system bar a second time`() {
        showShellNav(first)
        val margin = px(NavR.dimen.bottom_nav_island_bottom_margin)
        for (inset in listOf(0, 48)) {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, inset))
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                .build()
            compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(frame, insets) }
            compose.waitForIdle()
            near("M9 island clearance with a $inset px nav-bar inset", margin, nav.height - island().bottom)
        }
    }
}
