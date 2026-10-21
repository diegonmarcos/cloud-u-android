package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.ui.test.onNodeWithTag
import com.diegonmarcos.superapp.bottomnav.PageTabsTags
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1062 - Cloud ▸ WebApps is ONE page: the cloud-superapp site embedded in the shell (a WebView pane), not a second native grid.
 *
 *   E1 the page's URL is the one declared (webapps_base + webapps_path), https, with embed=1 and no second url or app list
 *   E2 the WebApps tab hosts a WebView on exactly that URL with the embed user agent and no floating Back
 *   E3 SuperApp's own Cloud strip is hidden on WebApps (`hide_tabs`) and still drawn on Cloud ▸ App; no other page hides it
 *   E4 the page is the section's own tab, so it renders inside the shell content area (below the top row), never a pushed full-screen page
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebAppsEmbedTest : KitPageHarness() {

    private val page get() = Sections.byId("cloud")!!.pages.first { it.id == "webapps" }

    private fun webViewIn(v: android.view.View): android.webkit.WebView? =
        if (v is android.webkit.WebView) v
        else (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { webViewIn(g.getChildAt(it)) } }

    @Test
    fun `E1 the URL is the one declaration, https, with embed=1`() {
        val url = page.url
        assertEquals("https://diegonmarcos.github.io/cloud-superapp/cloud/apps/?embed=1", url)
        assertTrue("https: $url", url.startsWith("https://"))
        assertTrue("embed=1: $url", WebPageFragment.isEmbed(url))
    }

    @Test
    fun `E2 the WebApps tab is a web view on the declared URL with the embed user agent`() {
        // The tab pane builds its page through this routing (SectionTabsFragment.render -> pageFragment -> SectionPages).
        val frag = SectionPages.pagesFor("cloud", includeHidden = true).first { it.id == "webapps" }.factory.invoke()
        assertTrue("the page routes to the embedded browser: ${frag.javaClass.simpleName}", frag is WebPageFragment)
        show(frag)
        compose.waitForIdle()
        val web = webViewIn(compose.activity.window.decorView)
        assertTrue("a WebView is hosted", web != null)
        assertEquals(page.url, web!!.url)
        assertTrue("UA marker: ${web.settings.userAgentString}", web.settings.userAgentString.endsWith(WebPageFragment.UA_MARKER))
        compose.onNodeWithTag(WebPageFragment.TAG_BACK).assertDoesNotExist()
    }

    @Test
    fun `E3 the Cloud strip is hidden on WebApps only`() {
        assertTrue("WebApps declares hide_tabs", page.hideTabs)
        assertTrue("no other Cloud page hides the strip", Sections.byId("cloud")!!.pages.filter { it.id != "webapps" }.none { it.hideTabs })
        show(SectionTabsFragment.newInstance("cloud", "webapps"))
        compose.onNodeWithTag(PageTabsTags.tab("apps")).assertDoesNotExist()
        compose.onNodeWithTag(PageTabsTags.tab("webapps")).assertDoesNotExist()
    }

    @Test
    fun `E3b the strip is still drawn on Cloud App`() {
        show(SectionTabsFragment.newInstance("cloud", "apps"))
        compose.onNodeWithTag(PageTabsTags.tab("webapps")).assertExists()
    }

    @Test
    fun `E4 WebApps is a tab pane of the Cloud section, not an action or a pushed page`() {
        assertTrue("it has a page URL, so SectionPages routes it to the pane's WebPageFragment", page.url.isNotBlank())
        assertTrue("it is not an action tile", page.action.isBlank())
        assertEquals("cloud's tab order is untouched", listOf("apps", "webapps", "lnktree", "configs"), Sections.byId("cloud")!!.pages.map { it.id })
    }
}
