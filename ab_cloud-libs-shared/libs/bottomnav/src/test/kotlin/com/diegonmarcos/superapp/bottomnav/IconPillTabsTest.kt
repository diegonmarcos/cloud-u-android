package com.diegonmarcos.superapp.bottomnav

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
 * #1150 the sub-page tab strip (PageTabs with `iconFor`), MEASURED at mdpi (px == dp):
 *  - it is visibly SMALLER than the bottom nav it hangs under (tab height, glyph size, name size), to show hierarchy;
 *  - 1 tab sits centred; N tabs are space-evenly: the gap between neighbours equals the gap to each border.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconPillTabsTest {

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

    private val res get() = RuntimeEnvironment.getApplication().resources

    private fun show(count: Int) {
        val pages = (0 until count).map { NavPage("p$it", "P$it", "", "") }
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                PageTabsImpl(pages, "p0", {}, Modifier, {}, false, WindowInsets(0, 0, 0, 0),
                    iconFor = { ColorPainter(Color.White) })
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test
    fun `the sub-page tabs are smaller than the bottom nav`() {
        show(3)
        val navItem = res.getDimension(R.dimen.bottom_nav_icon_size) + 2 * res.getDimension(R.dimen.bottom_nav_item_vertical_pad)
        val tab = bounds(PageTabsTags.tab("p1"))
        assertTrue("a tab (${tab.height}) is not shorter than a collapsed nav item ($navItem)", tab.height < navItem)
        val icon = bounds(PageTabsTags.icon("p1"))
        assertTrue("a tab glyph (${icon.width}) is not smaller than the nav icon", icon.width < res.getDimension(R.dimen.bottom_nav_icon_size))
        assertTrue("a tab is 32dp high at most", tab.height <= 32.5f)
    }

    @Test
    fun `one tab is centred`() {
        show(1)
        val strip = bounds(PageTabsTags.STRIP)
        val tab = bounds(PageTabsTags.tab("p0"))
        assertEquals("the lone tab is off-centre", strip.center.x, tab.center.x, 1f)
    }

    private fun assertEvenGaps(n: Int) {
        show(n)
        val strip = bounds(PageTabsTags.STRIP)
        val tabs = (0 until n).map { bounds(PageTabsTags.tab("p$it")) }
        val gaps = buildList {
            add(tabs.first().left - strip.left)
            for (i in 1 until n) add(tabs[i].left - tabs[i - 1].right)
            add(strip.right - tabs.last().right)
        }
        for (g in gaps) assertEquals("n=$n gaps differ: $gaps", gaps.first(), g, 1.5f)
        assertTrue("n=$n tabs touch: $gaps", gaps.first() > 0f)
    }

    @Test fun `two tabs have equal gaps between them and to both borders`() = assertEvenGaps(2)
    @Test fun `three tabs have equal gaps between them and to both borders`() = assertEvenGaps(3)
    @Test fun `four tabs have equal gaps between them and to both borders`() = assertEvenGaps(4)
}
