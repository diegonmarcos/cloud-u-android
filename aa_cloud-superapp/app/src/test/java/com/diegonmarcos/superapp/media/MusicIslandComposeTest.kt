package com.diegonmarcos.superapp.media

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.ui.KitPageHarness
import com.diegonmarcos.superapp.ui.ShellFxState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The music mini-island in Compose.
 *   M1 idle: the black pill is there and tappable, with no title and no icon
 *   M2 playing: the title is drawn
 *   M3 a tap reports once
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MusicIslandComposeTest : KitPageHarness() {

    private fun show(state: MusicIslandState, onTap: () -> Unit = {}) {
        ShellFxState.frozen = true
        showKit { Box(Modifier.size(90.dp, 16.dp)) { MusicIsland(state, onTap) } }
    }

    @Test fun `M1 idle pill has no title`() {
        show(MusicIslandState())
        compose.onNodeWithTag("musicisland").assertExists()
        compose.onAllNodesWithTag("musictitle", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun `M2 playing shows the title`() {
        val s = MusicIslandState().also { it.playing = NowPlayingLine("com.example.nope", "Artist — Song") }
        show(s)
        compose.onNodeWithTag("musictitle", useUnmergedTree = true).assertExists()
    }

    @Test fun `M3 a tap reports once`() {
        var taps = 0
        show(MusicIslandState()) { taps++ }
        compose.onNodeWithTag("musicisland").performClick()
        assertEquals(1, taps)
    }
}
