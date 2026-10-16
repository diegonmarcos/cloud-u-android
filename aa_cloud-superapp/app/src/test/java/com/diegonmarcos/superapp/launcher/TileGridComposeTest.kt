package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #773 — the launcher tile and the two grids built on it (TileGrid, HomeGrouped), Compose since
 * item_tile.xml / fragment_tile_grid.xml / fragment_home_grouped.xml.
 *
 *   G1 the plan: a heading where the declared group or subgroup changes, a new group resets the
 *      remembered subgroup, a row never straddles a group/subgroup, and holds at most `cols` tiles
 *   G2 separators ride inline: they take no column slot, so a row of `cols` tiles plus separators is one row
 *   G3 a declared row_break starts a fresh row without a heading, and a break on the first tile loops nowhere
 *   G4 the grid draws one tile per non-separator id, a heading per group, and a tap reports the tile id
 *   G5 an empty grid says so
 *   G6 the icon is the wanted 32 dp until the cell cannot hold it, then shrinks to what is left, never below 16
 *   G7 HomeGrouped's buckets are ui.home_groups in order, then ui.home_actions as "Actions"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TileGridComposeTest : KitPageHarness() {

    private fun plan(ids: List<String>, g: List<String> = emptyList(), s: List<String> = emptyList(),
                     br: List<Boolean> = emptyList(), cols: Int = 3) =
        TileGridFragment.plan(ids.toTypedArray(), g.toTypedArray(), s.toTypedArray(), br.toBooleanArray(), cols)

    private fun shape(items: List<TileGridFragment.GridItem>) = items.map {
        when (it) {
            is TileGridFragment.GridItem.Header -> "H:${it.text}"
            is TileGridFragment.GridItem.SubHeader -> "S:${it.text}"
            is TileGridFragment.GridItem.TileRow -> "R:${it.first}+${it.span}/${it.slots}"
        }
    }

    @Test
    fun `G1 headings follow the declared group and subgroup and rows never straddle them`() {
        val ids = (0..6).map { "t$it" }
        val g = listOf("A", "A", "A", "A", "B", "B", "B")
        val s = listOf("", "x", "x", "x", "x", "x", "")
        assertEquals(
            listOf("H:A", "R:0+1/1", "S:x", "R:1+3/3", "H:B", "S:x", "R:4+2/2", "R:6+1/1"),
            shape(plan(ids, g, s)),
        )
    }

    @Test
    fun `G1b a repeated subgroup under a later group draws again`() {
        val shape = shape(plan(listOf("a", "b"), listOf("A", "B"), listOf("Setup", "Setup")))
        assertEquals(listOf("H:A", "S:Setup", "R:0+1/1", "H:B", "S:Setup", "R:1+1/1"), shape)
    }

    @Test
    fun `G2 separators take no column slot`() {
        val ids = listOf("a", "b", "separator:|", "c", "d")
        assertEquals(listOf("R:0+4/3", "R:4+1/1"), shape(plan(ids, cols = 3)))
    }

    @Test
    fun `G3 a row break starts a fresh row without a heading`() {
        val ids = listOf("a", "b", "c", "d")
        assertEquals(listOf("R:0+1/1", "R:1+2/2", "R:3+1/1"), shape(plan(ids, br = listOf(false, true, false, true), cols = 3)))
        assertEquals(listOf("R:0+2/2"), shape(plan(listOf("a", "b"), br = listOf(true, false), cols = 3)))
    }

    @Test
    fun `G4 the grid draws its tiles and headings and a tap reports the id`() {
        val tiles = listOf(
            TileGridFragment.Tile("page:cfg/a", "Alpha", R.drawable.ic_settings, group = "Launcher"),
            TileGridFragment.Tile("page:cfg/b", "Beta", R.drawable.ic_settings, group = "Launcher"),
            TileGridFragment.Tile("separator:|", "|", 0, group = "Launcher"),
            TileGridFragment.Tile("page:cfg/c", "Gamma", R.drawable.ic_settings, group = "Setup", subgroup = "Network"),
        )
        val page = show(TileGridFragment.newInstance("Configs", tiles, columns = 4))
        compose.onNodeWithTag(TileGridFragment.TAG_TITLE).assertExists()
        compose.onNodeWithTag(TileGridFragment.headerTag("Launcher")).assertExists()
        compose.onNodeWithTag(TileGridFragment.headerTag("Setup")).assertExists()
        compose.onNodeWithTag(TileGridFragment.subHeaderTag("Network")).assertExists()
        for (id in listOf("page:cfg/a", "page:cfg/b", "page:cfg/c")) compose.onNodeWithTag(tileTag(id)).assertExists()
        compose.onAllNodesWithTag(tileTag("separator:|")).assertCountEquals(0)
        // The harness activity is no TileClickListener, so a tap must not crash; the page still stands.
        compose.onNodeWithTag(tileTag("page:cfg/a")).performClick()
        compose.onNodeWithTag(tileTag("page:cfg/b")).assertExists()
        assertTrue(page.isAdded)
    }

    @Test
    fun `G5 an empty grid says so`() {
        show(TileGridFragment.newInstance("Nothing", emptyList()))
        compose.onNodeWithTag(TileGridFragment.TAG_EMPTY).assertExists()
    }

    @Test
    fun `G6 the icon shrinks only when the cell cannot hold it`() {
        // 360 dp at density 2 = 720 px. Six columns: 120 px cell; chrome 16 dp = 32 px → 88 px left, wanted 64 px.
        assertEquals(32f, tileIconDp(720, 2f, 6, 0, 16).value, 0.01f)
        // Eight columns, chrome 18 dp: 90 px cell - 36 px = 54 px = 27 dp.
        assertEquals(27f, tileIconDp(720, 2f, 8, 0, 18).value, 0.01f)
        // A cell that cannot even hold the floor still gets 16 dp.
        assertEquals(16f, tileIconDp(720, 2f, 40, 0, 18).value, 0.01f)
    }

    @Test
    fun `G7 home grouped is the declared home groups then the actions`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val b = HomeGroupedFragment.buckets(ctx)
        val groups = Sections.homeGroups()
        assertEquals(groups.map { it.title }, b.take(groups.size).map { it.title })
        for ((i, g) in groups.withIndex()) assertEquals(g.tiles.map { it.id }, b[i].tiles.map { it.first })
        val acts = Sections.homeActions()
        if (acts.isNotEmpty()) {
            assertEquals(ctx.getString(R.string.home_group_actions), b.last().title)
            assertEquals(acts.map { "action:${it.actionType}" }, b.last().tiles.map { it.first })
        } else {
            assertEquals(groups.size, b.size)
        }
    }
}
