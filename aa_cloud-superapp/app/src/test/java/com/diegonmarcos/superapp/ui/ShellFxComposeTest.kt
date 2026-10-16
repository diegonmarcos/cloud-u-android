package com.diegonmarcos.superapp.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #773 — the shell's decorative layers in Compose (were GalaxyBackdropView, IslandWaveView, PrismaticShimmerView and
 * WaveLineView, one ValueAnimator each).
 *
 *   X1 each layer composes at the size it is given and draws without throwing
 *   X2 the Configs switches are state: refresh() re-reads the store, defaulting to on
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ShellFxComposeTest : KitPageHarness() {

    @Test
    fun `X1 every layer composes at its size`() {
        ShellFxState.frozen = true
        showKit {
            Box(Modifier.size(300.dp, 200.dp).testTag("galaxy")) { GalaxyBackdrop(Modifier.size(300.dp, 200.dp)) }
            Box(Modifier.size(120.dp, 22.dp).testTag("island")) {
                IslandWave(Modifier.size(120.dp, 22.dp)); PrismaticShimmer(Modifier.size(120.dp, 22.dp))
            }
            Box(Modifier.size(90.dp, 16.dp).testTag("line")) { WaveLine(Modifier.size(90.dp, 16.dp)) }
        }
        compose.waitForIdle()
        for (tag in listOf("galaxy", "island", "line")) compose.onNodeWithTag(tag).assertExists()
        val n = compose.onNodeWithTag("island").fetchSemanticsNode()
        assertTrue("island is ${n.size}, galaxy ${compose.onNodeWithTag("galaxy").fetchSemanticsNode().size}", n.size.width > n.size.height)
        ShellFxState.frozen = false
    }

    @Test
    fun `X2 the live switches are state read from the store`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        ShellFxState.refresh(ctx)
        assertEquals(true, ShellFxState.stars)
        assertEquals(true, ShellFxState.island)
        assertEquals(true, ShellFxState.pets)
    }
}
