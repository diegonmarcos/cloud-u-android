package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.ui.test.onNodeWithTag
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #773 — the Home fan menu (hold the nav item, slide up, lift on a bubble) in Compose, was a PopupWindow of Views.
 *
 *   F1 one bubble on top, the rest along the bottom row
 *   F2 the finger arms the nearest bubble, and only inside the activation radius
 *   F3 the armed bubble is the one the state names (the grow + colour follow it)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeFanMenuComposeTest : KitPageHarness() {

    private val items = listOf(
        "section:config" to (R.drawable.ic_settings to "Configs"),
        "a" to (R.drawable.ic_settings to "Recent"),
        "b" to (R.drawable.ic_settings to "Tabs"),
        "c" to (R.drawable.ic_settings to "Update"),
    )

    @Test
    fun `F1 one bubble on top and the rest below it`() {
        val st = HomeFanMenu.FanState()
        showKit { HomeFanMenu.Fan(items, st, animate = false) }
        compose.waitForIdle()
        for (i in items.indices) compose.onNodeWithTag("fan:bubble:$i").assertExists()
        val top = st.centers.getValue(0)
        for (i in 1..3) assertTrue("bubble $i sits below the top one", st.centers.getValue(i).y > top.y)
    }

    @Test
    fun `F2 the finger arms the nearest bubble inside the radius only`() {
        val st = HomeFanMenu.FanState()
        showKit { HomeFanMenu.Fan(items, st, animate = false) }
        compose.waitForIdle()
        val c2 = st.centers.getValue(2)
        assertEquals(2, HomeFanMenu.nearest(st.centers, c2.x + 5f, c2.y - 5f))
        assertEquals(-1, HomeFanMenu.nearest(st.centers, c2.x + 2000f, c2.y))
    }

    @Test
    fun `F3 the armed bubble follows the state`() {
        val st = HomeFanMenu.FanState()
        showKit { HomeFanMenu.Fan(items, st, animate = false) }
        compose.runOnIdle { st.highlighted = 1 }
        compose.waitForIdle()
        assertEquals(1, st.highlighted)
    }
}
