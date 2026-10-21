package com.diegonmarcos.superapp.launcher

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * #1075 — the Configs page is Cloud ▸ Apps' design (the one tile, the one size), at the Samsung A37 (384 x 672 dp of
 * content between the bars). This draws the REAL Configs page (build.json's config section, as sectionGrid builds it) and
 * guards, at fontScale 1.0 / 1.15 / 1.3:
 *
 *   F1 every tile drawn is the Apps cell (TILE_CELL_WIDTH wide) with the Apps 32 dp glyph
 *   F2 the page starts with its first heading, and the first row of tiles follows it
 *   F3 whether the whole page fits one screen or scrolls inside its content area is written to
 *      build/configs-screens/fit@<case>.txt (no assertion: Apps' size wins over fitting, the page scrolls if it must)
 *
 * Each case writes build/configs-screens/configs@<case>.png (uploaded by configs-screens.yml).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class ConfigsIconSizeTest : KitPageHarness() {

    private fun configsPage(): Pair<ConfigsFragment, List<TileGridFragment.Tile>> =
        ConfigsPageFixture.tiles(compose.activity).let { ConfigsFragment.newInstance(it) to it }

    private fun guard(name: String, fontScale: Float) {
        RuntimeEnvironment.setFontScale(fontScale)
        val (page, tiles) = configsPage()
        show(page)
        compose.waitForIdle()
        val root = compose.activity.findViewById<View>(android.R.id.content)
        val out = File(System.getProperty("user.dir"), "build/configs-screens").apply { mkdirs() }
        compose.runOnUiThread {
            val b = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            Canvas(b).also { it.drawColor(android.graphics.Color.BLACK); root.draw(it) }
            File(out, "configs@$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val first = tiles.first { !it.id.startsWith(TileGridFragment.SEPARATOR_PREFIX) }
        val cell = compose.onNodeWithTag(GroupedTilesFragment.tileTag(first.id), useUnmergedTree = true).getBoundsInRoot()
        assertEquals("$name: a Configs tile is the Apps cell wide", TILE_CELL_WIDTH.value, cell.width.value, 0.5f)
        val icon = compose.onNodeWithTag(tileIconTag(first.id), useUnmergedTree = true).getBoundsInRoot()
        assertEquals("$name: the glyph is the Apps 32 dp", 32f, icon.width.value, 0.5f)
        assertEquals(32f, icon.height.value, 0.5f)
        val head = compose.onNodeWithTag(GroupedTilesFragment.groupTag(first.group), useUnmergedTree = true).getBoundsInRoot()
        assertTrue("$name: the first tile row sits below its heading", cell.top >= head.bottom - 0.5.dp)
        val last = tiles.last { !it.id.startsWith(TileGridFragment.SEPARATOR_PREFIX) }
        val fits = runCatching { compose.onNodeWithTag(GroupedTilesFragment.tileTag(last.id), useUnmergedTree = true).assertIsDisplayed() }.isSuccess
        File(out, "fit@$name.txt").writeText("whole page on one screen: $fits\n")
    }

    @Test @Config(qualifiers = "w384dp-h672dp-450dpi")
    fun `F1 Configs at the A37 fontScale 1_0`() = guard("a37-1.0", 1.0f)

    @Test @Config(qualifiers = "w384dp-h672dp-450dpi")
    fun `F2 Configs at the A37 fontScale 1_15`() = guard("a37-1.15", 1.15f)

    @Test @Config(qualifiers = "w360dp-h600dp-480dpi")
    fun `F3 Configs at display zoom and fontScale 1_3`() = guard("zoom-1.3", 1.3f)

    // Owner rule: no repeated symbols. Two tiles on one screen must never share an icon, and every icon must resolve
    // to a real drawable (a missing one silently falls back to the generic ic_link_tile, which would repeat).
    @Test
    fun `G1 no two visible tiles of a screen share an icon`() {
        val ctx = compose.activity
        val fallback = Sections.iconResFor(ctx, "")
        for (section in Sections.all()) {
            val seen = mutableMapOf<Int, String>()
            for (p in section.pages.filterNot { it.hidden }) {
                val name = p.iconName ?: continue
                val res = Sections.iconResFor(ctx, name)
                assertTrue("${section.id}: tile '${p.label}' icon '$name' does not resolve to a drawable", res != 0 && res != fallback)
                val other = seen.put(res, p.label)
                assertTrue("${section.id}: tiles '$other' and '${p.label}' share the icon '$name'", other == null)
            }
        }
    }
}

/** The Configs page's tiles exactly as sectionGrid builds them from build.json's config section. */
internal object ConfigsPageFixture {
    fun tiles(ctx: android.content.Context): List<TileGridFragment.Tile> {
        val section = Sections.byId("config")!!
        val tiles = section.pages.filterNot { it.hidden }.map { p ->
            TileGridFragment.Tile(
                id = if (p.separator) "${TileGridFragment.SEPARATOR_PREFIX}${p.id}" else if (p.action.isNotBlank()) p.action else "page:config/${p.id}",
                label = p.label,
                iconRes = p.iconName?.let { Sections.iconResFor(ctx, it) } ?: 0,
                group = if (p.isAction) "Actions" else p.group.ifBlank { "Pages" },
                subgroup = if (p.isAction) "" else p.subgroup,
                rowBreak = p.rowBreak,
            )
        }
        assertTrue("the Configs section declares tiles", tiles.isNotEmpty())
        return tiles
    }
}
