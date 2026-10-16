package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.ui.test.onNodeWithTag
import com.diegonmarcos.superapp.bottomnav.PageTabsTags
import com.diegonmarcos.superapp.launcher.WebPageFragment
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #773 — the embedded browser and the section tab strip, Compose since they were a FrameLayout around a WebView
 * and a LinearLayout around a PageTabsView.
 *
 *   W1 the browser draws its WebView and the floating Back control
 *   W2 the failure page names the host and the mesh-only reason
 *   S1 a section's strip draws one tab per declared page, none for a page without content ids
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebAndTabsComposeTest : KitPageHarness() {

    @Test
    fun `W1 the browser draws its web view and its back control`() {
        show(WebPageFragment.newInstance(""))
        compose.onNodeWithTag(WebPageFragment.TAG_WEB).assertExists()
        compose.onNodeWithTag(WebPageFragment.TAG_BACK).assertExists()
    }

    @Test
    fun `W2 the error page says which host and why`() {
        val html = WebPageFragment().errorHtml("paca.diegonmarcos.com", "net::ERR_NAME_NOT_RESOLVED")
        assertTrue(html.contains("Could not load paca.diegonmarcos.com"))
        assertTrue(html.contains("net::ERR_NAME_NOT_RESOLVED"))
        assertTrue(html.contains("WireGuard"))
    }

    @Test
    fun `S1 a section strip has a tab per declared page`() {
        val section = Sections.all().first { s -> s.pages.count { it.action.isBlank() } >= 2 && s.pages.size <= SectionTabsFragment.MAX_PANES }
        show(SectionTabsFragment.newInstance(section.id))
        for (p in section.pages) compose.onNodeWithTag(PageTabsTags.tab(p.id)).assertExists()
    }
}
