package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Configs icons are huge" on a real phone while the emulator said 7/7: TileSizeTest measured the CARD at one
 * default density. This draws the REAL Configs page (TileGridFragment at the declared 8 columns, the composition the
 * phone shows) at the densities and font scale a Samsung really runs, and measures the ICON node itself, in dp, from
 * the layout - not from the arithmetic that produced it.
 *
 *   I1 2.625 (420 dpi, 411 dp wide), fontScale 1.3 : every icon is 16..32 dp and the card is 128 dp
 *   I2 3.0   (480 dpi, 360 dp wide), fontScale 1.3 : same
 *   I3 the diagnostics metrics agree with the layout
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConfigsIconSizeTest : KitPageHarness() {

    private val ids = (0 until 24).map { "cfg$it" }

    private fun configsPage() = TileGridFragment.newInstance(
        "Configs",
        ids.mapIndexed { i, id -> TileGridFragment.Tile(id, "Tile $i", R.drawable.ic_settings, group = if (i < 12) "Setup" else "Fleet") },
        columns = 8,
    )

    private fun iconDp(id: String): Pair<Float, Float> {
        val b = compose.onNodeWithTag(tileIconTag(id)).getBoundsInRoot()
        return b.width.value to b.height.value
    }

    private fun check(id: String) {
        val (w, h) = iconDp(id)
        assertTrue("icon of $id is $w x $h dp, must be 16..32", w in 15.9f..32.1f && h in 15.9f..32.1f)
        val card = compose.onNodeWithTag(tileTag(id)).getBoundsInRoot().height
        assertEquals("card height of $id", 128f, card.value, 0.6f)
    }

    @Test @Config(qualifiers = "w411dp-h891dp-420dpi")
    fun `I1 icons at density 2_625 and fontScale 1_3`() {
        RuntimeEnvironment.setFontScale(1.3f)
        TileMetrics.clear()
        show(configsPage())
        check("cfg0"); check("cfg7")
        assertEquals(2.625f, compose.density.density, 0.01f)
    }

    @Test @Config(qualifiers = "w360dp-h780dp-480dpi")
    fun `I2 icons at density 3 and fontScale 1_3`() {
        RuntimeEnvironment.setFontScale(1.3f)
        TileMetrics.clear()
        show(configsPage())
        check("cfg0"); check("cfg7")
        assertEquals(3.0f, compose.density.density, 0.01f)
    }

    @Test @Config(qualifiers = "w411dp-h891dp-420dpi")
    fun `I3 the diagnostics metrics see the same icon the layout drew`() {
        TileMetrics.clear()
        show(configsPage())
        compose.waitForIdle()
        val (w, _) = iconDp("cfg2")
        assertTrue("TileMetrics saw ${TileMetrics.largestIconDp()} dp, layout drew $w dp", kotlin.math.abs(TileMetrics.largestIconDp() - w) < 0.6f)
        assertTrue(TileMetrics.largestIconDp() <= TILE_ICON_MAX_DP + 0.1f)
        assertEquals(128f, TileMetrics.latest()!!.tileHDp, 0.6f)
    }
}
