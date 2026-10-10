package com.diegonmarcos.superapp.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * libs:search's engine — the one every SuperApp search runs (the Home star, the Home swipe sheet,
 * Cloud ▸ Apps): matching, one section per scope in the declared order, the scope filter, the
 * per-entry defaults, the chip rule and the colon rule. Plain JVM: no screen, no Android call.
 */
class SearchEngineTest {

    /** The seven scopes, as build.json::ui.search_scopes declares them (SearchScopesDeclaredTest
     *  holds the real file to this). */
    private val scopes = listOf(
        SearchScope("cloud-apps", "Cloud-Apps", SearchKinds.CLOUD_APPS, "Cloud apps"),
        SearchScope("phone-apps", "Phone-Apps", SearchKinds.PHONE_APPS, "Phone apps"),
        SearchScope("cloud-configs", "Cloud-Configs", SearchKinds.CLOUD_CONFIGS, "Cloud configs"),
        SearchScope("phone-configs", "Phone-Configs", SearchKinds.PHONE_CONFIGS, "Phone configs"),
        SearchScope("browser-fav", "Browser-Fav", SearchKinds.BROWSER_FAV, "Browser favourites"),
        SearchScope("browser-history", "Browser-History", SearchKinds.BROWSER_HISTORY, "Browser history"),
        SearchScope("browser-web", "Browser-Web", SearchKinds.BROWSER_WEB, "Web"),
    )
    private val all = scopes.map { it.id }.toSet()

    private fun hit(label: String, scope: String, crumb: String = "") = SearchHit(label, crumb, scope, target = "t:$label")

    // Built in a scrambled order on purpose: the sections must follow the DECLARED order, not this.
    private val index = listOf(
        hit("Mail settings", "phone-configs"),
        hit("Mail", "phone-apps"),
        hit("Cloud Mail", "cloud-apps"),
        hit("mail.diegonmarcos.com", "cloud-configs", "Cloud config · oci-mail"),
        hit("Drive", "cloud-apps"),
        hit("Maildir", "cloud-apps", "Cloud · Tile"),
    )
    private val live = listOf(
        hit("Search the web for “mail”", "browser-web"),
        hit("Gmail inbox", "browser-history", "https://mail.google.com · today"),
        hit("Webmail", "browser-fav", "https://mail.example"),
    )

    // ── sections ───────────────────────────────────────────────────────────────

    @Test fun `results come back grouped per scope, in the declared section order`() {
        val s = SearchEngine.sections(scopes, index, all, "mail", live)
        assertEquals(listOf("Cloud apps", "Phone apps", "Cloud configs", "Phone configs",
            "Browser favourites", "Browser history", "Web"), s.map { it.scope.section })
        assertEquals(listOf("Cloud Mail", "Maildir"), s[0].hits.map { it.label })
        assertEquals(2, s[0].total)
        // Each section holds only its own scope's rows.
        assertTrue(s.all { sec -> sec.hits.all { it.source == sec.scope.id } })
    }

    @Test fun `a scope that is off, or has no hit, draws no section`() {
        val s = SearchEngine.sections(scopes, index, all - "phone-apps", "drive", emptyList())
        assertEquals(listOf("cloud-apps"), s.map { it.scope.id })
        assertTrue(SearchEngine.sections(scopes, index, all, "zzz-nothing", emptyList()).isEmpty())
    }

    @Test fun `matching is case-insensitive over label and crumb, and an empty query matches everything`() {
        val byCrumb = SearchEngine.sections(scopes, index, all, "OCI-MAIL")
        assertEquals(listOf("mail.diegonmarcos.com"), byCrumb.flatMap { it.hits }.map { it.label })
        assertEquals(index.size, SearchEngine.sections(scopes, index, all, "  ").sumOf { it.total })
    }

    @Test fun `live hits were matched by their own source and are filtered by scope only`() {
        // "inbox" is not in the web row's label, but the browser matched it for this query.
        val s = SearchEngine.sections(scopes, emptyList(), setOf("browser-history"), "inbox", live)
        assertEquals(listOf("Gmail inbox"), s.flatMap { it.hits }.map { it.label })
    }

    @Test fun `rows are capped per scope, never below the floor, and total counts every match`() {
        val many = (1..100).map { hit("App $it", "cloud-apps") } + (1..100).map { hit("App $it", "phone-apps") }
        val one = SearchEngine.sections(scopes, many, setOf("cloud-apps"), "app")
        assertEquals(SearchEngine.CAP, one.single().hits.size)
        assertEquals(100, one.single().total)
        val seven = SearchEngine.sections(scopes, many, all, "app")
        assertEquals(SearchEngine.MIN_PER_SCOPE, seven.first().hits.size)
        val two = SearchEngine.sections(scopes, many, setOf("cloud-apps", "phone-apps"), "app")
        assertEquals(SearchEngine.CAP / 2, two.first().hits.size)
    }

    @Test fun `Go opens the first row of the first section`() {
        val s = SearchEngine.sections(scopes, index, all, "mail", live)
        assertEquals("Cloud Mail", SearchEngine.top(s)?.label)
        assertNull(SearchEngine.top(emptyList()))
    }

    @Test fun `the last chip on stays on`() {
        assertEquals(setOf("a", "b"), SearchEngine.toggle(setOf("a"), "b"))
        assertEquals(setOf("a"), SearchEngine.toggle(setOf("a", "b"), "b"))
        assertEquals(setOf("a"), SearchEngine.toggle(setOf("a"), "a"))
    }

    @Test fun `no declared scopes is one unscoped list`() {
        assertEquals(listOf(SearchEngine.ALL), SearchEngine.scopesOrAll(emptyList()).map { it.id })
        assertEquals(scopes, SearchEngine.scopesOrAll(scopes))
    }

    // ── defaults per entry point ──────────────────────────────────────────────

    @Test fun `Cloud Apps and the swipe sheet start on apps and configs, every browser scope off`() {
        val apps = setOf("cloud-apps", "phone-apps", "cloud-configs", "phone-configs")
        assertEquals(apps, SearchEntry.CLOUD_APPS_PAGE.defaults(scopes))
        assertEquals(apps, SearchEntry.HOME_SHEET.defaults(scopes))
    }

    @Test fun `the Home star starts the other way round, browser and configs on, apps off`() {
        assertEquals(setOf("browser-fav", "browser-history", "browser-web", "cloud-configs", "phone-configs"),
            SearchEntry.HOME_STAR.defaults(scopes))
    }

    @Test fun `a saved choice wins, and a never-set or vanished one falls back to the defaults`() {
        val e = SearchEntry.HOME_STAR
        assertEquals(e.defaults(scopes), e.selection(scopes, null))
        assertEquals(setOf("cloud-apps"), e.selection(scopes, setOf("cloud-apps", "gone")))
        assertEquals(e.defaults(scopes), e.selection(scopes, setOf("gone")))
        assertEquals(e.defaults(scopes), e.selection(scopes, emptySet()))
    }

    @Test fun `the entry points are told apart in storage`() {
        assertEquals(3, SearchEntry.values().map { it.id }.toSet().size)
    }

    @Test fun `a taxonomy with none of an entry's kinds starts on everything`() {
        val other = listOf(SearchScope("x", "X", "other"), SearchScope("y", "Y", "other"))
        assertEquals(setOf("x", "y"), SearchEntry.HOME_STAR.defaults(other))
    }

    // ── the colon rule ────────────────────────────────────────────────────────

    private val commands = listOf(
        SearchCommand("update-all", "Update all", "action:update_all"),
        SearchCommand("check-updates", "Check updates", "action:check_updates"),
    )

    @Test fun `a command line is a colon in the very first position, nowhere else`() {
        assertTrue(SearchEngine.isCommandMode(":update-all", commands))
        assertFalse(SearchEngine.isCommandMode(" :update-all", commands))
        assertFalse(SearchEngine.isCommandMode("Bars in Berlin:Mitte", commands))
        assertFalse(SearchEngine.isCommandMode(":update-all", emptyList()))
    }

    @Test fun `Go runs the one exact alias, the list narrows by alias or label`() {
        assertEquals("action:update_all", SearchEngine.exactCommand(":  Update-All ", commands)?.target)
        assertNull(SearchEngine.exactCommand(":update", commands))
        assertEquals(2, SearchEngine.commandMatches(":", commands).size)
        assertEquals(listOf("check-updates"), SearchEngine.commandMatches(":check", commands).map { it.alias })
    }
}
