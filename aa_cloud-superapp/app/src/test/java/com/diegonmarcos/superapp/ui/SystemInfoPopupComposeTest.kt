package com.diegonmarcos.superapp.ui

import android.app.Application
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #773 — the system-info bubble (RAM / Storage tap) in Compose, was a PopupWindow of TextViews.
 *
 *   S1 it draws the rows the old bubble drew (Device, Android, App, Memory, Swap, Storage, CPU, Uptime)
 *   S2 it is a compact bubble: one screenful, not a stretched Material sheet
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SystemInfoPopupComposeTest : KitPageHarness() {

    @Test
    fun `S1 the bubble draws its rows`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        showKit { SystemInfoPopup.SystemInfoBubble(ctx) }
        for (row in listOf("Device", "Android", "App", "Memory", "Swap", "Storage", "CPU", "Uptime"))
            compose.onNodeWithText(row).assertExists()
    }

    @Test
    fun `S2 the bubble is compact`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        showKit { SystemInfoPopup.SystemInfoBubble(ctx) }
        val h = compose.onNodeWithTag("sysinfo:bubble").fetchSemanticsNode().size.height
        val limit = with(compose.density) { 520.dp_().roundToPx() }
        assertTrue("bubble is $h px", h <= limit)
    }

    private fun Int.dp_() = androidx.compose.ui.unit.Dp(this.toFloat())
}
