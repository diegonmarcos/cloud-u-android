package com.diegonmarcos.superapp.apps

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import com.diegonmarcos.superapp.search.SearchPanelTags
import com.diegonmarcos.superapp.ui.KitPageHarness
import com.diegonmarcos.superapp.uikit.KitSearchTags
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Phone ▸ Apps mounts the same shared search bar (SearchFrame) Cloud ▸ Apps does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-xxhdpi")
class PhoneAppsSearchTest : KitPageHarness() {
    @Test fun `Phone Apps has the shared search bar and Back clears a query first`() {
        val page = show(SuitePhoneAppsFragment.newInstance())
        compose.onNodeWithTag(KitSearchTags.BAR).assertExists()
        compose.onNodeWithTag(SuitePhoneAppsFragment.TAG_LIST).assertExists()
        assertFalse(page.tryHandleBack())
        compose.onNodeWithTag(KitSearchTags.FIELD).performTextInput("zzzz-no-such-app")
        compose.waitForIdle()
        compose.onNodeWithTag(SearchPanelTags.CLOUD_SEARCH).assertExists()
        assertTrue(page.tryHandleBack())
        compose.waitForIdle()
        compose.onAllNodesWithTag(SearchPanelTags.CLOUD_SEARCH).assertCountEquals(0)
    }
}
