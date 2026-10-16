package com.diegonmarcos.cloudcalc

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.diegonmarcos.cloudcalc.engine.CalcApi
import com.diegonmarcos.cloudcalc.sheetui.SheetHub
import com.diegonmarcos.cloudcalc.ui.CalcShell
import com.diegonmarcos.cloudcalc.ui.CalcState
import com.diegonmarcos.cloudcalc.ui.CalcTags
import com.diegonmarcos.cloudcalc.ui.CalcTheme
import com.diegonmarcos.cloudcalc.ui.SheetTags
import com.diegonmarcos.superapp.bottomnav.BottomNavTags
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

/** The new shell pieces under Robolectric: the bar's order, the round Configs button, and the Sheet page's tabs and cells. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SheetShellTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                SheetHub.reset()
                base.evaluate()
            }
        }
    }).around(compose)

    private object NoEngine : CalcApi {
        override fun info() = """{"ok":true}"""
        override fun eval(expr: String, options: String) = """{"ok":true,"result":"0","messages":[]}"""
        override fun plot(expr: String, xmin: Double, xmax: Double, steps: Int) = """{"ok":true,"x":[],"y":[]}"""
        override fun complete(prefix: String, max: Int) = "[]"
        override fun items(kind: String, category: String, max: Int) = "[]"
        override fun ratesInfo() = """{"sources":[],"time":0}"""
        override fun fetchRates() = """{"ok":false,"error":"offline","fetched":[],"failed":[],"time":0}"""
    }

    private val state = CalcState(null)
    private fun launch() = compose.setContent { CalcTheme { CalcShell(NoEngine, state) } }

    @Test fun `the bar is Calc, Sheet, AI, Measure, JEV left to right`() {
        launch()
        val ids = listOf("calculator", "sheet", "ai", "measure", "jev")
        assertEquals(ids, Declarations.sections.map { it.id })
        val lefts = ids.map { compose.onNodeWithTag(BottomNavTags.item(it)).getBoundsInRoot().left }
        assertEquals("left to right", lefts.sorted(), lefts)
        assertTrue(lefts.toSet().size == ids.size)
    }

    @Test fun `the round Configs button opens Configs, is not on the bar, and goes back`() {
        launch()
        assertTrue(compose.onAllNodesWithTag(BottomNavTags.item("settings")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithTag(BottomNavTags.item("configs")).fetchSemanticsNodes().isEmpty())
        val before = state.tab
        compose.onNodeWithTag(CalcTags.CONFIGS_BUTTON).performClick()
        compose.waitForIdle()
        assertEquals("configs", state.tab)
        compose.onNodeWithTag(CalcTags.tab("configs")).assertExists()
        compose.onNodeWithTag(CalcTags.CONFIGS_BUTTON).performClick()
        compose.waitForIdle()
        assertEquals(before, state.tab)
    }

    @Test fun `Jev has Ask only now, and the Sheet and AI pages are reachable from the bar`() {
        launch()
        compose.onNodeWithTag(BottomNavTags.item("jev")).performClick()
        compose.waitForIdle()
        assertEquals("ask", state.tab)
        compose.onNodeWithTag(BottomNavTags.item("sheet")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTags.ROOT).assertExists()
        compose.onNodeWithTag(BottomNavTags.item("ai")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(CalcTags.AI_PAGE).assertExists()
    }

    @Test fun `sheet tabs add, rename, duplicate, delete and reorder`() {
        launch()
        compose.onNodeWithTag(BottomNavTags.item("sheet")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTags.tab("Sheet1")).assertExists()
        compose.onNodeWithTag(SheetTags.ADD_TAB).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTags.tab("Sheet2")).assertExists()
        val st = SheetHub.get(RuntimeEnvironment.getApplication())
        compose.runOnIdle { st.rename("Sheet2", "Totals"); st.duplicate("Totals") }
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTags.tab("Totals")).assertExists()
        compose.onNodeWithTag(SheetTags.tab("Totals (copy)")).assertExists()
        compose.runOnIdle { st.move("Totals (copy)", -2) }
        assertEquals(listOf("Totals (copy)", "Sheet1", "Totals"), st.wb.sheetNames())
        compose.runOnIdle { st.delete("Totals (copy)") }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag(SheetTags.tab("Totals (copy)")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun `typing in the edit bar enters a formula and the grid shows the result`() {
        launch()
        compose.onNodeWithTag(BottomNavTags.item("sheet")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTags.cell(0, 0)).performClick()
        compose.onNodeWithTag(SheetTags.EDIT_BAR).performTextReplacement("4")
        compose.onNodeWithTag(SheetTags.COMMIT).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTags.cell(0, 1)).performClick()
        compose.onNodeWithTag(SheetTags.EDIT_BAR).performTextReplacement("=A1*2")
        compose.onNodeWithTag(SheetTags.COMMIT).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTags.cell(0, 1)).assertTextContains("8")
        // the edit bar shows the RAW formula
        compose.onNodeWithTag(SheetTags.cell(0, 1)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTags.EDIT_BAR).assertTextContains("=A1*2")
        // undo takes the formula away again
        compose.onNodeWithTag(SheetTags.UNDO).performClick()
        compose.waitForIdle()
        assertEquals("", SheetHub.get(RuntimeEnvironment.getApplication()).wb.raw("Sheet1", 0, 1))
    }
}
