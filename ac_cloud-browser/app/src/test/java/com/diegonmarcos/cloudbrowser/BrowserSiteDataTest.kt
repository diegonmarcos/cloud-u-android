package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.CookieScope
import com.diegonmarcos.superapp.browser.CrawlLimits
import com.diegonmarcos.superapp.browser.OfflineIndex
import com.diegonmarcos.superapp.browser.OfflinePage
import com.diegonmarcos.superapp.browser.OfflineSite
import com.diegonmarcos.superapp.browser.SiteCrawl
import com.diegonmarcos.superapp.browser.SiteData
import com.diegonmarcos.superapp.browser.SiteScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #887 site data: URL scoping to one origin, the crawl's limits, size accounting, the offline index, per-site cookies. */
class BrowserSiteDataTest {

    // ── scope ──

    @Test
    fun `an origin is scheme, host and a non-default port, lower-cased`() {
        assertEquals("https://example.org", SiteScope.origin("https://Example.ORG/a/b?x=1#f"))
        assertEquals("https://example.org", SiteScope.origin("https://example.org:443/"))
        assertEquals("http://localhost:8000", SiteScope.origin("http://localhost:8000/x"))
        assertEquals("", SiteScope.origin("file:///x"))
        assertEquals("", SiteScope.origin("mailto:a@b.c"))
        assertEquals("", SiteScope.origin("not a url"))
    }

    @Test
    fun `same origin is exact - a subdomain, another scheme or another port is another site`() {
        assertTrue(SiteScope.sameOrigin("https://a.example/x", "https://a.example/y?z"))
        assertFalse(SiteScope.sameOrigin("https://a.example/", "https://b.a.example/"))
        assertFalse(SiteScope.sameOrigin("https://a.example/", "http://a.example/"))
        assertFalse(SiteScope.sameOrigin("https://a.example/", "https://a.example:8443/"))
        assertFalse(SiteScope.sameOrigin("", ""))
    }

    @Test
    fun `normalising drops the fragment and keeps the query`() {
        assertEquals("https://a.example/p?x=1", SiteScope.normalize("https://A.example/p?x=1#top"))
        assertEquals("https://a.example/", SiteScope.normalize("https://a.example"))
        assertNull(SiteScope.normalize("ftp://a.example/"))
    }

    @Test
    fun `links in scope are same-origin pages, once each`() {
        val got = SiteScope.inScope("https://a.example/start", listOf(
            "https://a.example/one", "https://a.example/one#frag", "https://a.example/two?x=1",
            "https://other.example/one", "http://a.example/one", "mailto:x@a.example", "https://a.example/file.zip",
            "https://a.example/pic.png", "https://a.example/doc.pdf", "https://a.example/app.js", "javascript:void(0)",
        ))
        assertEquals(listOf("https://a.example/one", "https://a.example/two?x=1"), got)
    }

    // ── the crawl ──

    private fun site(vararg links: Pair<String, List<String>>) = links.toMap()

    /** Drive a crawl against a link graph the way OfflineSiteJob does; returns the pages saved in order. */
    private fun run(start: String, limits: CrawlLimits, graph: Map<String, List<String>>, size: Long = 1000): Pair<List<String>, SiteCrawl> {
        val c = SiteCrawl(start, limits)
        val saved = ArrayList<String>()
        while (true) {
            val item = c.next() ?: break
            saved.add(item.url)
            c.saved(size)
            c.offer(item.depth, graph[item.url].orEmpty())
        }
        return saved to c
    }

    private val graph = site(
        "https://a.example/" to listOf("https://a.example/1", "https://a.example/2", "https://other.example/x"),
        "https://a.example/1" to listOf("https://a.example/", "https://a.example/3"),
        "https://a.example/2" to listOf("https://a.example/4"),
        "https://a.example/3" to listOf("https://a.example/5"),
    )

    @Test
    fun `depth zero saves only the start page`() {
        val (saved, _) = run("https://a.example/", CrawlLimits(0, 50, 1L shl 30), graph)
        assertEquals(listOf("https://a.example/"), saved)
    }

    @Test
    fun `depth one follows the start page's links but not theirs`() {
        val (saved, _) = run("https://a.example/", CrawlLimits(1, 50, 1L shl 30), graph)
        assertEquals(listOf("https://a.example/", "https://a.example/1", "https://a.example/2"), saved)
    }

    @Test
    fun `depth two goes one level further, never leaves the site, never repeats a page`() {
        val (saved, c) = run("https://a.example/", CrawlLimits(2, 50, 1L shl 30), graph)
        assertEquals(listOf("https://a.example/", "https://a.example/1", "https://a.example/2", "https://a.example/3", "https://a.example/4"), saved)
        assertTrue(saved.none { it.contains("other.example") })
        assertEquals(saved.size, saved.toSet().size)
        assertNull("ran out of pages, not a limit", c.stopReason)
    }

    @Test
    fun `the page limit stops the crawl and says so`() {
        val (saved, c) = run("https://a.example/", CrawlLimits(3, 2, 1L shl 30), graph)
        assertEquals(2, saved.size)
        assertEquals("page limit (2)", c.stopReason)
    }

    @Test
    fun `the size limit stops the crawl after the page that reaches it`() {
        val (saved, c) = run("https://a.example/", CrawlLimits(3, 50, 2500L), graph, size = 1000)
        assertEquals(3, saved.size)           // 1000, 2000, 3000 >= 2500: the third page crosses it, the fourth never starts
        assertTrue(c.stopReason!!.startsWith("size limit"))
    }

    @Test
    fun `limits come from the settings and are clamped to their declared ranges`() {
        val l = CrawlLimits.of(9, 9999, 1)
        assertEquals(3, l.depth); assertEquals(200, l.maxPages); assertEquals(5L * 1024 * 1024, l.maxBytes)
        val d = CrawlLimits.of(null, null, null)
        assertEquals(1, d.depth); assertEquals(25, d.maxPages); assertEquals(50L * 1024 * 1024, d.maxBytes)
    }

    @Test
    fun `a start that is not a web page crawls nothing`() {
        assertNull(SiteCrawl("file:///x", CrawlLimits(1, 5, 1000)).next())
    }

    // ── sizes ──

    @Test
    fun `sizes read as a person reads them`() {
        assertEquals("0 B", SiteData.human(0))
        assertEquals("1023 B", SiteData.human(1023))
        assertEquals("1.0 KB", SiteData.human(1024))
        assertEquals("1.5 MB", SiteData.human(1_572_864))
        assertEquals("2.0 GB", SiteData.human(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `the total counts what is measurable and everything means every item`() {
        val items = listOf(SiteData.Item("a", "A", 100), SiteData.Item("b", "B", null), SiteData.Item("c", "C", 24))
        assertEquals(124L, SiteData.total(items))
        assertEquals(setOf("a", "b", "c"), SiteData.everything(items))
    }

    // ── the offline index ──

    private val s1 = OfflineSite("id1", "Site one", "https://a.example", "https://a.example/", 2000L,
        listOf(OfflinePage("https://a.example/", "id1/p0.mhtml", 1500, "Home"), OfflinePage("https://a.example/x?y=1", "id1/p1.mhtml", 500, "X")))
    private val s2 = OfflineSite("id2", "Page", "https://b.example", "https://b.example/p", 3000L,
        listOf(OfflinePage("https://b.example/p", "id2/p0.mhtml", 700, "P")), kind = "page", stopped = "page limit (1)")

    @Test
    fun `the index round-trips, newest first, with sizes summed from the pages`() {
        val back = OfflineIndex.fromJson(OfflineIndex.toJson(listOf(s1, s2)))
        assertEquals(listOf("id2", "id1"), back.map { it.id })
        assertEquals(2000L, back.first { it.id == "id1" }.bytes)
        assertEquals("page limit (1)", back.first { it.id == "id2" }.stopped)
        assertEquals("page", back.first { it.id == "id2" }.kind)
        assertTrue(OfflineIndex.fromJson("garbage").isEmpty())
    }

    @Test
    fun `deleting one copy leaves the others`() {
        assertEquals(listOf("id2"), OfflineIndex.remove(listOf(s1, s2), "id1").map { it.id })
        assertEquals(2, OfflineIndex.remove(listOf(s1, s2), "nope").size)
    }

    @Test
    fun `a link inside a copy finds the saved page, fragment and case aside`() {
        assertEquals("id1/p1.mhtml", OfflineIndex.pageFor(s1, "https://A.example/x?y=1#sec")!!.file)
        assertNull(OfflineIndex.pageFor(s1, "https://a.example/other"))
        val (site, page) = OfflineIndex.find(listOf(s1, s2), "https://b.example/p")!!
        assertEquals("id2", site.id); assertEquals("id2/p0.mhtml", page.file)
        assertNull(OfflineIndex.find(listOf(s1, s2), "https://c.example/"))
    }

    // ── cookies of one site ──

    @Test
    fun `a cookie header yields its names, once`() {
        assertEquals(listOf("a", "session"), CookieScope.names("a=1; session=xyz; a=2"))
        assertTrue(CookieScope.names(null).isEmpty())
        assertTrue(CookieScope.names("").isEmpty())
    }

    @Test
    fun `a site's cookie hosts are itself and its parents down to two labels`() {
        assertEquals(listOf("a.b.example.org", "b.example.org", "example.org"), CookieScope.hosts("a.b.example.org"))
        assertEquals(listOf("example.org"), CookieScope.hosts("example.org"))
        assertEquals(listOf("localhost"), CookieScope.hosts("localhost"))
        assertTrue(CookieScope.hosts("").isEmpty())
    }

    @Test
    fun `expiring a name covers host-only and Domain variants, and touches only that site's hosts`() {
        val e = CookieScope.expiries("www.example.org", listOf("sid"))
        assertTrue(e.all { it.second.contains("Max-Age=0") && it.second.startsWith("sid=") })
        assertTrue(e.any { it.first == "https://www.example.org/" && !it.second.contains("Domain=") })
        assertTrue(e.any { it.second.contains("Domain=.example.org") })
        assertTrue("no other site is named", e.all { it.first.contains("example.org") })
        assertFalse(e.any { it.first.contains("other") })
        assertTrue(CookieScope.expiries("www.example.org", emptyList()).isEmpty())
        assertNotNull(CookieScope.expiries("localhost", listOf("a"), secure = false).firstOrNull { it.first == "http://localhost/" })
    }
}
