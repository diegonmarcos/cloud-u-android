package com.diegonmarcos.cloudagenda

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.cloudagenda.data.ThemeMode
import com.diegonmarcos.cloudagenda.ui.ChromeTags
import com.diegonmarcos.cloudagenda.ui.DrawerSheet
import com.diegonmarcos.cloudagenda.ui.LocalPageDrawer
import com.diegonmarcos.cloudagenda.ui.PageDrawer
import com.diegonmarcos.cloudagenda.ui.PageDrawerState
import com.diegonmarcos.cloudagenda.ui.TasksTheme
import com.diegonmarcos.cloudagenda.ui.TopRow
import androidx.compose.runtime.CompositionLocalProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The app chrome on its own: the top row (hamburger left; AI and Profile as circular buttons right) and the page-managed drawer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChromeTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules = composeChain(compose)

    private var menu = 0; private var ai = 0; private var account = 0; private var settings = 0; private var back = 0

    private fun row(withBack: Boolean = false, aiOpen: Boolean = false) = compose.setContent {
        TasksTheme(ThemeMode.LIGHT) {
            TopRow("Tasks", { menu++ }, { ai++ }, { account++ }, { settings++ }, aiOpen = aiOpen, onBack = if (withBack) ({ back++ }) else null)
        }
    }

    @Test fun `the top row has a hamburger on the left and the AI and Profile buttons on the right`() {
        row()
        compose.onNodeWithTag(ChromeTags.HAMBURGER).assertIsDisplayed()
        compose.onNodeWithTag(ChromeTags.AI).assertIsDisplayed()
        compose.onNodeWithTag(ChromeTags.PROFILE).assertIsDisplayed()
        compose.onNodeWithTag(ChromeTags.TITLE).assertIsDisplayed()
        val h = compose.onNodeWithTag(ChromeTags.HAMBURGER).fetchSemanticsNode().boundsInRoot
        val t = compose.onNodeWithTag(ChromeTags.TITLE).fetchSemanticsNode().boundsInRoot
        val a = compose.onNodeWithTag(ChromeTags.AI).fetchSemanticsNode().boundsInRoot
        val p = compose.onNodeWithTag(ChromeTags.PROFILE).fetchSemanticsNode().boundsInRoot
        assertTrue("hamburger is left of the title", h.right <= t.left + 1)
        assertTrue("AI sits left of Profile, both after the title", t.left < a.left && a.right <= p.left + 1)
        assertEquals("circular buttons are square", a.width, a.height, 1f)
        assertEquals(p.width, p.height, 1f)
    }

    @Test fun `hamburger and AI report their taps`() {
        row()
        compose.onNodeWithTag(ChromeTags.HAMBURGER).performClick()
        compose.onNodeWithTag(ChromeTags.AI).performClick()
        assertEquals(1, menu); assertEquals(1, ai)
    }

    @Test fun `Profile opens a menu with Account and Settings`() {
        row()
        compose.onNodeWithTag(ChromeTags.PROFILE_ACCOUNT).assertDoesNotExist()
        compose.onNodeWithTag(ChromeTags.PROFILE).performClick()
        compose.onNodeWithTag(ChromeTags.PROFILE_ACCOUNT).assertIsDisplayed()
        compose.onNodeWithTag(ChromeTags.PROFILE_SETTINGS).assertIsDisplayed()
        compose.onNodeWithTag(ChromeTags.PROFILE_ACCOUNT).performClick()
        assertEquals(1, account); assertEquals(0, settings)
        compose.onNodeWithTag(ChromeTags.PROFILE).performClick()
        compose.onNodeWithTag(ChromeTags.PROFILE_SETTINGS).performClick()
        assertEquals(1, settings)
    }

    @Test fun `a sub-screen swaps the hamburger for a back arrow`() {
        row(withBack = true)
        compose.onNodeWithTag(ChromeTags.BACK).assertIsDisplayed().performClick()
        assertEquals(1, back)
        compose.onNodeWithTag(ChromeTags.HAMBURGER).assertDoesNotExist()
    }

    // ── the page-managed drawer ─────────────────────────────────────────────────────────────────────────

    @Test fun `the drawer shows what the page supplies and the empty state when the page leaves`() {
        var page by mutableStateOf<String?>("one")
        val state = PageDrawerState()
        compose.setContent {
            TasksTheme(ThemeMode.DARK) {
                CompositionLocalProvider(LocalPageDrawer provides state) {
                    Column {
                        when (page) {
                            "one" -> PageDrawer { Text("drawer of one", Modifier.testTag("d1")) }
                            "two" -> PageDrawer { Text("drawer of two", Modifier.testTag("d2").fillMaxWidth()) }
                            else -> {}
                        }
                        DrawerSheet(state, close = {})
                    }
                }
            }
        }
        compose.onNodeWithTag("d1").assertIsDisplayed()
        assertTrue(state.hasContent)
        page = "two"
        compose.waitForIdle()
        compose.onNodeWithTag("d2").assertIsDisplayed()
        compose.onNodeWithTag("d1").assertDoesNotExist()
        page = null
        compose.waitForIdle()
        assertFalse("a page that is gone takes its drawer with it", state.hasContent)
        compose.onNodeWithTag(ChromeTags.DRAWER_EMPTY).assertIsDisplayed()
    }
}
