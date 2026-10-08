package com.diegonmarcos.cloudsearch

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.diegonmarcos.cloudsearch.core.Http
import com.diegonmarcos.cloudsearch.core.Templates
import com.diegonmarcos.cloudsearch.data.Account
import com.diegonmarcos.cloudsearch.data.Services
import com.diegonmarcos.cloudsearch.debugapi.SearchDebugApi
import com.diegonmarcos.cloudsearch.ui.SearchShell
import com.diegonmarcos.cloudsearch.ui.SearchState
import com.diegonmarcos.cloudsearch.ui.Tags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The UI smoke test PER VERTICAL AND SUBPAGE: the real shell composed under Robolectric against a
 * fake network, every declared vertical reached through the app's own bottom nav (#797) and every
 * one of its declared subpages composed. Every id comes from the declaration, none is restated here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchShellTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    /** One job from the Bundesagentur, a refusal from everything else; records what was asked. */
    private class FakeHttp : Http {
        val asked = mutableListOf<String>()
        val headersAsked = mutableListOf<Map<String, String>>()
        override fun get(url: String, headers: Map<String, String>, timeoutMs: Int): Http.Response {
            synchronized(asked) { asked += url; headersAsked += headers }
            return when {
                // #903 Things: two stores (one of a chain the price service reads), a geocoded city, the service's answer.
                "overpass" in url -> Http.Response(200,
                    """{"elements":[
                       {"type":"node","id":1,"lat":48.14,"lon":11.58,"tags":{"name":"OBI Mitte","brand":"OBI","shop":"doityourself","addr:street":"Hauptstr.","addr:housenumber":"1"}},
                       {"type":"node","id":2,"lat":48.15,"lon":11.59,"tags":{"name":"Kleiner Baumarkt","shop":"doityourself"}}]}""")
                "nominatim" in url -> Http.Response(200, """[{"lat":"48.1371","lon":"11.5753","name":"München","display_name":"München, Bayern, Deutschland"}]""")
                "prices.openfoodfacts" in url -> Http.Response(200, """{"items":[]}""")
                "/scrappers/prices" in url -> Http.Response(200,
                    """{"results":[{"adapter":"obi","label":"OBI","status":"ok","price":24.49,"currency":"EUR","title":"Bohrmaschine X","url":"https://www.obi.de/p/1","fetched_at":1790000000000}],
                       "adapters":[{"adapter":"obi","label":"OBI","enabled":true,"brands":["obi"],"why":""}]}""")
                "arbeitsagentur" in url -> Http.Response(200,
                    """{"maxErgebnisse":1,"ergebnisliste":[{"referenznummer":"r-1","stellenangebotsTitel":"Kotlin Developer","firma":"ACME","arbeitszeitVollzeit":true}]}""")
                // The Bundesbank's SDMX-JSON shape, two observations a year apart.
                "bundesbank" in url -> Http.Response(200,
                    """{"data":{"structure":{"dimensions":{"observation":[{"id":"TIME_PERIOD","values":[{"id":"2025-08"},{"id":"2026-08"}]}]}},
                       "dataSets":[{"series":{"0:0":{"observations":{"0":["3.71"],"1":["4.01"]}}}}]}}""")
                else -> Http.Response(503, "")
            }
        }
        override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): Http.Response {
            synchronized(asked) { asked += url }
            return Http.Response(503, "")
        }
    }

    private val http = FakeHttp()
    private lateinit var services: Services
    private lateinit var state: SearchState
    private lateinit var savedReader: (android.content.Context, String) -> Account.Token
    private val savedFleetRead = com.diegonmarcos.cloudsearch.data.FleetBearer.reader
    private val savedLocator = com.diegonmarcos.cloudsearch.data.Locator.reader

    @Before fun offline() {
        services = Services(RuntimeEnvironment.getApplication(), http)
        // Things asks the coarse-location permission once, through the system dialog: not under test here.
        services.prefs.locationAsked = true
        Services.install(services)
        state = SearchState(services)
        savedReader = Account.reader
        Account.reader = { _, _ -> Account.Token(null, "no token in this test") }
    }

    @After fun online() {
        com.diegonmarcos.cloudsearch.data.FleetBearer.reader = savedFleetRead
        com.diegonmarcos.cloudsearch.data.Locator.reader = savedLocator
        Account.reader = savedReader
        Services.install(null)
    }

    private fun launch() = compose.setContent { SearchShell(state) }

    /** #913 a vertical through the app's own nav: the assistant is the island's Chat; every other one a pill of Web Search's strip. */
    private fun openVertical(id: String) {
        if (id == state.chatVertical) compose.onNodeWithTag(Tags.nav("chat")).performClick()
        else {
            compose.onNodeWithTag(Tags.nav("web")).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(Tags.subpage(id)).performClick()
        }
        compose.waitForIdle()
    }

    // A timeout names the tag, the vertical and the subpage it was waiting on: run 37474747245 (arm64
    // only, Release) reported nothing but "Condition still not satisfied after 60000 ms".
    private fun waitFor(tag: String) = try {
        compose.waitUntil(60_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("no node tagged '$tag' within 60 s (vertical=${state.vertical})", e)
    }

    @Test fun everyVerticalAndSubpageIsReachableAndComposes() {
        launch()
        val cfg = Decl.config
        for (v in cfg.verticals) {
            openVertical(v.id)
            assertEquals(v.id, state.vertical)
            // A vertical with one subpage draws no sub-nav (the mockup's Search, Groceries, Things).
            // (Things' one subpage shares its id with Web Search's Things pill, which is always there.)
            if (v.subpages.first() !in cfg.verticals.map { it.id })
                assertEquals(v.subpages.size > 1, compose.onAllNodesWithTag(Tags.subpage(v.subpages.first())).fetchSemanticsNodes().isNotEmpty())
            if (v.subpages.size > 1) compose.onNodeWithTag(Tags.subpage(v.subpages.last())).performClick()
            for (sub in v.subpages) {
                compose.runOnIdle { state.showSubpage(v, sub) }
                // Bounded wait, not an instant check: a heavy page (the payslip runs the whole PAP
                // while composing) was not yet composed on one runner (run 37036021906) and was on
                // the other (run 37403502266: arm64 timed out at 10 s while the same step passed on x86_64, with
                // PIT running beside it). A page that never appears still fails here, after 60 s.
                waitFor(Tags.page(cfg.subpage(sub)!!.kind))
                assertEquals(sub, state.subpageOf(v))
            }
        }
    }

    @Test fun jobsListingShowsWhatTheSourceReturnedAndWhyTheOthersDidNot() {
        launch()
        openVertical("jobs")
        waitFor(Tags.card("ba-jobsuche:r-1"))
        compose.onNodeWithTag(Tags.card("ba-jobsuche:r-1")).assertTextContains("Kotlin Developer", substring = true)
        waitFor(Tags.source("arbeitnow"))
        compose.onNodeWithTag(Tags.source("arbeitnow")).assertTextContains("failed", substring = true)
        assertTrue(synchronized(http.asked) { http.asked.any { "arbeitsagentur" in it } })
    }

    @Test fun payslipComputesOnScreen() {
        launch()
        openVertical("jobs")
        compose.runOnIdle { state.showSubpage(state.v(), "calculators") }
        waitFor(Tags.output("payslip", "net"))
        // 5,000 € gross, class I, childless, 2.9 % extra rate: PayslipTest's hand-checked net.
        compose.onNodeWithTag(Tags.output("payslip", "net")).assertTextContains("3,130.09", substring = true)
        compose.onNodeWithTag(Tags.output("payslip", "company_cost")).assertTextContains("6,065.00", substring = true)
    }

    @Test fun chatWithoutATokenSaysWhyAndSendsNothing() {
        launch()
        openVertical("search")
        waitFor(Tags.CHAT_INPUT)
        compose.onNodeWithTag(Tags.CHAT_INPUT).performTextInput("What is the Grundfreibetrag?")
        compose.onNodeWithTag(Tags.CHAT_SEND).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("no token in this test", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertFalse("nothing is posted without a token", synchronized(http.asked) { http.asked.any { it == Decl.config.ai.chatUrl } })
        assertEquals("What is the Grundfreibetrag?", services.sessions.all().single().messages.single().content)
    }

    @Test fun themeToggleFlipsAndIsRemembered() {
        launch()
        val before = state.dark
        compose.onNodeWithTag(Tags.PROFILE).performClick()
        waitFor(Tags.THEME)
        compose.onNodeWithTag(Tags.THEME).performClick()
        compose.runOnIdle { assertEquals(!before, state.dark) }
        assertEquals(!before, services.prefs.dark)
    }

    @Test fun theMenuOpensEveryCategoryAndSavedItems() {
        launch()
        compose.onNodeWithTag(Tags.MENU).performClick()
        waitFor(Tags.drawer("jobs"))
        compose.onNodeWithTag(Tags.drawer("jobs")).performClick()
        compose.runOnIdle { assertEquals("jobs", state.vertical); assertEquals(null, state.menu) }
        compose.onNodeWithTag(Tags.MENU).performClick()
        waitFor(Tags.drawer("saved"))
        compose.onNodeWithTag(Tags.drawer("saved")).performClick()
        waitFor(Tags.SAVED)
        compose.onNodeWithTag(Tags.ISLAND).assertTextContains("Saved Items", substring = true)
        openVertical("house")
        compose.runOnIdle { assertFalse(state.saved); assertEquals("house", state.vertical) }
    }

    @Test fun extensiveFiltersApplyTheDeclaredOptions() {
        launch()
        openVertical("jobs")
        waitFor(Tags.MORE_FILTERS)
        compose.onNodeWithTag(Tags.MORE_FILTERS).performClick()
        waitFor(Tags.APPLY_FILTERS)
        compose.onNodeWithTag(Tags.option("remote")).performClick()
        compose.onNodeWithTag(Tags.APPLY_FILTERS).performClick()
        compose.runOnIdle {
            assertEquals(setOf("remote"), state.filtersOf("jobs").chips)
            assertEquals(null, state.menu)
        }
    }

    @Test fun houseAnalysisShowsTheSeriesAndSaysWhatIsMissing() {
        launch()
        openVertical("house")
        compose.runOnIdle { state.showSubpage(state.v(), "analysis") }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("4.01 %", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("+0.30 pp vs 2025-08", substring = true).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        // Eurostat is down in this test: its two series read "no source", never a number.
        assertEquals(2, compose.onAllNodesWithText("no source").fetchSemanticsNodes().size)
    }

    /**
     * #803 the owner's renderUnifiedSearchChat: the Search tab is ONE page holding both the engine
     * boxes and the AI chat, not a web page and a chat page. Which vertical that is comes from the
     * declaration (the one whose subpage is of kind `assistant`).
     */
    @Test fun theSearchTabIsOnePageWithTheEnginesAndTheChat() {
        launch()
        val cfg = Decl.config
        val app = RuntimeEnvironment.getApplication()
        val search = cfg.verticals.single { v -> v.subpages.any { cfg.subpage(it)?.kind == "assistant" } }
        openVertical(search.id)
        waitFor(Tags.page("assistant"))
        // One page: the assistant's is the only page composed, and no sub-nav offers another.
        for (kind in cfg.subpages.map { it.kind }.toSet())
            assertEquals("pages of kind $kind", if (kind == "assistant") 1 else 0, compose.onAllNodesWithTag(Tags.page(kind)).fetchSemanticsNodes().size)
        for (sub in cfg.subpages)
            assertTrue("no sub-nav tab ${sub.id}", compose.onAllNodesWithTag(Tags.subpage(sub.id)).fetchSemanticsNodes().isEmpty())

        // Both parts at once, inside that one page, in the mockup's order of appearance.
        val onPage = hasAnyAncestor(hasTestTag(Tags.page("assistant")))
        val hello = app.getString(R.string.assistant_hello)
        val top = { m: SemanticsMatcher -> compose.onNode(m and onPage).fetchSemanticsNode().boundsInRoot.top }
        val greeting = top(hasText(hello))
        val engines = cfg.engines.map { top(hasTestTag(Tags.engine(it.id))) }
        assertTrue("Sessions and the model sit above the greeting", top(hasTestTag(Tags.SESSIONS)) < greeting && top(hasTestTag(Tags.MODEL)) < greeting)
        assertTrue("the greeting sits above the engine boxes", greeting < engines.first())
        assertEquals("the engine boxes run in declared order", engines.sorted(), engines)
        assertTrue("the chat input sits below the last engine box", top(hasTestTag(Tags.CHAT_INPUT)) > engines.last())
        compose.onNode(hasTestTag(Tags.NEW_CHAT) and onPage).assertExists()
        compose.onNode(hasTestTag(Tags.CHAT_SEND) and onPage).assertExists()

        // An engine box opens that engine's own results in cloud-browser.
        val shadow = shadowOf(app)
        while (shadow.nextStartedActivity != null) Unit
        val first = cfg.engines.first()
        compose.onNodeWithTag(Tags.engine(first.id)).performTextInput("kotlin jobs")
        compose.onNodeWithTag(Tags.engine(first.id)).performImeAction()
        val opened = shadow.nextStartedActivity
        assertEquals(Templates.fill(first.url, "kotlin jobs", cfg.city(state.city)), opened?.dataString)
        assertEquals(BuildConfig.BROWSER_PACKAGE, opened?.`package`)

        // Sending a message hides the initial view; the chat goes on in the same page.
        compose.onNodeWithTag(Tags.CHAT_INPUT).performTextInput("Hello")
        compose.onNodeWithTag(Tags.CHAT_SEND).performClick()
        compose.waitUntil(10_000) { !state.chat.sending && compose.onAllNodes(hasText("Hello") and onPage).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("the greeting is gone", compose.onAllNodesWithText(hello).fetchSemanticsNodes().isEmpty())
        for (e in cfg.engines)
            assertTrue("engine box ${e.id} is gone", compose.onAllNodesWithTag(Tags.engine(e.id)).fetchSemanticsNodes().isEmpty())
        assertEquals(1, compose.onAllNodesWithTag(Tags.page("assistant")).fetchSemanticsNodes().size)
        compose.onNode(hasTestTag(Tags.CHAT_INPUT) and onPage).assertExists()

        // A new chat brings the initial view back; Sessions opens its menu.
        compose.onNodeWithTag(Tags.NEW_CHAT).performClick()
        for (e in cfg.engines) waitFor(Tags.engine(e.id))
        compose.onNodeWithTag(Tags.SESSIONS).performClick()
        compose.runOnIdle { assertEquals(com.diegonmarcos.cloudsearch.ui.Menu.SESSIONS, state.menu) }
    }

    @Test fun debugRoutesAnswerFromTheSameEngine() {
        val v = SearchDebugApi.verticals(services)
        assertEquals(Decl.config.verticals.size, v.getJSONArray("verticals").length())
        val calc = SearchDebugApi.calc(services, mapOf("name" to "payslip", "gross" to "5000"))
        assertEquals(3130.09, calc.getJSONObject("outputs").getDouble("net"), 0.001)
        assertFalse(SearchDebugApi.calc(services, mapOf("name" to "nope")).getBoolean("ok"))
        val q = SearchDebugApi.query(services, mapOf("v" to "jobs", "q" to "kotlin"))
        assertTrue(q.getBoolean("ok"))
        assertEquals("Kotlin Developer", q.getJSONArray("listings").getJSONObject(0).getString("title"))
        assertFalse(SearchDebugApi.query(services, mapOf("v" to "nope")).getBoolean("ok"))
        // #797 the Analysis and Feed pages, screen-locked: offline here, so every source says why.
        val house = SearchDebugApi.analysis(services, mapOf("v" to "house"))
        assertEquals("market", house.getString("kind"))
        assertEquals(Decl.config.vertical("house")!!.series.size, house.getJSONArray("sources").length())
        val houseSources = (0 until house.getJSONArray("sources").length()).map { house.getJSONArray("sources").getJSONObject(it) }.associateBy { it.getString("id") }
        assertEquals("ok", houseSources.getValue("bbk-mortgage-rate").getString("state"))
        assertEquals("error", houseSources.getValue("eurostat-house-prices").getString("state"))
        assertEquals("jobs", SearchDebugApi.analysis(services, mapOf("v" to "jobs", "q" to "kotlin")).getString("kind"))
        assertFalse(SearchDebugApi.analysis(services, mapOf("v" to "nope")).getBoolean("ok"))
        val feed = SearchDebugApi.feed(services, mapOf("v" to "jobs"))
        assertTrue(feed.getBoolean("ok"))
        assertEquals("error", feed.getJSONArray("sources").getJSONObject(0).getString("state"))
        assertFalse(SearchDebugApi.feed(services, mapOf("v" to "search")).getBoolean("ok"))
    }

    // ── #903 Things ──────────────────────────────────────────────────────────────────────────────
    private fun openThings(item: String) {
        launch()
        openVertical("things")
        waitFor(Tags.SEARCH_BOX)
        compose.onNodeWithTag(Tags.SEARCH_BOX).performTextInput(item)
        compose.onNodeWithTag(Tags.SEARCH_BOX).performImeAction()
    }

    @Test fun thingsComparesStoresWithTheirRealPriceOrWhyNone() {
        services.prefs.useLocation = false
        services.prefs.city = "munich" // the declared city the fake stores sit in
        state.city = "munich"
        com.diegonmarcos.cloudsearch.data.FleetBearer.reader = { "tok-1" }
        openThings("Bohrmaschine")
        waitFor(Tags.thingsRow("node/1"))
        // the chain store carries the price its own site published, with where it comes from; the other says why not
        compose.onNodeWithTag(Tags.thingsRow("node/1")).assertTextContains("€24.49", substring = true)
        compose.onNodeWithTag(Tags.thingsRow("node/1")).assertTextContains("online", substring = true)
        compose.onNodeWithTag(Tags.thingsRow("node/2")).assertTextContains("no price", substring = true)
        compose.onNodeWithTag(Tags.thingsRow("node/2")).assertTextContains("no price source for this chain", substring = true)
        val rows = compose.onAllNodes(hasTestTag(Tags.THINGS_TABLE)).fetchSemanticsNodes()
        assertEquals(1, rows.size)
        val top = { t: String -> compose.onNodeWithTag(t).fetchSemanticsNode().boundsInRoot.top }
        assertTrue("cheapest first, the unpriced below", top(Tags.thingsRow("node/1")) < top(Tags.thingsRow("node/2")))
        compose.onNodeWithTag(Tags.THINGS_STORES).assertTextContains("DIY and tools", substring = true)
        // the default city and the declared 20 km radius, said on the page
        compose.onNodeWithTag(Tags.THINGS_AREA).assertTextContains("20 km", substring = true)
        synchronized(http.asked) {
            val i = http.asked.indexOfFirst { "/scrappers/prices" in it }
            assertTrue(http.asked[i], "q=Bohrmaschine" in http.asked[i] && "brands=kleiner%20baumarkt%2Cobi" in http.asked[i])
            assertEquals(mapOf("Authorization" to "Bearer tok-1"), http.headersAsked[i])
        }
    }

    @Test fun thingsWithoutTheFleetSignInShowsNoStorePriceAndSaysSo() {
        services.prefs.useLocation = false
        services.prefs.city = "munich"
        state.city = "munich"
        com.diegonmarcos.cloudsearch.data.FleetBearer.reader = { "" }
        openThings("Bohrmaschine")
        waitFor(Tags.thingsRow("node/1"))
        compose.onNodeWithTag(Tags.thingsRow("node/1")).assertTextContains("no price", substring = true)
        compose.onNodeWithTag(Tags.thingsRow("node/1")).assertTextContains("fleet sign-in", substring = true)
        assertTrue(synchronized(http.asked) { http.asked.none { "/scrappers/prices" in it } })
    }

    @Test fun thingsCentresOnTheCoarseFixWhenAllowed() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        com.diegonmarcos.cloudsearch.data.Locator.reader = { com.diegonmarcos.cloudsearch.data.Locator.Fix(48.1371, 11.5753) }
        openThings("Bohrmaschine")
        waitFor(Tags.thingsRow("node/1"))
        compose.onNodeWithTag(Tags.THINGS_AREA).assertTextContains("Near you", substring = true)
        // the centre leaves the phone rounded to ~1 km
        assertTrue(synchronized(http.asked) { http.asked.any { "overpass" in it && "48.14000%2C11.58000" in it } })
        assertTrue(synchronized(http.asked) { http.asked.none { "48.1371" in it || "11.5753" in it } })
    }

    @Test fun thingsFallsBackToTheTypedCityAndNamesWhy() {
        services.prefs.thingsCity = "München"
        openThings("Bohrmaschine")
        waitFor(Tags.thingsRow("node/1"))
        compose.onNodeWithTag(Tags.THINGS_AREA).assertTextContains("München", substring = true)
        compose.onNodeWithTag(Tags.THINGS_AREA).assertTextContains("typed city", substring = true)
        compose.onNodeWithTag(Tags.THINGS_AREA).assertTextContains("no location permission", substring = true)
        assertTrue(synchronized(http.asked) { http.asked.any { "nominatim" in it && "K%C3%B6ln" !in it && "M%C3%BCnchen" in it } })
    }

    @Test fun thingsSettingsKeepTheCityAndClampTheRadius() {
        launch()
        compose.onNodeWithTag(Tags.PROFILE).performClick()
        waitFor(Tags.THINGS_CITY)
        compose.onNodeWithTag(Tags.THINGS_CITY).performTextInput(" Köln ")
        compose.onNodeWithTag(Tags.THINGS_RADIUS).performTextInput("500")
        val before = state.areaRev
        compose.onNodeWithTag(Tags.THINGS_APPLY).performClick()
        compose.runOnIdle {
            assertEquals("Köln", services.prefs.thingsCity)
            assertEquals(Decl.config.things!!.maxRadiusKm, services.prefs.radiusKm)
            assertEquals(before + 1, state.areaRev)
        }
        compose.onNodeWithTag(Tags.option("radius_5")).performClick()
        compose.runOnIdle { assertEquals(5, services.prefs.radiusKm) }
        compose.onNodeWithTag(Tags.THINGS_USE_LOCATION).performClick()
        compose.runOnIdle { assertFalse(services.prefs.useLocation) }
    }

    @Test fun theDebugRouteAnswersTheThingsComparison() {
        com.diegonmarcos.cloudsearch.data.FleetBearer.reader = { "tok-1" }
        val j = SearchDebugApi.things(services, mapOf("q" to "Bohrmaschine", "lat" to "48.14", "lon" to "11.58", "radius" to "10"))
        assertTrue(j.getBoolean("ok"))
        assertEquals(10, j.getJSONObject("area").getInt("radius_km"))
        assertEquals(2, j.getJSONArray("rows").length())
        assertEquals(24.49, j.getJSONArray("rows").getJSONObject(0).getDouble("price"), 1e-9)
        assertTrue(j.getJSONArray("rows").getJSONObject(1).isNull("price"))
        assertTrue(SearchDebugApi.things(services, mapOf("q" to "x", "city" to "München")).getBoolean("ok"))
    }

    // ── #913 the island, Agents and Reports ──────────────────────────────────────────────────────
    @Test fun theIslandHoldsTheFiveSectionsAndEachOpens() {
        launch()
        assertEquals(listOf("web", "cloud", "chat", "agents", "reports"), NAV_IDS)
        for (id in NAV_IDS) {
            compose.onNodeWithTag(Tags.nav(id)).performClick()
            compose.waitForIdle()
            assertEquals(id, state.section)
        }
        // Web Search's strip is its verticals, Chat is the assistant.
        compose.onNodeWithTag(Tags.nav("web")).performClick()
        waitFor(Tags.WEB_STRIP)
        for (v in listOf("house", "jobs", "groceries", "things")) compose.onNodeWithTag(Tags.subpage(v)).assertExists()
        compose.onNodeWithTag(Tags.nav("chat")).performClick()
        waitFor(Tags.page("assistant"))
        assertEquals(state.chatVertical, state.vertical)
    }

    private val NAV_IDS get() = com.diegonmarcos.cloudsearch.ui.NAV.bottomNav

    private class FakeMail : com.diegonmarcos.cloudsearch.core.agents.MailSource {
        override fun messages(from: String, subject: String, sinceMs: Long, limit: Int) = listOf(
            com.diegonmarcos.cloudsearch.core.agents.MailHeader("m1", "acc", "Neue Angebote", "WG", "info@wg-gesucht.de", "2026-10-07T10:00:00Z", 1L, false),
        )
        override fun body(accountId: String, id: String) = com.diegonmarcos.cloudsearch.core.agents.MailBody(
            "", """<a href="https://www.wg-gesucht.de/wg-zimmer-in-Berlin-Mitte.11223344.html?x=1">Zimmer</a>""", false)
    }

    private class FakePages : com.diegonmarcos.cloudsearch.core.agents.PageSource {
        override fun text(url: String, maxChars: Int) = com.diegonmarcos.cloudsearch.core.agents.PageText(true, url, "Helles Zimmer", "Ruhige WG sucht Mitbewohner.", false, "")
    }

    private fun installAgents(): com.diegonmarcos.cloudsearch.data.AgentService {
        val a = com.diegonmarcos.cloudsearch.data.AgentService(
            RuntimeEnvironment.getApplication(), http, services.cfg, services.cfg.agents!!, services.models, "p.mail", "p.browser",
            mailOverride = FakeMail(), pagesOverride = FakePages(),
            llmOverride = object : com.diegonmarcos.cloudsearch.core.agents.Llm {
                override fun complete(req: com.diegonmarcos.cloudsearch.core.agents.LlmRequest) = com.diegonmarcos.cloudsearch.core.agents.LlmReply("Ich koche gern.", null, 10, 5, 0.0001)
            },
        )
        a.prefs.setProfile("name", "Ada Test"); a.prefs.setProfile("about", "Ich arbeite als Entwicklerin.")
        services.installAgents(a)
        return a
    }

    @Test fun anAgentRunDraftsForReviewAndNothingIsSent() {
        val a = installAgents()
        launch()
        compose.onNodeWithTag(Tags.nav("agents")).performClick()
        waitFor(com.diegonmarcos.cloudsearch.ui.AgentTags.run("house_search"))
        compose.onNodeWithTag(com.diegonmarcos.cloudsearch.ui.AgentTags.run("house_search")).performClick()
        waitFor(com.diegonmarcos.cloudsearch.ui.AgentTags.copy("11223344"))
        compose.onNodeWithTag(com.diegonmarcos.cloudsearch.ui.AgentTags.report("11223344")).assertTextContains("Ich koche gern.", substring = true)
        // The review buttons: copy puts the message on the clipboard, open hands the listing to Cloud Browser.
        compose.onNodeWithTag(com.diegonmarcos.cloudsearch.ui.AgentTags.copy("11223344")).performClick()
        val app = RuntimeEnvironment.getApplication()
        val clip = (app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
        assertTrue(clip!!.getItemAt(0).text.toString().contains("Ada Test"))
        val shadow = shadowOf(app)
        while (shadow.nextStartedActivity != null) Unit
        compose.onNodeWithTag(com.diegonmarcos.cloudsearch.ui.AgentTags.open("11223344")).performClick()
        val opened = shadow.nextStartedActivity!!
        assertEquals(Decl.config.agents!!.browser.openAction, opened.action)
        assertEquals("https://www.wg-gesucht.de/wg-zimmer-in-Berlin-Mitte.11223344.html", opened.getStringExtra("url"))
        assertEquals("House search", opened.getStringExtra("group"))
        // Nothing was posted anywhere, and nothing but a view of the page was started.
        assertTrue(synchronized(http.asked) { http.asked.none { it == Decl.config.ai.chatUrl } })
        assertEquals(1, a.runs.all().size)
        assertEquals(1, a.reports.newestFirst().single().items.size)
        // Reports lists it, newest first, and opens the detail.
        compose.onNodeWithTag(Tags.nav("reports")).performClick()
        waitFor(com.diegonmarcos.cloudsearch.ui.AgentTags.report("row_" + a.reports.newestFirst().single().id))
        compose.onNodeWithTag(com.diegonmarcos.cloudsearch.ui.AgentTags.report("row_" + a.reports.newestFirst().single().id)).performClick()
        waitFor(com.diegonmarcos.cloudsearch.ui.AgentTags.copy("11223344"))
    }

    @Test fun agentsSubSectionsAreReachable() {
        installAgents()
        launch()
        compose.onNodeWithTag(Tags.nav("agents")).performClick()
        for (p in listOf("runs", "templates", "settings", "agents")) {
            compose.onNodeWithTag(Tags.subpage(p)).performClick()
            waitFor(Tags.page("agents_$p"))
        }
    }

    @Test fun cloudSearchSectionHasItsThreePages() {
        installAgents()
        launch()
        compose.onNodeWithTag(Tags.nav("cloud")).performClick()
        for (p in listOf("apps", "messages", "code")) {
            compose.onNodeWithTag(Tags.subpage(p)).performClick()
            waitFor(Tags.page("cloud_$p"))
        }
    }
}
