package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.ui.test.onNodeWithTag
import com.diegonmarcos.superapp.bottomnav.PageTabsTags
import com.diegonmarcos.superapp.launcher.WebPageFragment
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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

    private fun webViewIn(v: android.view.View): android.webkit.WebView? =
        if (v is android.webkit.WebView) v
        else (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { webViewIn(g.getChildAt(it)) } }

    @Test
    fun `C1 Cloud tabs are App, WebApps, Linktree, Configs in that order`() {
        show(SectionTabsFragment.newInstance("cloud"))
        val ids = Sections.byId("cloud")!!.pages.map { it.id }
        assertEquals(listOf("apps", "webapps", "lnktree", "configs"), ids)
        for (id in ids) compose.onNodeWithTag(PageTabsTags.tab(id)).assertExists()
        val xs = ids.map { compose.onNodeWithTag(PageTabsTags.tab(it)).fetchSemanticsNode().positionInRoot.x }
        assertEquals("the tabs are laid out in declared order", xs.sorted(), xs)
    }

    @Test
    fun `C2 WebApps is a native grid of the declared apps, not a web view`() {
        val page = Sections.byId("cloud")!!.pages.first { it.id == "webapps" }
        assertTrue("WebApps declares its apps", page.webapps.isNotEmpty())
        assertTrue("no url on the page itself: the apps are the single declaration", page.url.isBlank())
        show(LauncherNavController.webAppsGrid(compose.activity, page))
        for (w in page.webapps) compose.onNodeWithTag(tileTag(w.url)).assertExists()
        assertTrue(webViewIn(compose.activity.window.decorView) == null)
    }

    @Test
    fun `C3 a plain page carries no embed marker`() {
        show(WebPageFragment.newInstance("https://linktree.diegonmarcos.com"))
        val web = webViewIn(compose.activity.window.decorView)!!
        assertTrue(!web.settings.userAgentString.contains("CloudSuperApp-Embed"))
        compose.onNodeWithTag(WebPageFragment.TAG_BACK).assertExists()
    }

    @Test
    fun `C4 links stay in the page for the same site only`() {
        val u = "https://diegonmarcos.github.io/cloud-mobile/?embed=1"
        assertTrue(WebPageFragment.sameSite(u, "https://diegonmarcos.github.io/cloud-mobile/x/"))
        assertTrue(!WebPageFragment.sameSite(u, "https://example.com/"))
    }
}
