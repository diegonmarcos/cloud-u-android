package com.diegonmarcos.superapp.navbarbottom

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.bottomnav.BottomNavEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
class NavBarBottomTest {
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

    private fun spread(c: Color) = maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)

    @Test fun `selection is Cloud Search's accent colour, never a gray`() {
        for (dark in listOf(true, false)) {
            val on = BottomSpec.selectedInk(dark)
            assertTrue("selected ink is coloured (dark=$dark)", spread(on) > 0.5f)
            assertNotEquals(BottomSpec.idleInk(dark), on)
        }
        assertEquals(Color(0xFF0A84FF), BottomSpec.selectedInk(true))
        assertEquals(Color(0xFF007AFF), BottomSpec.selectedInk(false))
    }

    @Test fun `cells keep their size until the bar is crowded, then shrink evenly`() {
        assertEquals(BottomSpec.CELL, BottomSpec.cellWidth(328.dp, 5))
        val eight = BottomSpec.cellWidth(328.dp, 8)
        assertTrue("8 items on a 360dp phone shrink: $eight", eight < BottomSpec.CELL)
        assertEquals((328.dp - BottomSpec.INNER_PAD * 2) / 8, eight)
    }

    @Test fun `the bar is as declared and every cell is a tab`() {
        var picked: String? = null
        compose.setContent {
            val entries = listOf(
                BottomNavEntry("home", "Home", rememberVectorPainter(Icons.Filled.Home)),
                BottomNavEntry("mail", "Mail", rememberVectorPainter(Icons.Filled.Mail)),
                BottomNavEntry("set", "Settings", rememberVectorPainter(Icons.Filled.Settings)),
            )
            NavBarBottom(entries, "home", { picked = it.id }, dark = true)
        }
        val bar = compose.onNodeWithTag(BottomTags.BAR).fetchSemanticsNode().boundsInRoot
        assertEquals(BottomSpec.HEIGHT.value, bar.height, 0.5f)
        val cell = compose.onNodeWithTag(BottomTags.item("mail")).fetchSemanticsNode().boundsInRoot
        assertEquals(BottomSpec.CELL.value, cell.width, 0.5f)
        val icon = compose.onNodeWithTag(BottomTags.icon("mail")).fetchSemanticsNode().boundsInRoot
        assertEquals(BottomSpec.ICON.value, icon.width, 0.5f)
        compose.onNodeWithTag(BottomTags.item("mail")).performClick()
        assertEquals("mail", picked)
    }

    @Test fun `the host ends the page where the bar begins and scrolling collapses the bar to icons`() {
        compose.setContent {
            val entries = listOf(
                BottomNavEntry("home", "Home", rememberVectorPainter(Icons.Filled.Home)),
                BottomNavEntry("mail", "Mail", rememberVectorPainter(Icons.Filled.Mail)),
            )
            NavBarBottomHost(entries, "home", {}) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("page")) { Spacer(Modifier.height(6000.dp)) }
            }
        }
        compose.waitForIdle()
        val content = compose.onNodeWithTag(NAV_BAR_BOTTOM_CONTENT_TAG).fetchSemanticsNode().boundsInRoot
        val bar = compose.onNodeWithTag(BottomTags.BAR).fetchSemanticsNode().boundsInRoot
        assertTrue("page ($content) runs under the bar ($bar)", content.bottom <= bar.top + 1f)
        val labels = { compose.onAllNodesWithTag(BottomTags.label("home"), useUnmergedTree = true).fetchSemanticsNodes().size }
        assertEquals(1, labels())
        compose.onNodeWithTag("page").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertEquals("labels survive the collapse", 0, labels())
        assertTrue("the collapse shrinks the bar", compose.onNodeWithTag(BottomTags.BAR).fetchSemanticsNode().boundsInRoot.height < BottomSpec.HEIGHT.value - 1f)
        compose.onNodeWithTag("page").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals("labels return on scroll up", 1, labels())
    }
}
