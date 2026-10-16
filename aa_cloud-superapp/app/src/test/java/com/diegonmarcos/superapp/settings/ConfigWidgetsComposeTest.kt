package com.diegonmarcos.superapp.settings

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Configs dropdown replaced the platform Spinner on the One-hand page. A Spinner fires its listener on a programmatic
 * selection, which is why the old pickers carried a `suppress` flag; the Compose field must write ONLY on a tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConfigWidgetsComposeTest : KitPageHarness() {

    private val opts = listOf(DropdownOption("None"), DropdownOption("Back"), DropdownOption("Home"))

    @Test
    fun `W1 showing a selection does not write`() {
        val writes = mutableListOf<Int>()
        showKit { ConfigDropdown(opts, selected = 1, tag = "t") { writes += it } }
        compose.onNodeWithTag("cfg:dropdown:t").assertIsDisplayed()
        compose.waitForIdle()
        assertEquals(emptyList<Int>(), writes)
    }

    @Test
    fun `W2 tapping a row writes exactly that index`() {
        val writes = mutableListOf<Int>()
        showKit { ConfigDropdown(opts, selected = 0, tag = "t") { writes += it } }
        compose.onNodeWithTag("cfg:dropdown:t").performClick()
        compose.onNodeWithTag("cfg:option:t:2").performClick()
        compose.waitForIdle()
        assertEquals(listOf(2), writes)
    }

    @Test
    fun `W3 the switch row toggles through its callback`() {
        var on = false
        showKit {
            var state by remember { mutableStateOf(false) }
            ConfigSwitchRow("Enable", state, tag = "s") { state = it; on = it }
        }
        compose.onNodeWithTag("cfg:switch:s").performClick()
        compose.waitForIdle()
        assertEquals(true, on)
    }
}
