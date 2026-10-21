package com.diegonmarcos.superapp.search

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.launcher.Sections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1076 — the search box on Configs. By default it finds ONLY the Configs entries (page names, their headings, the
 * settings a page declares), filtering live as the query changes; the "Whole app" chip widens it to the rest of the app.
 * The Configs items are read from build.json's config section, never listed here.
 *
 *   S1 the default scope is Configs alone, and the entry offers exactly Configs + Whole app
 *   S2 default filtering: a query matches the Configs entries and nothing from the rest of the app
 *   S3 widening: switching Whole app on adds the app-wide hits, and the Configs hits stay
 *   S4 the Configs entries are the config section's declared pages (and their declared settings), no copy
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConfigsSearchTest {

    private class Fake(val configs: List<SearchHit>, val app: List<SearchHit>) : SearchSource {
        override fun hitsFor(scope: SearchScope) = when (scope.kind) {
            SearchKinds.CONFIGS -> configs
            SearchKinds.APP_ALL -> app
            else -> emptyList()
        }
    }

    private val configs = listOf(
        SearchHit("Cloud Mesh", "Configs · Setup · Network", "configs", target = "page:config/wg"),
        SearchHit("Firewall", "Configs · Setup · Network", "configs", target = "page:config/firewall"),
        SearchHit("About", "Configs · Observability", "configs", target = "page:config/about"),
    )
    private val app = listOf(
        SearchHit("Mesh Store", "Cloud · Tile", "app", target = "page:cloud/mesh-store"),
        SearchHit("Mail", "Cloud · Tile", "app", target = "section:mail"),
    )

    private fun controller() = SearchController(SearchEntry.CONFIGS_PAGE, Fake(configs, app), null, emptyList())

    @Test fun `S1 Configs starts on its own scope and offers it with Whole app only`() {
        val c = controller()
        assertEquals(listOf("configs", "app"), c.scopes.map { it.id })
        assertEquals(setOf("configs"), c.selected)
    }

    @Test fun `S2 by default a query finds Configs entries only`() {
        val c = controller()
        c.query = "mesh"
        assertEquals(listOf("Cloud Mesh"), c.sections().flatMap { it.hits }.map { it.label })
        c.query = "mail"
        assertTrue("nothing outside Configs is offered by default", c.sections().isEmpty())
        c.query = "network"   // the heading crumb is searched too
        assertEquals(listOf("Cloud Mesh", "Firewall"), c.sections().flatMap { it.hits }.map { it.label })
    }

    @Test fun `S3 the Whole app chip widens the same query`() {
        val c = controller()
        c.query = "mesh"
        c.toggle("app")
        assertEquals(setOf("configs", "app"), c.selected)
        assertEquals(listOf("configs" to "Cloud Mesh", "app" to "Mesh Store"),
            c.sections().flatMap { s -> s.hits.map { s.scope.id to it.label } })
        c.query = "mail"
        assertEquals(listOf("Mail"), c.sections().flatMap { it.hits }.map { it.label })
        c.toggle("app"); c.toggle("configs")   // the last chip never turns off, so Configs is still on
        assertTrue("configs" in c.selected)
    }

    @Test fun `S4 the Configs hits are the config section's declared pages and their declared settings`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val sec = Sections.byId("config")!!
        val hits = SuperappSearchIndex.configsOnly("configs")
        val pages = sec.pages.filter { !it.separator }
        assertTrue("every page is searchable", pages.all { p -> hits.any { it.label == p.label } })
        assertTrue("every declared setting is searchable", pages.flatMap { it.settings }.all { st -> hits.any { it.label == st } })
        assertTrue("only Configs pages", hits.all { it.crumb.startsWith(sec.label) })
        assertEquals(hits.size, SuperappSearchIndex.hitsFor(ctx, SearchScope("configs", "Configs", SearchKinds.CONFIGS)).size)
    }
}
