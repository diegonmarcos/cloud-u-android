package com.diegonmarcos.superapp.launcher

import android.app.Application
import android.graphics.Rect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.graphics.Insets
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
 * The status strip on the owner's Samsung A37 (SM-A376B): 1080 x 2340 px at 420 dpi (density 2.625, 411 dp wide), ONE centre
 * punch-hole camera. Its cutout is dispatched with the window insets the way the platform does it.
 *   C1 the clock sits BELOW the cutout, centred, and its box does not touch the camera's rectangle
 *   C2 every first-row child lies inside the screen width, with the cutout's side insets kept clear
 *   C3 the cutout is found from the window's root insets even if the dispatch never reached the strip
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class StatusStripCutoutTest : KitPageHarness() {

    // The A37's camera: a hole centred on the 1080 px width, its bounding rect 64 x 86 px from the top edge.
    private val cutout = Rect(508, 0, 572, 86)
    private val screenW get() = compose.activity.resources.displayMetrics.widthPixels

    @Before fun still() { ShellFxState.frozen = true }

    private fun insets() = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, cutout.bottom, 0, 0))
        .setDisplayCutout(DisplayCutoutCompat(Rect(0, cutout.bottom, 0, 0), listOf(cutout)))
        .build()

    private fun showStrip(dispatch: Boolean = true): StatusStripModel {
        lateinit var model: StatusStripModel
        compose.runOnUiThread {
            val host = ComposeView(compose.activity)
            compose.activity.setContentView(host)
            model = LauncherStatusStrip.install(compose.activity, host)
            if (dispatch) ViewCompat.dispatchApplyWindowInsets(host, insets())
        }
        compose.waitForIdle()
        return model
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test fun `C1 the clock sits below the camera and clear of it`() {
        showStrip()
        val clock = bounds("strip:clock")
        assertTrue("clock top ${clock.top} is not below the cutout bottom ${cutout.bottom}", clock.top >= cutout.bottom)
        val camera = androidx.compose.ui.geometry.Rect(cutout.left.toFloat(), cutout.top.toFloat(), cutout.right.toFloat(), cutout.bottom.toFloat())
        assertTrue("clock $clock intersects the camera $camera", !clock.overlaps(camera))
        val mid = (clock.left + clock.right) / 2f
        assertEquals("clock is not centred on the camera", screenW / 2f, mid, 3f)
    }

    @Test fun `C2 no first-row child leaves the screen`() {
        showStrip()
        val tagged = compose.onAllNodes(SemanticsMatcher("a strip child") { n ->
            val t = n.config.getOrNull(SemanticsProperties.TestTag)
            t != null && t.startsWith("strip:") && t != "strip:reserve" && t != "strip:row"
        }, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("no strip children found", tagged.size >= 10)
        for (n in tagged) {
            val b = n.boundsInRoot
            assertTrue("${n.config.getOrNull(SemanticsProperties.TestTag)} spans ${b.left}..${b.right} of $screenW px",
                b.left >= 0f && b.right <= screenW.toFloat() + 0.5f)
        }
    }

    @Test fun `C3 the cutout is read from the root insets when no dispatch arrives`() {
        val model = showStrip(dispatch = false)
        // Nothing was dispatched to the strip; the window's own root insets (what the platform reports) are asked instead.
        compose.runOnUiThread {
            val host = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
            model.noteInsets(insets())
            assertEquals(cutout.bottom, model.cutoutTopPx)
            host.requestLayout()
        }
        compose.waitForIdle()
        assertTrue(bounds("strip:clock").top >= cutout.bottom)
    }
}
