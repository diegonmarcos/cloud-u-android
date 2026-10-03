package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.BrowserConfig
import com.diegonmarcos.superapp.browser.BrowserSearchEngine
import com.diegonmarcos.superapp.browser.BrowserSitePolicy
import com.diegonmarcos.superapp.browser.BrowserSuggest
import com.diegonmarcos.superapp.browser.BrowserTab
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #802 I5 site rules resolve by host suffix; a private tab is never recorded or suggested. */
class BrowserPrivacyTest {

    private val rules = mapOf(
        BrowserSitePolicy.key("example.org", "geolocation") to "deny",
        BrowserSitePolicy.key("maps.example.org", "geolocation") to "allow",
    )

    @Test
    fun `a rule covers subdomains, and the most specific host wins`() {
        assertEquals("deny", BrowserSitePolicy.resolve(rules, "example.org", "geolocation", "ask"))
        assertEquals("deny", BrowserSitePolicy.resolve(rules, "news.example.org", "geolocation", "ask"))
        assertEquals("allow", BrowserSitePolicy.resolve(rules, "a.maps.example.org", "geolocation", "ask"))
        // Not a suffix match on a label boundary: notexample.org is a different site.
        assertEquals("ask", BrowserSitePolicy.resolve(rules, "notexample.org", "geolocation", "ask"))
        assertEquals("ask", BrowserSitePolicy.resolve(rules, "example.org", "camera", "ask"))
    }

    @Test
    fun `host comes from the URL, case-insensitively`() {
        assertEquals("example.org", BrowserSitePolicy.hostOf("https://Example.ORG/path?q=1"))
        assertEquals("", BrowserSitePolicy.hostOf("not a url"))
    }

    @Test
    fun `a private tab is not recorded and not suggested`() {
        val priv = BrowserTab("https://secret.example/x", "Secret", 2, isPrivate = true)
        val open = BrowserTab("https://secret.example/y", "Open", 1)
        assertFalse(BrowserSitePolicy.shouldRecord(priv))
        assertTrue(BrowserSitePolicy.shouldRecord(open))
        val s = BrowserSuggest.suggest("secret", listOf(priv, open), emptyList(),
            BrowserSearchEngine("q", "Q", "https://q/?q=${BrowserSearchEngine.QUERY}"))
        assertEquals(listOf("https://secret.example/y"), s.filter { it.source == BrowserSuggest.Source.TAB }.map { it.url })
    }

    @Test
    fun `the shipped site permissions and clear-data boxes parse`() {
        val cfg = BrowserConfig.parse(JSONObject(File("../build.json").readText()).getJSONObject("ui").getJSONObject("browser").toString())
        val geo = cfg.sitePerms.first { it.id == "geolocation" }
        assertEquals("ask", geo.default)
        assertTrue("geolocation" in geo.webkit)
        assertTrue(geo.android.contains("android.permission.ACCESS_FINE_LOCATION"))
        assertEquals("allow", cfg.sitePerms.first { it.id == "javascript" }.default)
        assertTrue(cfg.clearData.map { it.first }.containsAll(listOf("history", "cookies", "cache", "storage")))
    }
}
