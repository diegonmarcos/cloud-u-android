package com.diegonmarcos.cloudcalc

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import com.diegonmarcos.cloudcalc.sheet.Area
import com.diegonmarcos.cloudcalc.sheet.Format
import androidx.compose.ui.test.performTextReplacement
import com.diegonmarcos.cloudcalc.engine.CalcApi
import com.diegonmarcos.cloudcalc.sheetui.SheetHub
import com.diegonmarcos.cloudcalc.ui.CalcShell
import com.diegonmarcos.cloudcalc.ui.CalcState
import com.diegonmarcos.cloudcalc.ui.CalcTags
import com.diegonmarcos.cloudcalc.ui.CalcTheme
import com.diegonmarcos.cloudcalc.ui.DrawerTags
import com.diegonmarcos.cloudcalc.ui.SheetTags
import com.diegonmarcos.superapp.bottomnav.BottomNavTags
import com.diegonmarcos.superapp.bottomnav.TopBarTags
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

/** The Sheet page's point-and-click references (tap, cross-sheet, drag) and its status bar, under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SheetPointTest {
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

    private fun st() = SheetHub.get(RuntimeEnvironment.getApplication())
    private fun openSheet() {
        launch()
        compose.onNodeWithTag(BottomNavTags.item("sheet")).performClick()
        compose.waitForIdle()
    }
    private fun cell(c: Int, r: Int) = compose.onNodeWithTag(SheetTags.cell(c, r))
    private fun bar() = compose.onNodeWithTag(SheetTags.EDIT_BAR)

    @Test fun `tapping a cell while a formula is edited inserts its address, and a second tap replaces it`() {
        openSheet()
        cell(3, 0).performClick()                      // D1 is the cell being edited
        bar().performTextReplacement("=")
        cell(0, 1).performClick(); compose.waitForIdle()
        bar().assertTextContains("=A2")
        assertEquals("the tap did not move the cell", 3, st().anchorC)
        cell(2, 2).performClick(); compose.waitForIdle()
        bar().assertTextContains("=C3")                 // replaced, not appended
        assertEquals("=C3", st().draft.text)
        bar().performTextReplacement("=C3+")
        cell(0, 0).performClick(); compose.waitForIdle()
        assertEquals("=C3+A1", st().draft.text)
        compose.onNodeWithTag(SheetTags.COMMIT).performClick(); compose.waitForIdle()
        assertEquals("=C3+A1", st().wb.raw("Sheet1", 3, 0))
    }

    @Test fun `a tap where no reference can stand commits the formula and selects the cell`() {
        openSheet()
        cell(3, 0).performClick()
        bar().performTextReplacement("=2")
        cell(0, 3).performClick(); compose.waitForIdle()
        assertEquals("=2", st().wb.raw("Sheet1", 3, 0))
        assertEquals(0, st().anchorC); assertEquals(3, st().anchorR)
    }

    @Test fun `a tap on another sheet inserts Sheet2!A1 and the formula still lands on its own cell`() {
        openSheet()
        compose.runOnIdle { st().addSheet() }
        compose.runOnIdle { st().switchTo(0) }
        compose.waitForIdle()
        cell(3, 0).performClick()
        bar().performTextReplacement("=")
        compose.onNodeWithTag(SheetTags.tab("Sheet2")).performClick(); compose.waitForIdle()
        cell(0, 0).performClick(); compose.waitForIdle()
        assertEquals("=Sheet2!A1", st().draft.text)
        compose.onNodeWithTag(SheetTags.COMMIT).performClick(); compose.waitForIdle()
        assertEquals("Sheet1", st().sheet)
        assertEquals("=Sheet2!A1", st().wb.raw("Sheet1", 3, 0))
    }

    @Test fun `press and drag over cells inserts a range reference`() {
        openSheet()
        cell(3, 0).performClick()
        bar().performTextReplacement("=SUM(")
        cell(0, 0).performTouchInput {
            down(center)
            advanceEventTime(800)                        // past the long-press timeout
            moveBy(Offset(width.toFloat(), 0f))
            advanceEventTime(50)
            moveBy(Offset(0f, height.toFloat()))
            up()
        }
        compose.waitForIdle()
        assertEquals("=SUM(A1:B2", st().draft.text)
    }

    @Test fun `the drag steps replace each other (state level) and the range is normalised`() {
        openSheet()
        cell(3, 0).performClick()
        bar().performTextReplacement("=SUM(")
        compose.runOnIdle { st().dragBegin(1, 4); st().dragTo(0, 2); st().dragTo(0, 0); st().dragEnd() }
        assertEquals("=SUM(A1:B5", st().draft.text)
    }

    @Test fun `a long press and drag selects a range when no formula is edited, and the Range toggle still works`() {
        openSheet()
        compose.runOnIdle { st().dragBegin(0, 0); st().dragTo(2, 3); st().dragEnd() }
        assertEquals(Area(0, 0, 2, 3), st().area)
        compose.onNodeWithTag(SheetTags.handle("end"), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(SheetTags.EXTEND).performClick()
        cell(0, 0).performClick(); compose.onNodeWithTag(SheetTags.cell(4, 4)).performClick()
        compose.waitForIdle()
        assertEquals(Area(0, 0, 4, 4), st().area)
    }

    @Test fun `dragging the end handle grows the selection`() {
        openSheet()
        cell(0, 0).performClick()
        compose.onNodeWithTag(SheetTags.handle("end"), useUnmergedTree = true).performTouchInput {
            down(center); moveBy(Offset(width * 40f, height * 20f)); up()
        }
        compose.waitForIdle()
        assertTrue("the selection grew: ${st().area}", st().area.cols > 1 || st().area.rows > 1)
    }

    private fun fill(vararg raw: String) {
        compose.runOnIdle { raw.forEachIndexed { i, t -> st().wb.set("Sheet1", 0, i, t) }; st().bump() }
    }

    @Test fun `the status bar shows the stats of the selected numbers`() {
        openSheet()
        fill("1", "2", "3", "text", "")
        compose.runOnIdle { st().selectArea(Area(0, 0, 0, 4)) }
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTags.STATUS_BAR).assertExists()
        compose.onNodeWithTag(SheetTags.stat("sum")).assertTextContains("6")
        compose.onNodeWithTag(SheetTags.stat("avg")).assertTextContains("2")
        compose.onNodeWithTag(SheetTags.stat("median")).assertTextContains("2")
        compose.onNodeWithTag(SheetTags.stat("stdev")).assertTextContains("1")
        compose.onNodeWithTag(SheetTags.stat("min")).assertTextContains("1")
        compose.onNodeWithTag(SheetTags.stat("max")).assertTextContains("3")
        compose.onNodeWithTag(SheetTags.stat("count")).assertTextContains("3")
        listOf("ci_67", "ci_97.5", "ci_99").forEach { compose.onNodeWithTag(SheetTags.stat(it)).assertTextContains("[") }
        compose.onNodeWithTag(SheetTags.stat("dist")).assertTextContains("Student")
        assertTrue("no errors, no errors chip", compose.onAllNodesWithTag(SheetTags.stat("errors")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun `the status bar follows the number format, counts errors, and hides without numbers`() {
        openSheet()
        fill("10", "20", "=1/0")
        compose.runOnIdle { st().wb.setFormat("Sheet1", Area(0, 0, 0, 1), Format.currency("$", 2)); st().bump(); st().selectArea(Area(0, 0, 0, 2)) }
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTags.stat("sum")).assertTextContains("$30.00")
        compose.onNodeWithTag(SheetTags.stat("errors")).assertTextContains("1")
        compose.runOnIdle { st().selectArea(Area(5, 5, 6, 6)) }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag(SheetTags.STATUS_BAR).fetchSemanticsNodes().isEmpty())
    }

    @Test fun `one number has no deviation and no interval`() {
        openSheet()
        fill("5")
        compose.runOnIdle { st().selectArea(Area(0, 0, 0, 0)) }
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTags.stat("sum")).assertTextContains("5")
        assertTrue(compose.onAllNodesWithTag(SheetTags.stat("stdev")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithTag(SheetTags.stat("ci_99")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun `tapping a stat copies it`() {
        openSheet()
        fill("1", "2", "3")
        compose.runOnIdle { st().selectArea(Area(0, 0, 0, 2)) }
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTags.stat("sum")).performClick()
        compose.waitForIdle()
        val cm = RuntimeEnvironment.getApplication().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        assertEquals("6", cm.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test fun `the status bar steps aside while a formula is edited`() {
        openSheet()
        fill("1", "2", "3")
        compose.runOnIdle { st().selectArea(Area(0, 0, 0, 2)) }
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTags.STATUS_BAR).assertExists()
        bar().performTextReplacement("=SUM(")
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag(SheetTags.STATUS_BAR).fetchSemanticsNodes().isEmpty())
    }
}
