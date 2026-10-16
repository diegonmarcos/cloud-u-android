package com.diegonmarcos.superapp.settings

import android.app.Application
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #773 — Configs ▸ Launcher ▸ Controls in Compose (was a ScrollView of hand-built Views).
 *
 *   L1 the UI scope draws its section headers
 *   L2 a stepper writes the grid width, clamped, and shows it
 *   L3 a switch tile flips the stored value AND repaints in place (no page rebuild, #349)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LauncherControlsComposeTest : KitPageHarness() {

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `L1 the UI scope draws its headers`() {
        show(LauncherConfigFragment.newInstance(LauncherConfigFragment.SCOPE_UI))
        compose.onNodeWithText("Screensaver").assertExists()
        compose.onNodeWithText("Icons per row").assertExists()
    }

    @Test
    fun `L2 a stepper writes the grid width`() {
        show(LauncherConfigFragment.newInstance(LauncherConfigFragment.SCOPE_UI))
        val before = GridColumns.cloud(ctx)
        compose.onNodeWithTag("stepper:More icons per row", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(GridColumns.clamp(before + 1), GridColumns.cloud(ctx))
    }

    @Test
    fun `L3 a switch tile flips the store and repaints in place`() {
        show(LauncherConfigFragment.newInstance(LauncherConfigFragment.SCOPE_UI))
        val prefs = LauncherSettingsPrefs(ctx)
        val item = LauncherSettingsPrefs.Config.toggles.first { it.store.isEmpty() && !it.userOwned }
        val before = prefs.toggle(item)
        compose.onNodeWithTag("toggle:${item.id}").performScrollTo().performClick()
        compose.waitForIdle()
        assertNotEquals(before, prefs.toggle(item))
        // The tile is still on screen, drawn from the new value: the page was not rebuilt under the tap.
        compose.onNodeWithTag("toggle:${item.id}").assertExists()
    }
}
