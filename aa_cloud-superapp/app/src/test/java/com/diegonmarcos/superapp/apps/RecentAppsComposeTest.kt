package com.diegonmarcos.superapp.apps

import android.app.Application
import androidx.compose.ui.test.onNodeWithText
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #773 — Recent apps, Compose since its grid was hand-built LinearLayout rows.
 *
 *   R1 with no usage access there is nothing recent, and the page says how to fix that
 *      instead of drawing an empty grid
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecentAppsComposeTest : KitPageHarness() {

    @Test
    fun `R1 no usage access shows the way to grant it`() {
        assertTrue("the fixture has usage data, so R1 would not exercise the empty path",
            RecentAppsFragment.recentApps(RuntimeEnvironment.getApplication()).isEmpty())
        show(RecentAppsFragment.newInstance())
        compose.onNodeWithText("No recent apps yet — grant usage access").assertExists()
    }
}
