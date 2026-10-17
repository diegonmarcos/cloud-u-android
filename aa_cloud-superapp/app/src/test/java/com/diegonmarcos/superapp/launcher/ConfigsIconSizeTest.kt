package com.diegonmarcos.superapp.launcher

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * "The Configs tiles are HUGE" (5th report): the grid drew 128 dp cards, one row per subgroup, three screens tall.
 * The owner's requirement is now: ALL Configs tiles on ONE screen of the Samsung A37 (1080 x 2340 px, 450 dpi =
 * 384 x 832 dp) with no scrolling. The viewport is what is left between the status strip (24), the top bar (56)
 * and the bottom nav (80): 384 x 672 dp. This draws the REAL Configs grid (build.json's config section, fit mode,
 * exactly as sectionGrid builds it) at that size and guards:
 *
 *   F1 fontScale 1.0  : last tile bottom <= viewport bottom, every tile <= 80 dp tall, every icon >= 22 dp,
 *                       no two labels overlap
 *   F2 fontScale 1.15 : same
 *   F3 Samsung display zoom (360 x 600 dp at 480 dpi) and fontScale 1.3: still fits (labels shrink, never scroll)
 *
 * Each case writes build/configs-screens/grid-fit@<case>.png (uploaded by configs-screens.yml).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class ConfigsIconSizeTest : KitPageHarness() {

    private fun configsGrid(): TileGridFragment {
        val section = Sections.byId("config")!!
        val ctx = compose.activity
        val tiles = section.pages.filterNot { it.hidden }.map { p ->
            TileGridFragment.Tile(
                id = if (p.action.isNotBlank()) p.action else "page:config/${p.id}",
                label = p.label,
                iconRes = p.iconName?.let { Sections.iconResFor(ctx, it) } ?: 0,
                group = if (p.isAction) "Actions" else p.group.ifBlank { "Pages" },
                subgroup = if (p.isAction) "" else p.subgroup,
                rowBreak = p.rowBreak,
            )
        }
        assertTrue("the Configs section declares tiles", tiles.isNotEmpty())
        return TileGridFragment.newInstance("Configs", tiles, columns = section.gridColumns, fit = true)
    }

    private fun guard(name: String, fontScale: Float) {
        RuntimeEnvironment.setFontScale(fontScale)
        TileMetrics.clear()
        val page = show(configsGrid())
        compose.waitForIdle()
        val root = compose.activity.findViewById<View>(android.R.id.content)
        val out = File(System.getProperty("user.dir"), "build/configs-screens").apply { mkdirs() }
        compose.runOnUiThread {
            val b = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            Canvas(b).also { it.drawColor(android.graphics.Color.BLACK); root.draw(it) }
            File(out, "grid-fit@$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val viewportDp = root.height / compose.density.density
        val ids = requireNotNull(page.requireArguments().getStringArray("tile_ids")).filterNot { it.startsWith(TileGridFragment.SEPARATOR_PREFIX) }
        val labels = mutableListOf<Pair<String, androidx.compose.ui.unit.DpRect>>()
        for (id in ids) {
            val t = compose.onNodeWithTag(tileTag(id), useUnmergedTree = true).getBoundsInRoot()
            assertTrue("$name: tile $id bottom ${t.bottom} dp is below the viewport $viewportDp dp (grid scrolls)", t.bottom.value <= viewportDp + 0.5f)
            assertTrue("$name: tile $id is ${t.height} tall, max 80 dp", t.height.value <= 80.5f)
            val i = compose.onNodeWithTag(tileIconTag(id), useUnmergedTree = true).getBoundsInRoot()
            assertTrue("$name: icon of $id is ${i.width} x ${i.height}, min 22 dp", i.width.value >= 21.9f && i.height.value >= 21.9f)
            labels += id to compose.onNodeWithTag("tilelabel:$id", useUnmergedTree = true).getBoundsInRoot()
        }
        for (a in labels.indices) for (b in a + 1 until labels.size) {
            val (x, r) = labels[a]; val (y, s) = labels[b]
            val overlap = r.left < s.right - 0.5.dp && s.left < r.right - 0.5.dp && r.top < s.bottom - 0.5.dp && s.top < r.bottom - 0.5.dp
            assertTrue("$name: labels of $x and $y overlap", !overlap)
        }
    }

    @Test @Config(qualifiers = "w384dp-h672dp-450dpi")
    fun `F1 the whole Configs grid fits one A37 screen at fontScale 1_0`() = guard("a37-1.0", 1.0f)

    @Test @Config(qualifiers = "w384dp-h672dp-450dpi")
    fun `F2 the whole Configs grid fits one A37 screen at fontScale 1_15`() = guard("a37-1.15", 1.15f)

    @Test @Config(qualifiers = "w360dp-h600dp-480dpi")
    fun `F3 display zoom and fontScale 1_3 shrink the labels instead of scrolling`() = guard("zoom-1.3", 1.3f)
}
