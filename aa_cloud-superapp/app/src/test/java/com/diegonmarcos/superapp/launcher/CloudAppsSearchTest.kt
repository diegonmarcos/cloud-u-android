package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
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

/**
 * Cloud ▸ Apps searches with the SuperApp's ONE search: the second engine it once had (AppsSearch)
 * is gone, the page mounts the shared panel with its bar at the TOP (directly under the page's tab
 * icons, no longer pinned at the bottom), and its results are the shared sections, Cloud Search
 * hand-off included.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-xxhdpi")
class CloudAppsSearchTest : KitPageHarness() {

    @Test fun `the old AppsSearch engine no longer exists`() {
        val gone = runCatching { Class.forName("com.diegonmarcos.superapp.launcher.AppsSearch") }
        assertTrue(gone.exceptionOrNull() is ClassNotFoundException)
    }

    @Test fun `Cloud Apps puts the shared bar first, above the grid, and searches with the shared panel`() {
        val page = show(GroupedTilesFragment.newInstance("cloud", search = true))
        compose.onNodeWithTag(KitSearchTags.BAR).assertExists()
        val bar = compose.onNodeWithTag(KitSearchTags.BAR).getUnclippedBoundsInRoot()
        val list = compose.onNodeWithTag(GroupedTilesFragment.TAG_LIST).getUnclippedBoundsInRoot()
        assertTrue("the bar sits above the grid (bar bottom ${bar.bottom} <= grid top ${list.top})", bar.bottom <= list.top)
        assertFalse("Back with no query leaves the page", page.tryHandleBack())

        compose.onNodeWithTag(KitSearchTags.FIELD).performTextInput("zzzz-no-such-app")
        compose.waitForIdle()
        compose.onNodeWithTag(SearchPanelTags.DROPDOWN).assertExists()
        compose.onNodeWithTag(SearchPanelTags.CLOUD_SEARCH).assertExists()

        assertTrue("Back with a query clears it", page.tryHandleBack())
        compose.waitForIdle()
        // The query is gone, so the results (and the Cloud Search hand-off) are gone and the grid is back under the
        // bar. (Whether the bar still holds focus is the window's business: a JVM test has no focused window.)
        compose.onAllNodesWithTag(SearchPanelTags.CLOUD_SEARCH).assertCountEquals(0)
        compose.onNodeWithTag(GroupedTilesFragment.TAG_LIST).assertExists()
    }

    @Test fun `the Home sheet's Cloud tab is the same page without a second bar`() {
        val page = show(GroupedTilesFragment.newInstance("cloud"))
        compose.onAllNodesWithTag(KitSearchTags.BAR).assertCountEquals(0)
        compose.onNodeWithTag(GroupedTilesFragment.TAG_LIST).assertExists()
        assertFalse(page.tryHandleBack())
    }
}
