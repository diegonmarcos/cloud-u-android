package com.diegonmarcos.superapp.onehand

import android.app.Application
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The home stars' glyph in Compose (were TextViews the star classes poked). Polaris is the simplest of the five and shares
 * StarGlyph with the rest.
 *   H1 on the home section a star host shows and draws its glyph; off it, it is gone
 *   H2 a tap on the host navigates to the declared target
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StarsComposeTest : KitPageHarness() {

    private fun host(navigate: (String) -> Unit): Pair<ComposeView, PolarisStar> {
        lateinit var out: Pair<ComposeView, PolarisStar>
        compose.runOnUiThread {
            val v = ComposeView(compose.activity).apply { visibility = View.GONE; isClickable = true }
            compose.activity.setContentView(v)
            val star = PolarisStar(compose.activity, v, navigate)
            star.setup()
            out = v to star
        }
        return out
    }

    @Test fun `H1 shows on home and hides elsewhere`() {
        val (v, star) = host {}
        val cfg = CircularMenu.config()
        compose.runOnUiThread { star.update(cfg.showOnSection) }
        compose.waitForIdle()
        val shown = CircularMenu.searchStar().enabled && cfg.enabled
        assertEquals(if (shown) View.VISIBLE else View.GONE, v.visibility)
        if (shown) compose.onNodeWithTag("star", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnUiThread { star.update("not-home") }
        assertEquals(View.GONE, v.visibility)
    }

    @Test fun `H2 a tap navigates to the declared target`() {
        var got: String? = null
        val (v, star) = host { got = it }
        compose.runOnUiThread { star.update(CircularMenu.config().showOnSection) }
        if (v.visibility != View.VISIBLE) return   // star disabled in this build's data: nothing to tap
        compose.runOnUiThread { v.performClick() }
        assertTrue("tap did not navigate", got == CircularMenu.searchStar().target)
    }
}
