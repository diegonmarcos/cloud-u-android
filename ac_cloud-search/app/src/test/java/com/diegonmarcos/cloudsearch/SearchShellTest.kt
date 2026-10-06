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
        override fun get(url: String, headers: Map<String, String>, timeoutMs: Int): Http.Response {
            synchronized(asked) { asked += url }
            return when {
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

    @Before fun offline() {
        services = Services(RuntimeEnvironment.getApplication(), http)
        Services.install(services)
        state = SearchState(services)
        savedReader = Account.reader
        Account.reader = { _, _ -> Account.Token(null, "no token in this test") }
    }

    @After fun online() {
        Account.reader = savedReader
        Services.install(null)
    }

    private fun launch() = compose.setContent { SearchShell(state) }

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
            compose.onNodeWithTag(Tags.nav(v.id)).performClick()
            compose.waitForIdle()
            assertEquals(v.id, state.vertical)
            // A vertical with one subpage draws no sub-nav (the mockup's Search, Groceries, Things).
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
        compose.onNodeWithTag(Tags.nav("jobs")).performClick()
        waitFor(Tags.card("ba-jobsuche:r-1"))
        compose.onNodeWithTag(Tags.card("ba-jobsuche:r-1")).assertTextContains("Kotlin Developer", substring = true)
        waitFor(Tags.source("arbeitnow"))
        compose.onNodeWithTag(Tags.source("arbeitnow")).assertTextContains("failed", substring = true)
        assertTrue(synchronized(http.asked) { http.asked.any { "arbeitsagentur" in it } })
    }

    @Test fun payslipComputesOnScreen() {
        launch()
        compose.onNodeWithTag(Tags.nav("jobs")).performClick()
        compose.runOnIdle { state.showSubpage(state.v(), "calculators") }
        waitFor(Tags.output("payslip", "net"))
        // 5,000 € gross, class I, childless, 2.9 % extra rate: PayslipTest's hand-checked net.
        compose.onNodeWithTag(Tags.output("payslip", "net")).assertTextContains("3,130.09", substring = true)
        compose.onNodeWithTag(Tags.output("payslip", "company_cost")).assertTextContains("6,065.00", substring = true)
    }

    @Test fun chatWithoutATokenSaysWhyAndSendsNothing() {
        launch()
        compose.onNodeWithTag(Tags.nav("search")).performClick()
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
        compose.onNodeWithTag(Tags.nav("house")).performClick()
        compose.runOnIdle { assertFalse(state.saved); assertEquals("house", state.vertical) }
    }

    @Test fun extensiveFiltersApplyTheDeclaredOptions() {
        launch()
        compose.onNodeWithTag(Tags.nav("jobs")).performClick()
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
        compose.onNodeWithTag(Tags.nav("house")).performClick()
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
        compose.onNodeWithTag(Tags.nav(search.id)).performClick()
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
}
