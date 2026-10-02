package com.diegonmarcos.cloudcalc

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.diegonmarcos.cloudcalc.engine.CalcApi
import com.diegonmarcos.cloudcalc.ui.CalcShell
import com.diegonmarcos.cloudcalc.ui.CalcState
import com.diegonmarcos.cloudcalc.ui.CalcTags
import com.diegonmarcos.cloudcalc.ui.CalcTheme
import com.diegonmarcos.superapp.bottomnav.BottomNavTags
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
        override fun ratesInfo() = """{"sources":[],"time":1783296000}"""
        override fun fetchRates() = """{"ok":true,"fetched":[],"failed":[],"time":1783296000}"""
    }

    private val engine = FakeEngine()
    private val state = CalcState(null)

    private fun launch() = compose.setContent { CalcTheme { CalcShell(engine, state) } }

    @Test fun `every declared mode is reachable from the nav and composes`() {
        launch()
        Declarations.tabs.forEach { tab ->
            compose.onNodeWithTag(BottomNavTags.item(tab.id)).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(CalcTags.tab(tab.id)).assertExists()
            val modes = Declarations.modesOf(tab.id)
            if (modes.size > 1) {
                // The strip is a LazyRow: a chip scrolled out of view is not composed, so the
                // first is clicked and the rest are selected through the state the chips write.
                compose.onNodeWithTag(CalcTags.chip(modes.first().id)).performClick()
            }
            modes.forEach { m ->
                compose.runOnIdle { state.modeByTab[tab.id] = m.id }
                compose.waitForIdle()
                compose.onNodeWithTag(CalcTags.mode(m.id)).assertExists()
            }
        }
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
        assertEquals(Logic.Entry(mode.id, "2+2", "4"), state.history.first())
        assertTrue(synchronized(engine.evals) { "2+2" in engine.evals })
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
