package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.BrowserBookmark
import com.diegonmarcos.superapp.browser.BrowserRemoteSuggest
import com.diegonmarcos.superapp.browser.BrowserSearchEngine
import com.diegonmarcos.superapp.browser.BrowserSuggest
import com.diegonmarcos.superapp.browser.BrowserTab
import com.diegonmarcos.superapp.browser.BrowserVisit
import com.diegonmarcos.superapp.browser.SuggestRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #886 part 6: the address bar's dropdown is two labelled sections, ranked, and the remote feed is gated. */
class BrowserSuggestSectionsTest {

    private val ddg = BrowserSearchEngine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q={q}", "https://duckduckgo.com/ac/?q={q}&type=list")
    private val now = 100L * 86_400_000L
    private fun visit(url: String, title: String, ageDays: Double) = BrowserVisit(url, title, now - (ageDays * 86_400_000).toLong())

    @Test
    fun `section one is the default engine - a search row first, then its query suggestions`() {
        val s = BrowserSuggest.sections("kotl", emptyList(), emptyList(), ddg, remote = listOf("kotlin", "kotlin tutorial", "kotl", "  "), now = now)
        assertEquals("Search DuckDuckGo for “kotl”", s.search.first().label)
        assertEquals("https://duckduckgo.com/?q=kotl", s.search.first().url)
        assertEquals(listOf("kotlin", "kotlin tutorial"), s.search.drop(1).map { it.label })
        assertEquals("a suggestion searches with the engine", "https://duckduckgo.com/?q=kotlin+tutorial", s.search[2].url)
        assertTrue(s.search.all { it.source == BrowserSuggest.Source.SEARCH })
        assertTrue(s.history.isEmpty())
    }

    @Test
    fun `section two is history with title and url, best match first then most recent`() {
        val hist = listOf(
            visit("https://blog.example/post-about-kotlin", "A post", 0.1),                       // url contains
            visit("https://kotlinlang.org/docs", "Kotlin docs", 30.0),                            // host starts with: best match
            visit("https://news.example/x", "Learning Kotlin", 0.2),                              // title word starts
            visit("https://kotlin.example/old", "Old kotlin page", 400.0),                        // host starts, but ancient
        )
        val s = BrowserSuggest.sections("kotlin", emptyList(), hist, ddg, now = now)
        assertEquals(listOf("https://kotlinlang.org/docs", "https://kotlin.example/old", "https://news.example/x", "https://blog.example/post-about-kotlin"),
            s.history.map { it.url })
        assertEquals("title is the label, url the subtitle", "Kotlin docs", s.history[0].label)
        assertEquals("https://kotlinlang.org/docs", s.history[0].subtitle)
    }

    @Test
    fun `between equal matches the newer visit ranks first`() {
        val hist = listOf(visit("https://a.example/one", "kotlin one", 20.0), visit("https://b.example/two", "kotlin two", 1.0))
        assertEquals(listOf("https://b.example/two", "https://a.example/one"), BrowserSuggest.sections("kotlin", emptyList(), hist, ddg, now = now).history.map { it.url })
    }

    @Test
    fun `open tabs lead the history section and a private tab is never offered`() {
        val open = BrowserTab("https://open.example/kotlin", "open", 1L, id = "o")
        val priv = BrowserTab("https://secret.example/kotlin", "secret", 2L, id = "p", isPrivate = true)
        val hist = listOf(visit("https://kotlinlang.org/", "Kotlin", 0.0))
        val s = BrowserSuggest.sections("kotlin", listOf(priv, open), hist, ddg, now = now)
        assertEquals(BrowserSuggest.Source.TAB, s.history.first().source)
        assertEquals("https://open.example/kotlin", s.history.first().url)
        assertTrue(s.history.none { it.url.contains("secret") })
    }

    @Test
    fun `an empty box offers nothing and the sections never exceed their limits`() {
        assertTrue(BrowserSuggest.sections("  ", emptyList(), emptyList(), ddg).isEmpty)
        val many = (1..40).map { visit("https://k.example/$it", "kotlin $it", it.toDouble()) }
        val s = BrowserSuggest.sections("kotlin", emptyList(), many, ddg, remote = (1..40).map { "kotlin $it" }, now = now)
        assertEquals(BrowserSuggest.HISTORY_LIMIT, s.history.size)
        assertEquals(BrowserSuggest.SEARCH_LIMIT, s.search.size)
        assertEquals("dense: five rows a section", 5, s.history.size)
    }

    @Test
    fun `the panel draws a title only above a section that has rows`() {
        val s = BrowserSuggest.sections("zzz", emptyList(), emptyList(), ddg, now = now)
        val rows = SuggestRow.build(s, "DuckDuckGo")
        assertEquals(listOf("Web search · DuckDuckGo"), rows.filterIsInstance<SuggestRow.Title>().map { it.text })
        val withHist = SuggestRow.build(BrowserSuggest.sections("k", emptyList(), listOf(visit("https://k.example/", "k", 0.0)), ddg, now = now), "DuckDuckGo")
        assertEquals(listOf("Web search · DuckDuckGo", "History"), withHist.filterIsInstance<SuggestRow.Title>().map { it.text })
    }

    // ── #8xx the third section: Favorites ──

    private fun fav(url: String, title: String, folder: String = "") = BrowserBookmark(url, title, folder, 1L)
    private val favs = listOf(
        fav("https://diegonmarcos.github.io/leafy/", "Leafy", "GitHub Pages"),
        fav("https://diegonmarcos.github.io/linktree/", "Linktree", "GitHub Pages"),
        fav("https://git.diegonmarcos.com", "Gitea", "Public"),
        fav("https://notes.example/leaf-notes", "Notes"),
    )

    @Test
    fun `three sections in order - web search, history, favorites - each with its own title`() {
        val hist = listOf(visit("https://leaf.example/", "Leaf blower", 1.0))
        val s = BrowserSuggest.sections("leaf", emptyList(), hist, ddg, remote = listOf("leaf spring"), now = now, favourites = favs)
        assertEquals(listOf(BrowserSuggest.Source.SEARCH, BrowserSuggest.Source.HISTORY, BrowserSuggest.Source.FAV),
            s.flat.map { it.source }.distinct())
        assertEquals(listOf("Leafy", "Notes"), s.favourites.map { it.label })
        val rows = SuggestRow.build(s, "DuckDuckGo")
        assertEquals(listOf("Web search · DuckDuckGo", "History", "Favorites"), rows.filterIsInstance<SuggestRow.Title>().map { it.text })
        val order = rows.map { r -> if (r is SuggestRow.Title) r.text else (r as SuggestRow.Item).s.source.name }
        assertEquals(listOf("Web search · DuckDuckGo", "SEARCH", "SEARCH", "History", "HISTORY", "Favorites", "FAV", "FAV"), order)
    }

    @Test
    fun `favorites include the github pages section and match by title or address`() {
        assertEquals(listOf("https://diegonmarcos.github.io/leafy/", "https://diegonmarcos.github.io/linktree/"),
            BrowserSuggest.sections("github.io", emptyList(), emptyList(), ddg, now = now, favourites = favs).favourites.map { it.url })
        assertEquals(listOf("Gitea"), BrowserSuggest.sections("gitea", emptyList(), emptyList(), ddg, now = now, favourites = favs).favourites.map { it.label })
    }

    @Test
    fun `favorites are capped, ranked best match first, and a page already in history is not repeated`() {
        val many = (1..30).map { fav("https://p.example/$it", "page $it") } + fav("https://best.example/", "pagex start")
        val s = BrowserSuggest.sections("page", emptyList(), emptyList(), ddg, now = now, favourites = many)
        assertEquals(BrowserSuggest.FAV_LIMIT, s.favourites.size)
        val hist = listOf(visit("https://diegonmarcos.github.io/leafy/", "Leafy", 0.0))
        val both = BrowserSuggest.sections("leafy", emptyList(), hist, ddg, now = now, favourites = favs)
        assertEquals(listOf("https://diegonmarcos.github.io/leafy/"), both.history.map { it.url })
        assertTrue("shown once, in History", both.favourites.none { it.url == "https://diegonmarcos.github.io/leafy/" })
    }

    @Test
    fun `no favorite match draws no Favorites title, and an empty box offers no favorites`() {
        val s = BrowserSuggest.sections("zzz", emptyList(), emptyList(), ddg, now = now, favourites = favs)
        assertTrue(s.favourites.isEmpty())
        assertFalse(SuggestRow.build(s, "DuckDuckGo").any { it is SuggestRow.Title && it.text == "Favorites" })
        assertTrue(BrowserSuggest.sections(" ", emptyList(), emptyList(), ddg, favourites = favs).isEmpty)
    }

    // ── the remote feed ──

    @Test
    fun `the feed is only asked when the setting is on, not for a url, not from a private tab`() {
        assertTrue(BrowserRemoteSuggest.shouldAsk(true, "kotlin", false))
        assertFalse("setting off", BrowserRemoteSuggest.shouldAsk(false, "kotlin", false))
        assertFalse("private tab", BrowserRemoteSuggest.shouldAsk(true, "kotlin", true))
        assertFalse("a url is not a query", BrowserRemoteSuggest.shouldAsk(true, "example.com/path", false))
        assertFalse("one letter is too early", BrowserRemoteSuggest.shouldAsk(true, "k", false))
    }

    @Test
    fun `the feed url is the engine's own, https only, with the query encoded`() {
        assertEquals("https://duckduckgo.com/ac/?q=a+b%26c&type=list", BrowserRemoteSuggest.urlFor(ddg.suggest, "a b&c"))
        assertNull("an engine with no feed", BrowserRemoteSuggest.urlFor(null, "x"))
        assertNull("plain http is refused", BrowserRemoteSuggest.urlFor("http://x.example/?q={q}", "x"))
        assertNull("a template with no query slot", BrowserRemoteSuggest.urlFor("https://x.example/", "x"))
    }

    @Test
    fun `the feed's answer is read as OpenSearch and anything else is nothing`() {
        assertEquals(listOf("kotlin", "kotlin java"), BrowserRemoteSuggest.parse("""["kotl",["kotlin","kotlin java"]]"""))
        assertEquals(emptyList<String>(), BrowserRemoteSuggest.parse("<html>blocked</html>"))
        assertEquals(emptyList<String>(), BrowserRemoteSuggest.parse(null))
        assertEquals(emptyList<String>(), BrowserRemoteSuggest.parse("""{"a":1}"""))
    }
}
