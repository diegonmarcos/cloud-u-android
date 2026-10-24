package com.diegonmarcos.superapp.navbarbottom

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.Color
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
}
