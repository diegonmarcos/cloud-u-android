package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #773 — the index card grid in Compose (was MaterialCardView rows built by hand).
 *
 *   I1 a card calls its handler on a tap
 *   I2 a long unbroken label is clipped to two ellipsized lines instead of pushing into its neighbour
 *   I3 the row shape is full rows then the remainder (the pref decides how wide a row is)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IndexTileGridComposeTest : KitPageHarness() {

    @Test
    fun `I1 a card calls its handler`() {
        var taps = 0
        showKit { IndexTileGrid(3, listOf(IndexTiles.Cell("One") { taps++ }, IndexTiles.Cell("Two") {})) }
        compose.onNodeWithTag("index:One").performClick()
        assertEquals(1, taps)
    }

    @Test
    fun `I2 a long label is two ellipsized lines at most`() {
        val label = "Averyveryverylongunbrokenlabelthatcannotwrapanywhereatall"
        showKit { IndexTileGrid(7, listOf(IndexTiles.Cell(label) {})) }
        val results = mutableListOf<TextLayoutResult>()
        // The card is the clickable column; its text child is reached through the unmerged tree.
        val node = compose.onNodeWithTag("index:$label", useUnmergedTree = true).fetchSemanticsNode()
        val text = node.children.first { it.config.contains(SemanticsActions.GetTextLayoutResult) }
        text.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val layout = results.single()
        assertTrue("at most ${IndexTiles.LABEL_MAX_LINES} lines", layout.lineCount <= IndexTiles.LABEL_MAX_LINES)
        assertTrue("and the overflow is ellipsized", layout.isLineEllipsized(layout.lineCount - 1))
    }

    @Test
    fun `I3 rows are full then the remainder`() {
        assertEquals(listOf(6, 6, 4), IndexTiles.rowSizes(16, 6))
        assertEquals(listOf(3), IndexTiles.rowSizes(3, 7))
        assertEquals(emptyList<Int>(), IndexTiles.rowSizes(0, 4))
    }
}
