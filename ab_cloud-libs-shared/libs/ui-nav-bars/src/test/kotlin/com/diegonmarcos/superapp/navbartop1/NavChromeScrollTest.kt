package com.diegonmarcos.superapp.navbartop1

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.bottomnav.BottomNavEntry
import com.diegonmarcos.superapp.bottomnav.ChromeScrollPolicy
import com.diegonmarcos.superapp.bottomnav.LocalChromeScrollPolicy
import com.diegonmarcos.superapp.bottomnav.LocalNavUiStore
import com.diegonmarcos.superapp.bottomnav.MemoryNavUiStore
import com.diegonmarcos.superapp.bottomnav.NavQuickAction
import com.diegonmarcos.superapp.bottomnav.NavUiMode
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.TopBarTags
import com.diegonmarcos.superapp.navbarbottom.BottomTags
import com.diegonmarcos.superapp.navbarbottom.NavBarBottomHost
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import com.diegonmarcos.superapp.navbartop2.SecondRowTags
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
 * #1157/#1159: ONE scroll connection drives the whole chrome. At rest every item of the second row and the bottom nav shows
 * its label; scrolling the page down hides the first row, keeps only the selected label in both bars and shrinks both;
 * scrolling up restores all three.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavChromeScrollTest {
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

    /**
     * [policy] Shrink = the older three-state machine (hidden while dragging, compact bars after the finger lifts), which the first two
     * tests describe; null = the policy of the nav UI settings in force, which is scroll-hide ON by default (every bar hides and stays hidden).
     * [ui] seeds the user's choice (Classic / Clean) the way the Configs > UI card persists it.
     */
    private fun show(
        policy: ChromeScrollPolicy? = ChromeScrollPolicy.Shrink,
        ui: MemoryNavUiStore = MemoryNavUiStore(),
    ) = compose.setContent {
        val entries = listOf(
            BottomNavEntry("home", "Home", rememberVectorPainter(Icons.Outlined.Home)),
            BottomNavEntry("mail", "Mail", rememberVectorPainter(Icons.Outlined.Mail)),
        )
        CompositionLocalProvider(LocalChromeScrollPolicy provides policy, LocalNavUiStore provides ui) {
        NavBarTopFirstRowScaffold(title = "Title", hierarchy = emptyList(), onNavigate = {}, onAccount = {}, onConfigs = {}) {
            NavBarBottomHost(entries, "home", {}) {
                Column(Modifier.fillMaxSize()) {
                    NavBarTopSecondRow(
                        listOf(NavPage("p1", "One", "home"), NavPage("p2", "Two", "folder"), NavPage("p3", "Three", "settings")),
                        "p1", {}, underTopChrome = false,
                    )
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("page")) { Spacer(Modifier.height(6000.dp)) }
                }
            }
        }
        }
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
    private fun h(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height

    @Test fun `at rest every label shows, scrolling down compacts and keeps the selected label, scrolling up restores`() {
        show()
        compose.waitForIdle()
        val restTab = h(SecondRowTags.tab("p1")); val restBar = h(BottomTags.BAR)
        assertEquals("first row shown", 1, count(TopBarTags.BAR_SLOT))
        for (id in listOf("p1", "p2", "p3")) assertEquals("second row label $id at rest", 1, count(SecondRowTags.label(id)))
        for (id in listOf("home", "mail")) assertEquals("bottom label $id at rest", 1, count(BottomTags.label(id)))

        compose.onNodeWithTag("page").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertEquals("first row hidden", 0, count(TopBarTags.BAR_SLOT))
        assertEquals("selected second-row label stays", 1, count(SecondRowTags.label("p1")))
        assertEquals(0, count(SecondRowTags.label("p2"))); assertEquals(0, count(SecondRowTags.label("p3")))
        assertEquals("selected bottom label stays", 1, count(BottomTags.label("home")))
        assertEquals(0, count(BottomTags.label("mail")))
        assertTrue("second row shrinks", h(SecondRowTags.tab("p1")) < restTab - 1f)
        assertTrue("bottom nav shrinks", h(BottomTags.BAR) < restBar - 1f)

        compose.onNodeWithTag("page").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals("first row restored", 1, count(TopBarTags.BAR_SLOT))
        for (id in listOf("p1", "p2", "p3")) assertEquals(1, count(SecondRowTags.label(id)))
        for (id in listOf("home", "mail")) assertEquals(1, count(BottomTags.label(id)))
        assertEquals(restTab, h(SecondRowTags.tab("p1")), 1f)
        assertEquals(restBar, h(BottomTags.BAR), 1f)
    }

    @Test fun `while the finger drags down every bar is hidden, on release the second row and bottom nav return shrunk, up restores`() {
        show()
        compose.waitForIdle()
        compose.onNodeWithTag("page").performTouchInput {
            down(center)
            repeat(8) { moveBy(androidx.compose.ui.geometry.Offset(0f, -60f)) }
        }
        compose.waitForIdle()
        assertEquals("HIDDEN: first row away", 0, count(TopBarTags.BAR_SLOT))
        assertEquals("HIDDEN: second row away", 0, count(SecondRowTags.STRIP))
        assertEquals("HIDDEN: bottom nav away", 0, count(BottomTags.BAR))

        compose.onNodeWithTag("page").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals("SHRUNK: first row stays away", 0, count(TopBarTags.BAR_SLOT))
        assertEquals("SHRUNK: second row is back", 1, count(SecondRowTags.STRIP))
        assertEquals("SHRUNK: bottom nav is back", 1, count(BottomTags.BAR))
        assertEquals(1, count(SecondRowTags.label("p1"))); assertEquals(0, count(SecondRowTags.label("p2")))
        assertEquals(1, count(BottomTags.label("home"))); assertEquals(0, count(BottomTags.label("mail")))

        compose.onNodeWithTag("page").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals("DEFAULT: first row back", 1, count(TopBarTags.BAR_SLOT))
        assertEquals(1, count(SecondRowTags.label("p2")))
        assertEquals(1, count(BottomTags.label("mail")))
    }

    @Test fun `scroll-hide on, a downward drag hides every bar and they STAY hidden after the finger lifts, an upward swipe restores all`() {
        show(policy = null)
        compose.waitForIdle()
        assertEquals(1, count(TopBarTags.BAR_SLOT)); assertEquals(1, count(SecondRowTags.STRIP)); assertEquals(1, count(BottomTags.BAR))
        compose.onNodeWithTag("page").performTouchInput {
            down(center)
            repeat(8) { moveBy(androidx.compose.ui.geometry.Offset(0f, -60f)) }
            up()
        }
        compose.waitForIdle()
        assertEquals("first row away after release", 0, count(TopBarTags.BAR_SLOT))
        assertEquals("second row away after release", 0, count(SecondRowTags.STRIP))
        assertEquals("bottom nav away after release", 0, count(BottomTags.BAR))

        compose.onNodeWithTag("page").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(1, count(TopBarTags.BAR_SLOT)); assertEquals(1, count(SecondRowTags.STRIP)); assertEquals(1, count(BottomTags.BAR))
        for (id in listOf("p1", "p2", "p3")) assertEquals("label $id back at full size", 1, count(SecondRowTags.label(id)))
    }

    @Test fun `scroll-hide off pins every bar while the page scrolls`() {
        show(policy = null, ui = MemoryNavUiStore(scrollHide = false))
        compose.waitForIdle()
        compose.onNodeWithTag("page").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertEquals(1, count(TopBarTags.BAR_SLOT)); assertEquals(1, count(SecondRowTags.STRIP)); assertEquals(1, count(BottomTags.BAR))
        for (id in listOf("p1", "p2", "p3")) assertEquals(1, count(SecondRowTags.label(id)))
    }

    @Test fun `Clean mode, the second row is gone, the first row is only the hamburger, the bottom nav is icons only and the drawer holds the three rows`() {
        show(policy = null, ui = MemoryNavUiStore(mode = NavUiMode.Clean))
        compose.waitForIdle()
        assertEquals("no second row", 0, count(SecondRowTags.STRIP))
        assertEquals("the hamburger", 1, count(TopBarTags.MENU))
        assertEquals("no AI icon in the row (it moved into the drawer)", 0, count(TopBarTags.AI))
        assertEquals("the bottom nav is there", 1, count(BottomTags.BAR))
        for (id in listOf("home", "mail")) assertEquals("no bottom label $id", 0, count(BottomTags.label(id)))
        compose.onNodeWithTag(TopBarTags.MENU).performClick()
        compose.waitForIdle()
        assertEquals("drawer", 1, count(TopBarTags.DRAWER))
        assertEquals("row 1: the page dropdown", 1, count(CleanDrawerTags.PAGE_DROPDOWN))
        assertEquals("row 2: the icons", 1, count(CleanDrawerTags.ACTIONS))
        for (a in NavQuickAction.entries) assertEquals("icon ${a.declared}", 1, count(CleanDrawerTags.action(a)))
        assertEquals("the Configs > General entry", 1, count(CleanDrawerTags.CONFIGS_GENERAL))
        assertEquals("the Configs > About entry", 1, count(CleanDrawerTags.CONFIGS_ABOUT))
    }

    @Test fun `Classic mode keeps the second row and the labelled bottom nav, and has no hamburger`() {
        show(policy = null)
        compose.waitForIdle()
        assertEquals(1, count(SecondRowTags.STRIP))
        assertEquals(0, count(TopBarTags.MENU))
        for (id in listOf("home", "mail")) assertEquals(1, count(BottomTags.label(id)))
    }
}
