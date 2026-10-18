package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #773 — the converted tiles are exactly as big as the View tiles they replaced ("the buttons are huge").
 *
 *   Z1 the one tile (CompactTile) is 35 dp: a 24 dp plate hugging a 20 dp glyph, a 9 sp line under it
 *   Z2 grep: the old big LauncherTile / TILE_CELL_HEIGHT never comes back
 *   Z3 a group heading is its 11 sp caption plus its 20 dp of padding - NOT a 24 sp Material line (the dense theme is on)
 *   Z4 the icon is the fitted size the 360 dp test asserts, never more than the old 32 dp
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TileSizeTest : KitPageHarness() {

    // The activity content is a tight match-parent frame, which beats any fixed height; a grid cell sees loose constraints.
    @androidx.compose.runtime.Composable
    private fun Loose(content: @androidx.compose.runtime.Composable () -> Unit) =
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.wrapContentSize(androidx.compose.ui.Alignment.TopStart)) { content() }

    @Test
    fun `Z1 a tile is half the old 70 dp - 35 dp - with a 24 dp plate hugging a 20 dp glyph`() {
        showKit { Loose { CompactTile("t", "Label", R.drawable.ic_settings, onClick = {}) } }
        compose.onNodeWithTag(tileTag("t")).assertHeightIsEqualTo(35.dp)
        compose.onNodeWithTag("tileplate:t").assertHeightIsEqualTo(24.dp)
        compose.onNodeWithTag(tileIconTag("t")).assertHeightIsEqualTo(20.dp)
    }

    @Test
    fun `Z2 the old big tile is gone from the source tree`() {
        val root = java.io.File(System.getProperty("user.dir"), "src/main/java")
        val hits = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().let { Regex("\\bLauncherTile\\(|TILE_CELL_HEIGHT").containsMatchIn(it) } }
            .map { it.name }.toList()
        assertTrue("the old 128 dp LauncherTile is used again in $hits", hits.isEmpty())
    }

    @Test
    fun `Z3 a group heading is its caption line plus its padding`() {
        showKit {
            Loose {
                androidx.compose.foundation.layout.Column {
                    TileGroupHeader("Setup", androidx.compose.ui.Modifier.testTag("h"))
                    // The same 11 sp caption on its own: what the heading's text costs with no padding at all.
                    androidx.compose.material3.Text("SETUP", fontSize = 11.sp, modifier = androidx.compose.ui.Modifier.testTag("ref"))
                }
            }
        }
        val h = compose.onNodeWithTag("h").fetchSemanticsNode().size.height
        val ref = compose.onNodeWithTag("ref").fetchSemanticsNode().size.height
        // 14 dp above + 6 dp below; a stock Material bodyLarge line (24 sp) would add far more than that on its own.
        val pad = with(compose.density) { 20.dp.roundToPx() }
        assertTrue("heading is $h px, its text alone $ref px, padding $pad px", kotlin.math.abs(h - (ref + pad)) <= 2)
    }

    @Test
    fun `Z4 the icon never exceeds the old 32 dp`() {
        for (cols in 3..8) {
            val icon = tileIconDp(1080, 3f, cols, 0, 18)
            assertEquals("icon at $cols columns is at most 32 dp", true, icon.value <= 32f)
        }
    }
}
