package com.diegonmarcos.superapp.floatingnav

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.diegonmarcos.superapp.ui.KitPageHarness
import com.diegonmarcos.superapp.ui.ShellFxState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The service-hosted overlays in Compose (were hand-built LinearLayouts, TextViews and the NeonBackdropView).
 *
 *   O1 the nav menu draws its three lines, a tap on a chip reports that item, the children line is absent when empty
 *   O2 the nav menu bolds exactly the items the foreground context matches
 *   O3 the screensaver draws time, date and Unlock, and Unlock fires
 *   O4 the neon screensaver adds the orrery and edge light behind them
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OverlayComposeTest : KitPageHarness() {

    private fun item(label: String) = NavItem(label, "app:$label", "")

    @Test
    fun `O1 the nav menu lists three lines and reports a tap`() {
        val picked = mutableListOf<String>()
        showKit {
            Column(Modifier.wrapContentSize()) {
                NavBar(item("Root"), listOf(item("Comms"), item("Terminal")), listOf(item("Fossy")), { false }) { picked += it.label }
            }
        }
        for (l in listOf("Root", "Comms", "Terminal", "Fossy")) compose.onNodeWithTag("navchip:$l").assertIsDisplayed()
        compose.onNodeWithTag("navchip:Terminal").performClick()
        assertEquals(listOf("Terminal"), picked)
    }

    @Test
    fun `O1b no children, no third line`() {
        showKit {
            Column(Modifier.wrapContentSize()) { NavBar(item("Root"), listOf(item("Comms")), emptyList(), { false }) {} }
        }
        compose.onAllNodesWithTag("navchip:Fossy").assertCountEquals(0)
        compose.onNodeWithTag("navchip:Comms").assertIsDisplayed()
    }

    private fun weightOf(tag: String): FontWeight? {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.firstOrNull()?.layoutInput?.style?.fontWeight
    }

    @Test
    fun `O2 only the matching items are bold`() {
        showKit {
            Column(Modifier.wrapContentSize()) {
                NavBar(item("Root"), listOf(item("Comms"), item("Terminal")), emptyList(), { it.label == "Comms" }) {}
            }
        }
        assertEquals(FontWeight.Bold, weightOf("navchip:Comms"))
        assertEquals(null, weightOf("navchip:Terminal"))
        assertEquals(null, weightOf("navchip:Root"))
    }

    @Test
    fun `O3 the screensaver draws time, date and a working Unlock`() {
        var unlocked = 0
        showKit { ScreensaverScene(false, "21:05", "Fri 16 Oct", "Unlock") { unlocked++ } }
        compose.onNodeWithTag("sstime").assertIsDisplayed()
        compose.onNodeWithTag("ssdate").assertIsDisplayed()
        compose.onAllNodesWithTag("neon").assertCountEquals(0)
        compose.onNodeWithTag("unlock").performClick()
        assertEquals(1, unlocked)
    }

    @Test
    fun `O4 the neon screensaver adds the backdrop`() {
        ShellFxState.frozen = true
        showKit { ScreensaverScene(true, "21:05", "Fri 16 Oct", "Unlock") {} }
        compose.onNodeWithTag("neon").assertExists()
        compose.onNodeWithTag("sstime").assertIsDisplayed()
        ShellFxState.frozen = false
    }
}
