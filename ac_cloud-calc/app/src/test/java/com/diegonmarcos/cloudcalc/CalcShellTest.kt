package com.diegonmarcos.cloudcalc

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.diegonmarcos.cloudcalc.engine.CalcApi
import com.diegonmarcos.cloudcalc.ui.CalcShell
import com.diegonmarcos.cloudcalc.ui.CalcState
import com.diegonmarcos.cloudcalc.ui.CalcTags
import com.diegonmarcos.cloudcalc.ui.CalcTheme
import com.diegonmarcos.superapp.bottomnav.BottomNavTags
import com.diegonmarcos.superapp.bottomnav.PageTabsTags
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
 * The UI smoke test PER MODE: the real shell composed under Robolectric (the DriveShellTest
 * precedent) against a fake engine, reaching every declared mode through the bottom nav and its
 * chip strip. Every id comes from the declaration, none is restated here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalcShellTest {

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

    /** Answers like the engine does, and records what it was asked. */
    private class FakeEngine : CalcApi {
        val evals = mutableListOf<String>()
        override fun info() = """{"ok":true,"version":"fake"}"""
        override fun eval(expr: String, options: String): String {
            synchronized(evals) { evals += expr }
            return JSONObject().put("ok", true).put("result", if (expr == "2+2") "4" else "42").put("messages", org.json.JSONArray()).toString()
        }
        override fun plot(expr: String, xmin: Double, xmax: Double, steps: Int) = """{"ok":true,"x":[0,1,2],"y":[0,1,4]}"""
        override fun complete(prefix: String, max: Int) = """[{"name":"sqrt","title":"Square Root","kind":"function","category":"c"}]"""
        override fun items(kind: String, category: String, max: Int) =
            """[{"name":"m","title":"Meter","kind":"unit","category":"$category"},{"name":"ft","title":"Foot","kind":"unit","category":"$category"}]"""
        var time = 1783296000L
        var fetchedTime = 1783296000L
        var fetchOk = true
        var fetches = 0
        override fun ratesInfo() = """{"sources":[],"time":$time}"""
        override fun fetchRates(): String {
            fetches++
            if (fetchOk) time = fetchedTime
            return if (fetchOk) """{"ok":true,"fetched":[],"failed":[],"time":$time}""" else """{"ok":false,"error":"offline","fetched":[],"failed":[],"time":$time}"""
        }
    }

    private val engine = FakeEngine()
    private val state = CalcState(null)

    // #770 the Jev screens compose in the smoke test: no real network and no real Account binder.
    private lateinit var savedHttp: com.diegonmarcos.superapp.decisions.Http
    private lateinit var savedAccount: (android.content.Context, String) -> Pair<String?, String>

    @org.junit.Before fun offline() {
        com.diegonmarcos.cloudcalc.Fx.lastAttemptMs = 0L
        savedHttp = com.diegonmarcos.cloudcalc.decide.JevStore.http
        savedAccount = com.diegonmarcos.cloudcalc.decide.JevStore.account
        com.diegonmarcos.cloudcalc.decide.JevStore.http = object : com.diegonmarcos.superapp.decisions.Http {
            override fun send(url: String, token: String?, body: String?, timeoutMs: Int) = com.diegonmarcos.superapp.decisions.Http.Response(503, "{}")
        }
        com.diegonmarcos.cloudcalc.decide.JevStore.account = { _, _ -> null to "none" }
    }

    @org.junit.After fun online() {
        com.diegonmarcos.cloudcalc.decide.JevStore.http = savedHttp
        com.diegonmarcos.cloudcalc.decide.JevStore.account = savedAccount
    }

    private fun launch() = compose.setContent { CalcTheme { CalcShell(engine, state) } }

    @Test fun `every declared mode is reachable from the nav and composes`() {
        launch()
        Declarations.tabs.forEach { tab ->
            // #868 a page is in the top strip only while its section (a bottom-nav item) is selected.
            compose.onNodeWithTag(BottomNavTags.item(tab.section)).performClick()
            compose.waitForIdle()
            // The pill is declared and composed; a strip that scrolls (five pages on a narrow
            // screen) may park it off-screen, where a click has no pixel to land on, so the page
            // is selected through the state the pills write.
            compose.onNodeWithTag(PageTabsTags.tab(tab.id)).assertExists()
            compose.runOnIdle { state.tab = tab.id }
            compose.waitForIdle()
            compose.onNodeWithTag(CalcTags.tab(tab.id)).assertExists()
            val modes = Declarations.modesOf(tab.id)
            if (modes.size > 1) {
                // The sub-strip scrolls: a pill scrolled out of view may not be composed, so the
                // first is clicked and the rest are selected through the state the chips write.
                compose.onNodeWithTag(PageTabsTags.tab(modes.first().id)).performClick()
            }
            modes.forEach { m ->
                compose.runOnIdle { state.modeByTab[tab.id] = m.id }
                compose.waitForIdle()
                compose.onNodeWithTag(CalcTags.mode(m.id)).assertExists()
            }
        }
    }

    @Test fun `each section shows only its own tabs and comes back to the last one`() {
        launch()
        Declarations.sections.forEach { sec ->
            compose.onNodeWithTag(BottomNavTags.item(sec.id)).performClick()
            compose.waitForIdle()
            assertEquals(sec.id, state.section)
            Declarations.tabs.forEach { t ->
                val shown = compose.onAllNodesWithTag(PageTabsTags.tab(t.id)).fetchSemanticsNodes().isNotEmpty()
                assertEquals("tab ${t.id} in section ${sec.id}", t.section == sec.id, shown)
            }
        }
        val two = Declarations.sections.first { Declarations.tabsOf(it.id).size > 1 }
        val second = Declarations.tabsOf(two.id)[1]
        compose.runOnIdle { state.showSection(two.id); state.tab = second.id }
        val other = Declarations.sections.first { it.id != two.id }
        compose.runOnIdle { state.showSection(other.id) }
        compose.runOnIdle { state.showSection(two.id) }
        assertEquals(second.id, state.tab)
    }

    @Test fun `keys type, the engine answers, and = keeps the result in history`() {
        launch()
        val mode = Declarations.modes.first { it.kind == "expression" }
        compose.runOnIdle { state.tab = mode.tab; state.modeByTab[mode.tab] = mode.id }
        listOf("2", "+", "2").forEach { compose.onNodeWithTag(CalcTags.key(it)).performClick() }
        compose.onNodeWithTag(CalcTags.INPUT).assertTextContains("2+2")
        // The live result is debounced per keystroke, so the first answer on screen can belong
        // to an intermediate input ("2" is answered with 42 by the fake): wait for THE answer.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(CalcTags.RESULT).fetchSemanticsNodes().isNotEmpty() &&
                runCatching { compose.onNodeWithTag(CalcTags.RESULT).assertTextContains("= 4") }.isSuccess
        }
        compose.onNodeWithTag(CalcTags.key("=")).performClick()
        compose.waitForIdle()
        val kept = state.history.first()
        assertEquals(Logic.Entry(mode.id, "2+2", "4").copy(ts = kept.ts), kept)
        assertTrue(kept.ts > 0)
        assertTrue(synchronized(engine.evals) { "2+2" in engine.evals })
    }

    @Test fun `the Alarms screen shows the stored alarm and its switch cancels the wakeup`() {
        val app = RuntimeEnvironment.getApplication()
        val id = com.diegonmarcos.cloudcalc.clock.ClockEngine.saveAlarm(app, com.diegonmarcos.cloudcalc.clock.Alarm(0, 6 * 60 + 45))
        assertTrue(com.diegonmarcos.cloudcalc.clock.ClockEngine.isScheduled(app, "alarm:$id"))
        launch()
        val alarms = Declarations.modes.first { it.kind == "alarms" }
        compose.runOnIdle { state.tab = alarms.tab; state.modeByTab[alarms.tab] = alarms.id }
        compose.waitForIdle()
        compose.onNodeWithText("06:45").assertExists()
        // Switching it off is the screen's own write: store, then AlarmManager.
        compose.onNode(isToggleable()).performClick()
        compose.waitForIdle()
        assertFalse(com.diegonmarcos.cloudcalc.clock.ClockEngine.load(app).alarms.single().enabled)
        assertFalse(com.diegonmarcos.cloudcalc.clock.ClockEngine.isScheduled(app, "alarm:$id"))
    }

    @Test fun `the keypad does not move while a result appears, changes and goes`() {
        launch()
        val mode = Declarations.modes.first { it.kind == "expression" }
        compose.runOnIdle { state.tab = mode.tab; state.modeByTab[mode.tab] = mode.id }
        compose.waitForIdle()
        fun top() = compose.onNodeWithTag(CalcTags.key("=")).getBoundsInRoot().top
        val rest = top()
        val display = compose.onNodeWithTag(CalcTags.DISPLAY).getBoundsInRoot().let { it.bottom - it.top }
        listOf("2", "+", "2").forEach { compose.onNodeWithTag(CalcTags.key(it)).performClick(); compose.waitForIdle(); assertEquals(rest, top()) }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(CalcTags.RESULT).fetchSemanticsNodes().isNotEmpty() &&
                runCatching { compose.onNodeWithTag(CalcTags.RESULT).assertTextContains("= 4") }.isSuccess
        }
        assertEquals(rest, top())
        assertEquals(display, compose.onNodeWithTag(CalcTags.DISPLAY).getBoundsInRoot().let { it.bottom - it.top })
        assertEquals(0, compose.onAllNodesWithTag(CalcTags.ASK_TOGGLE).fetchSemanticsNodes().size)
    }

    @Test fun `history keeps each press with its time, reuses expression or result, deletes one, clears all`() {
        val mode = Declarations.modes.first { it.kind == "expression" }
        var t = 1_000L
        val s = CalcState(null) { t++ }
        s.remember(Logic.Entry(mode.id, "1+1", "2"), 10)
        s.remember(Logic.Entry(mode.id, "1+1", "2"), 10)
        s.remember(Logic.Entry(mode.id, "6*7", "42"), 10)
        assertEquals(listOf(1_002L, 1_001L, 1_000L), s.history.map { it.ts })
        s.deleteHistory(1)
        assertEquals(listOf("6*7", "1+1"), s.history.map { it.expr })
        s.deleteHistory(9)
        assertEquals(2, s.history.size)
        s.clearHistory()
        assertTrue(s.history.isEmpty())
        // The screen: a tap on the expression and a tap on the result each reuse theirs.
        state.remember(Logic.Entry(mode.id, "6*7", "42"), 10)
        launch()
        val history = Declarations.modes.first { it.kind == "history" }
        compose.runOnIdle { state.tab = history.tab; state.modeByTab[history.tab] = history.id }
        compose.waitForIdle()
        compose.onNodeWithTag(CalcTags.historyExpr(0)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(CalcTags.INPUT).assertTextContains("6*7")
        compose.runOnIdle { state.tab = history.tab }
        compose.waitForIdle()
        compose.onNodeWithTag(CalcTags.historyDelete(0)).performClick()
        compose.waitForIdle()
        assertTrue(state.history.isEmpty())
    }

    @Test fun `the Currency converter shows its rates date, swaps, and = keeps the conversion in history`() {
        launch()
        val cur = Declarations.modes.first { it.id == "currency" }
        compose.runOnIdle { state.tab = cur.tab; state.modeByTab[cur.tab] = cur.id }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(CalcTags.CONVERT_EQ).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Rates as of", substring = true).assertExists()
        compose.onNodeWithTag(CalcTags.CONVERT_SWAP).performClick()
        compose.onNodeWithTag(CalcTags.CONVERT_EQ).performClick()
        compose.waitForIdle()
        assertEquals(cur.id, state.history.first().mode)
        assertTrue(state.history.first().ts > 0)
    }

    private fun day(d: java.time.LocalDate) = d.atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond()
    private fun openCurrency() {
        launch()
        val cur = Declarations.modes.first { it.id == "currency" }
        compose.runOnIdle { state.tab = cur.tab; state.modeByTab[cur.tab] = cur.id }
    }

    @Test fun `opening Currency with stale rates fetches and shows the fetched rates' own date`() {
        val today = com.diegonmarcos.cloudcalc.Fx.expectedDate(System.currentTimeMillis())
        engine.time = day(today.minusDays(9)); engine.fetchedTime = day(today)
        openCurrency()
        compose.waitUntil(5_000) { engine.fetches == 1 && runCatching { compose.onNodeWithText("Rates as of $today", substring = true).assertExists() }.isSuccess }
        // The old date is gone: the line was re-read after the fetch, not kept.
        assertEquals(0, compose.onAllNodesWithText("Rates as of ${today.minusDays(9)}", substring = true).fetchSemanticsNodes().size)
    }

    @Test fun `fresh rates are not fetched on open`() {
        val today = com.diegonmarcos.cloudcalc.Fx.expectedDate(System.currentTimeMillis())
        engine.time = day(today)
        openCurrency()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Rates as of $today", substring = true).assertExists() }.isSuccess }
        compose.waitForIdle()
        assertEquals(0, engine.fetches)
    }

    @Test fun `a failed refresh on open leaves the cached date and notes the failure`() {
        val today = com.diegonmarcos.cloudcalc.Fx.expectedDate(System.currentTimeMillis())
        engine.time = day(today.minusDays(9)); engine.fetchOk = false
        openCurrency()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("update failed: offline", substring = true).assertExists() }.isSuccess }
        compose.onNodeWithText("Rates as of ${today.minusDays(9)}", substring = true).assertExists()
    }

    @Test fun `the cross-rate matrix of the favourites sits under the converter`() {
        openCurrency()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(CalcTags.matrixCell("USD", "EUR")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(CalcTags.matrixCell("USD", "EUR")).assertTextContains("42") }.isSuccess }
        compose.onNodeWithTag(CalcTags.matrixCell("BRL", "BRL")).assertTextContains("—")
        assertEquals(36, Declarations.modes.first { it.id == "currency" }.favourites.size.let { it * it })
    }

    @Test fun `a history tap sends the result back to its mode`() {
        val mode = Declarations.modes.first { it.kind == "expression" }
        state.remember(Logic.Entry(mode.id, "6*7", "42"), 10)
        launch()
        val history = Declarations.modes.first { it.kind == "history" }
        compose.runOnIdle { state.tab = history.tab }
        compose.waitForIdle()
        compose.runOnIdle { state.send(mode.id, "42") }
        compose.waitForIdle()
        assertEquals(mode.tab, state.tab)
        compose.onNodeWithTag(CalcTags.INPUT).assertTextContains("42")
    }
}
