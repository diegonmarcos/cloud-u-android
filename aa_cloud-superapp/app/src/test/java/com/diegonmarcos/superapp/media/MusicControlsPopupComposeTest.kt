package com.diegonmarcos.superapp.media

import android.app.Application
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #773 — the now-playing card (tap the music mini-island) in Compose, was an inflated MaterialCardView in a PopupWindow.
 *
 *   P1 idle: "Nothing playing", the streaming apps' quick-launch strip, an inert progress bar
 *   P2 the card is the compact landscape box (fixed height, nearly the screen wide)
 *   P3 a tap on an idle launcher closes the card
 *   P4 time formatting
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MusicControlsPopupComposeTest : KitPageHarness() {

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `P1 idle shows Nothing playing and the quick-launch strip`() {
        showKit { MusicControlsCard(ctx, null, null) {} }
        compose.onNodeWithText("Nothing playing").assertExists()
        compose.onNodeWithTag("music:idle-apps").assertExists()
        for (pkg in MusicControlsPopup.IDLE_LAUNCH_PKGS) compose.onNodeWithTag("music:idle:$pkg").assertExists()
        compose.onNodeWithTag("music:progress").assertExists()
    }

    @Test
    fun `P2 the card is the compact landscape box`() {
        // A popup is measured loosely; the test frame is a tight match-parent one, which beats any fixed height.
        showKit {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.wrapContentSize(androidx.compose.ui.Alignment.TopStart)) {
                MusicControlsCard(ctx, null, null) {}
            }
        }
        val n = compose.onNodeWithTag("music:card").fetchSemanticsNode()
        val dm = ctx.resources.displayMetrics
        assertEquals((MusicControlsPopup.POPUP_HEIGHT_DP * dm.density).toInt(), n.size.height)
        assertTrue("wider than tall", n.size.width > n.size.height)
    }

    @Test
    fun `P3 an idle launcher closes the card`() {
        var closed = 0
        showKit { MusicControlsCard(ctx, null, null) { closed++ } }
        compose.onNodeWithTag("music:idle:${MusicControlsPopup.IDLE_LAUNCH_PKGS.first()}").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun `P4 time is m ss`() {
        assertEquals("0:00", MusicControlsPopup.formatTime(0))
        assertEquals("3:05", MusicControlsPopup.formatTime(185_000))
    }
}
