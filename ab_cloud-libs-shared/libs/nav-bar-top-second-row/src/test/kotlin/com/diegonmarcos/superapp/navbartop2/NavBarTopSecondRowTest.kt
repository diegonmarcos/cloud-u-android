package com.diegonmarcos.superapp.navbartop2

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.navbarbottom.BottomSpec
import com.diegonmarcos.superapp.themebg.ThemeBackground
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavBarTopSecondRowTest {
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

    private fun pages(n: Int) = (1..n).map { NavPage("p$it", "Page $it") }

    private fun show(n: Int, selected: String = "p1", onSelect: (NavPage) -> Unit = {}) =
        compose.setContent { NavBarTopSecondRow(pages(n), selected, onSelect, ThemeBackground.colors(true)) }

    @Test fun `it is clearly smaller than the bottom nav in height, icon and font`() {
        val max = SecondRowSpec.SCALE_MAX
        assertTrue(SecondRowSpec.TAB_HEIGHT.value / BottomSpec.CELL.value <= max)
        assertTrue(SecondRowSpec.ICON.value / BottomSpec.ICON.value <= max)
        assertTrue(SecondRowSpec.LABEL.value / BottomSpec.LABEL.value <= max)
        assertTrue("label stays legible", SecondRowSpec.LABEL.value >= 8f)
        assertTrue("the whole strip is shorter than the whole bar",
            SecondRowSpec.TAB_HEIGHT.value + 2 * SecondRowSpec.STRIP_PAD_V.value < BottomSpec.HEIGHT.value)
    }

    @Test fun `the rendered tab is the declared height`() {
        show(3)
        val tab = compose.onNodeWithTag(SecondRowTags.tab("p2")).fetchSemanticsNode().boundsInRoot
        assertEquals(SecondRowSpec.TAB_HEIGHT.value, tab.height, 0.5f)
    }

    @Test fun `a single tab is centred`() {
        show(1)
        val strip = compose.onNodeWithTag(SecondRowTags.STRIP).fetchSemanticsNode().boundsInRoot
        val tab = compose.onNodeWithTag(SecondRowTags.tab("p1")).fetchSemanticsNode().boundsInRoot
        assertEquals((strip.left + strip.right) / 2, (tab.left + tab.right) / 2, 1.5f)
    }

    @Test fun `several tabs are spaced evenly including the borders`() {
        show(3, selected = "p2")
        val strip = compose.onNodeWithTag(SecondRowTags.STRIP).fetchSemanticsNode().boundsInRoot
        val t = (1..3).map { compose.onNodeWithTag(SecondRowTags.tab("p$it")).fetchSemanticsNode().boundsInRoot }
        val gaps = listOf(t[0].left - strip.left, t[1].left - t[0].right, t[2].left - t[1].right, strip.right - t[2].right)
        for (g in gaps) assertEquals("gaps $gaps", gaps[0], g, 1.5f)
    }

    @Test fun `selecting calls back with the page`() {
        var picked: String? = null
        show(3, onSelect = { picked = it.id })
        compose.onNodeWithTag(SecondRowTags.tab("p3")).performClick()
        assertEquals("p3", picked)
    }

    @Test fun `the selected tab is the SuperApp violet rounded rectangle`() {
        val c = ThemeBackground.colors(true)
        assertEquals(ThemeBackground.TAB_SELECTED, c.tabSelected)
        assertTrue("a rounded rectangle, not a circle: radius below half the tab height", SecondRowSpec.TAB_RADIUS.value < SecondRowSpec.TAB_HEIGHT.value / 2)
        assertTrue(c.tabSelected != Color.Gray && c.tabSelected.alpha == 1f)
    }
}
