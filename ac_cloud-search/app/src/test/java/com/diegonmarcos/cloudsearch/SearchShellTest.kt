package com.diegonmarcos.cloudsearch

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.diegonmarcos.cloudsearch.core.Http
import com.diegonmarcos.cloudsearch.data.Account
import com.diegonmarcos.cloudsearch.data.Services
import com.diegonmarcos.cloudsearch.debugapi.SearchDebugApi
import com.diegonmarcos.cloudsearch.ui.SearchShell
import com.diegonmarcos.cloudsearch.ui.SearchState
import com.diegonmarcos.cloudsearch.ui.Tags
import com.diegonmarcos.superapp.bottomnav.BottomNavTags
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
 * fake network, every declared vertical reached through the bottom-nav island and every one of its
 * declared subpages composed. Every id comes from the declaration, none is restated here.
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
            return if ("arbeitsagentur" in url) Http.Response(200,
                """{"maxErgebnisse":1,"ergebnisliste":[{"referenznummer":"r-1","stellenangebotsTitel":"Kotlin Developer","firma":"ACME","arbeitszeitVollzeit":true}]}""")
            else Http.Response(503, "")
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

    private fun waitFor(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test fun everyVerticalAndSubpageIsReachableAndComposes() {
        launch()
        val cfg = Decl.config
        for (v in cfg.verticals) {
            compose.onNodeWithTag(BottomNavTags.item(v.id)).performClick()
            compose.waitForIdle()
            assertEquals(v.id, state.vertical)
            if (v.subpages.size > 1) compose.onNodeWithTag(Tags.subpage(v.subpages.first())).performClick()
            for (sub in v.subpages) {
                compose.runOnIdle { state.showSubpage(v, sub) }
                // Bounded wait, not an instant check: a heavy page (the payslip runs the whole PAP
                // while composing) was not yet composed on one runner (run 37036021906) and was on
                // the other. A page that never appears still fails here, after 10 s.
                waitFor(Tags.page(cfg.subpage(sub)!!.kind))
                assertEquals(sub, state.subpageOf(v))
            }
        }
    }

    @Test fun jobsListingShowsWhatTheSourceReturnedAndWhyTheOthersDidNot() {
        launch()
        compose.onNodeWithTag(BottomNavTags.item("jobs")).performClick()
        waitFor(Tags.card("ba-jobsuche:r-1"))
        compose.onNodeWithTag(Tags.card("ba-jobsuche:r-1")).assertTextContains("Kotlin Developer", substring = true)
        waitFor(Tags.source("arbeitnow"))
        compose.onNodeWithTag(Tags.source("arbeitnow")).assertTextContains("failed", substring = true)
        assertTrue(synchronized(http.asked) { http.asked.any { "arbeitsagentur" in it } })
    }

    @Test fun payslipComputesOnScreen() {
        launch()
        compose.onNodeWithTag(BottomNavTags.item("jobs")).performClick()
        compose.runOnIdle { state.showSubpage(state.v(), "calculators") }
        waitFor(Tags.output("payslip", "net"))
        // 5,000 € gross, class I, childless, 2.9 % extra rate: PayslipTest's hand-checked net.
        compose.onNodeWithTag(Tags.output("payslip", "net")).assertTextContains("3,130.09", substring = true)
        compose.onNodeWithTag(Tags.output("payslip", "company_cost")).assertTextContains("6,065.00", substring = true)
    }

    @Test fun chatWithoutATokenSaysWhyAndSendsNothing() {
        launch()
        compose.onNodeWithTag(BottomNavTags.item("search")).performClick()
        compose.runOnIdle { state.showSubpage(state.v(), "chat") }
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
    }
}
