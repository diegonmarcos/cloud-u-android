package com.diegonmarcos.superapp.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shell's top row in Compose (was a MaterialToolbar + options menu).
 *   T1 at the Home root the row is empty: the corner orbs carry menu + wallet
 *   T2 off the root the hamburger and Back are there, and each reports its tap once
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ShellToolbarComposeTest : KitPageHarness() {

    @After fun reset() { ShellToolbarState.atHomeRoot = true }

    private fun show(onMenu: () -> Unit = {}, onBack: () -> Unit = {}) =
        showKit { Box(Modifier.size(320.dp, 56.dp)) { ShellToolbarRow(onMenu, onBack, tic = false) } }

    @Test fun `T1 empty at the Home root`() {
        ShellToolbarState.atHomeRoot = true
        show()
        compose.onAllNodesWithTag("toolbar:menu").assertCountEquals(0)
        compose.onAllNodesWithTag("toolbar:back").assertCountEquals(0)
    }

    @Test fun `T2 hamburger and Back off the root`() {
        ShellToolbarState.atHomeRoot = false
        var menu = 0; var back = 0
        show({ menu++ }, { back++ })
        compose.onNodeWithTag("toolbar:menu").assertIsDisplayed().performClick()
        compose.onNodeWithTag("toolbar:back").assertIsDisplayed().performClick()
        assertEquals(1, menu); assertEquals(1, back)
    }

    @Test fun `T3 leaving the root shows them without recreating the view`() {
        ShellToolbarState.atHomeRoot = true
        show()
        compose.runOnUiThread { ShellToolbarState.atHomeRoot = false }
        compose.waitForIdle()
        compose.onNodeWithTag("toolbar:menu").assertIsDisplayed()
    }
}
