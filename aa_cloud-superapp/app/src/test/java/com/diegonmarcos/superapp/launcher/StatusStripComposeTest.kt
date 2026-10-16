package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.network.NetworkSections
import com.diegonmarcos.superapp.settings.LauncherSettingsPrefs
import com.diegonmarcos.superapp.ui.KitPageHarness
import com.diegonmarcos.superapp.ui.ShellFxState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The launcher status strip in Compose (was LauncherStatusStripView, a 935-line hand-built LinearLayout).
 *
 *   S1 every network icon is drawn, left to right in NetworkSections.ORDER, and the model has a chip for each
 *   S2 the clock reads dd-MM-yyyy HH:mm EEE and sits at the true centre of the strip
 *   S3 the clock clears the camera cutout by the cutout's own pixels, and drops back when there is none (#407)
 *   S4 the right cluster draws RAM, storage and CPU
 *   S5 tapping the clock opens its popup without throwing
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StatusStripComposeTest : KitPageHarness() {

    @Before fun petsOff() {
        // A pet is a GIF on a frame clock; Compose's idle wait never returns while one plays.
        val app = ApplicationProvider.getApplicationContext<Application>()
        LauncherSettingsPrefs(app).setToggle("pets_anim", false)
        ShellFxState.refresh(app)
    }

    private fun showStrip(): StatusStripModel {
        val model = StatusStripModel(compose.activity)
        showKit { StatusStripBody(model, compose.activity) }
        return model
    }

    private fun left(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.left

    @Test
    fun `S1 every network icon is drawn in the shared order`() {
        val model = showStrip()
        assertEquals(NetworkSections.ORDER.toSet(), model.chips.keys)
        val xs = NetworkSections.ORDER.map { left("strip:$it") }
        assertEquals("icons run left to right in ORDER: $xs", xs.sorted(), xs)
    }

    @Test
    fun `S2 the clock is dated and centred`() {
        val model = showStrip()
        compose.runOnUiThread { model.refreshTime() }
        compose.waitForIdle()
        val node = compose.onNodeWithTag("strip:clock").fetchSemanticsNode()
        val text = node.config[SemanticsProperties.Text].joinToString("") { it.text }
        assertTrue("clock was '$text'", Regex("""\d\d-\d\d-\d{4} \d\d:\d\d [A-Za-z]{3}""").matches(text))
        val rootW = compose.onRoot().fetchSemanticsNode().size.width
        val mid = (node.boundsInRoot.left + node.boundsInRoot.right) / 2f
        assertEquals("clock centre vs strip centre", rootW / 2f, mid, 2f)
    }

    @Test
    fun `S3 the clock clears the cutout by its own pixels`() {
        val model = showStrip()
        val flat = compose.onNodeWithTag("strip:clock").fetchSemanticsNode().boundsInRoot.top
        compose.runOnUiThread { model.noteCutout(160) }
        compose.waitForIdle()
        val low = compose.onNodeWithTag("strip:clock").fetchSemanticsNode().boundsInRoot.top
        assertTrue("clock top $low must be at or below the 160px cutout", low >= 160f)
        compose.runOnUiThread { model.noteCutout(0) }
        compose.waitForIdle()
        val back = compose.onNodeWithTag("strip:clock").fetchSemanticsNode().boundsInRoot.top
        assertEquals("with no cutout the clock returns to where it was", flat, back, 1f)
    }

    @Test
    fun `S4 the right cluster draws RAM, storage and CPU`() {
        showStrip()
        for (t in listOf("strip:ram", "strip:storage", "strip:cpu")) compose.onNodeWithTag(t).assertExists()
    }

    @Test
    fun `S5 tapping the clock opens its popup without throwing`() {
        showStrip()
        compose.onNodeWithTag("strip:clock").performClick()
        compose.waitForIdle()
    }
}
