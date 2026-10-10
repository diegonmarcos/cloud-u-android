package com.diegonmarcos.superapp.launcher

import android.app.Application
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import com.diegonmarcos.superapp.search.SearchPanelTags
import com.diegonmarcos.superapp.ui.KitPageHarness
import com.diegonmarcos.superapp.uikit.KitSearchTags
import org.junit.Assert.assertEquals
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
        val root = page.requireView() as ViewGroup
        assertTrue("the first child of the page is the bar", root.getChildAt(0) is ComposeView)
        compose.onNodeWithTag(KitSearchTags.BAR).assertExists()
        assertFalse("Back with no query leaves the page", page.tryHandleBack())

        compose.onNodeWithTag(KitSearchTags.FIELD).performTextInput("zzzz-no-such-app")
        compose.waitForIdle()
        compose.onNodeWithTag(SearchPanelTags.DROPDOWN).assertExists()
        compose.onNodeWithTag(SearchPanelTags.CLOUD_SEARCH).assertExists()

        assertTrue("Back with a query clears it", page.tryHandleBack())
        compose.waitForIdle()
        val drop = (root.getChildAt(1) as ViewGroup).getChildAt(1)
        assertEquals(View.GONE, drop.visibility)
    }

    @Test fun `the Home sheet's Cloud tab is the same page without a second bar`() {
        val page = show(GroupedTilesFragment.newInstance("cloud"))
        assertTrue("the plain page is its scroller alone", page.requireView() is android.widget.ScrollView)
        assertFalse(page.tryHandleBack())
    }
}
