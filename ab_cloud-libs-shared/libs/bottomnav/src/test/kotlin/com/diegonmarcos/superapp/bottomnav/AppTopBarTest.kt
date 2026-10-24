package com.diegonmarcos.superapp.bottomnav

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * AppTopBar, DRIVEN: the real bar under Robolectric (mdpi, so px == dp). The declaration it reads, the drawer a
 * page supplies, the AI panel, the Settings dropdown, the Cloud Account launch intent, the Configs callback, the
 * accessibility contract (labels, button role, 48dp targets, a heading title centred in the bar), and the bar
 * sitting with the page tabs and the bottom island. The test only talks to the bar through its tags and labels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppTopBarTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager)
                    .addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    private val full = TopBarDecl(drawer = true, ai = true, settings = listOf("account", "configs"))

    private var accountTaps = 0
    private var configsTaps = 0
    private var page by mutableStateOf("a")

    private fun show(decl: TopBarDecl = full, realAccount: Boolean = false, content: @androidx.compose.runtime.Composable () -> Unit = {}) {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val account: () -> Unit = if (realAccount) rememberAccountLauncher() else ({ accountTaps++ })
                AppTopBarScaffold(
                    decl = decl,
                    title = "Cloud Store",
                    onAccount = account,
                    onConfigs = { configsTaps++ },
                    aiPanel = { close ->
                        Column {
                            Text("the shared chat", Modifier.testTag("ai_body"))
                            TextButton(close, Modifier.testTag("ai_close")) { Text("Close") }
                        }
                    },
                    content = content,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    private fun exists(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    // ── the declaration ───────────────────────────────────────────────────────────────────

    private fun b64(json: String) = Base64.getEncoder().encodeToString(json.toByteArray())

    @Test
    fun `the declaration reads drawer, ai and settings from build json`() {
        val d = TopBarDecl.fromBuildConfig(b64("""{"drawer":true,"ai":true,"settings":["account","configs"]}"""))
        assertEquals(full, d)
        assertTrue(d!!.problems().isEmpty())
    }

    @Test
    fun `no top_bar is no bar, a malformed one is no bar, never a crash`() {
        assertNull(TopBarDecl.fromBuildConfig(""))
        assertNull(TopBarDecl.fromBuildConfig("not base64 !!"))
        assertNull(TopBarDecl.fromBuildConfig(b64("[1,2]")))
        assertNull(TopBarDecl.fromBuildConfig(b64("{\"drawer\":")))
    }

    @Test
    fun `a missing flag is off, and a declaration that says nothing or names an unknown entry has problems`() {
        assertEquals(TopBarDecl(drawer = true), TopBarDecl.parse("""{"drawer":true}"""))
        assertTrue(TopBarDecl().problems().isNotEmpty())
        assertTrue(TopBarDecl(settings = listOf("account", "logout")).problems().any { "logout" in it })
        assertTrue(TopBarDecl(settings = listOf("account", "account")).problems().isNotEmpty())
    }

    // ── what is drawn follows what is declared ───────────────────────────────────────────

    @Test
    fun `the full declaration draws hamburger, centred title, AI and Settings`() {
        show()
        compose.onNodeWithTag(TopBarTags.MENU).assertIsDisplayed()
        compose.onNodeWithTag(TopBarTags.TITLE).assertIsDisplayed().assertTextEquals("Cloud Store")
        compose.onNodeWithTag(TopBarTags.AI).assertIsDisplayed()
        compose.onNodeWithTag(TopBarTags.SETTINGS).assertIsDisplayed()
        val bar = compose.onNodeWithTag(TopBarTags.BAR).fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithTag(TopBarTags.TITLE).fetchSemanticsNode().boundsInRoot
        val menu = compose.onNodeWithTag(TopBarTags.MENU).fetchSemanticsNode().boundsInRoot
        val ai = compose.onNodeWithTag(TopBarTags.AI).fetchSemanticsNode().boundsInRoot
        val settings = compose.onNodeWithTag(TopBarTags.SETTINGS).fetchSemanticsNode().boundsInRoot
        assertEquals("the title is centred in the bar", bar.center.x, title.center.x, 1f)
        assertTrue("the hamburger is at the left", menu.right <= bar.center.x && menu.left <= 8f)
        assertTrue("AI and Settings are at the right, AI first", ai.left >= bar.center.x && settings.left >= ai.right - 1f)
        assertEquals("the right-hand pair ends at the bar's edge", bar.right, settings.right + 4f, 1f)
    }

    @Test
    fun `only what is declared is drawn`() {
        show(TopBarDecl(ai = true))
        assertFalse("no hamburger without drawer", exists(TopBarTags.MENU))
        assertFalse("no Settings without settings entries", exists(TopBarTags.SETTINGS))
        compose.onNodeWithTag(TopBarTags.AI).assertIsDisplayed()
    }

    // ── the drawer: the page supplies it ──────────────────────────────────────────────────

    @Test
    fun `the hamburger opens the drawer with the current page's content, and a tap outside closes it`() {
        show {
            TopBarDrawer { _ -> Text("Categories", Modifier.testTag("drawer_text")) }
        }
        assertFalse("closed until asked", exists(TopBarTags.DRAWER))
        tap(TopBarTags.MENU)
        compose.onNodeWithTag(TopBarTags.DRAWER).assertIsDisplayed()
        compose.onNodeWithTag("drawer_text").assertIsDisplayed().assertTextEquals("Categories")
        compose.onNodeWithTag(TopBarTags.SCRIM).performTouchInput { click(Offset(width - 4f, height / 2f)) }
        compose.waitForIdle()
        assertFalse("the scrim closes it", exists(TopBarTags.DRAWER))
    }

    @Test
    fun `the drawer follows the page, and the page can close it`() {
        page = "a"
        show {
            if (page == "a") TopBarDrawer { close ->
                Text("Cloud filters", Modifier.testTag("drawer_a"))
                TextButton(close, Modifier.testTag("drawer_done")) { Text("Done") }
            } else TopBarDrawer { _ -> Text("Feed filters", Modifier.testTag("drawer_b")) }
        }
        tap(TopBarTags.MENU)
        compose.onNodeWithTag("drawer_a").assertIsDisplayed()
        tap("drawer_done")
        assertFalse("the page's own close button closes it", exists(TopBarTags.DRAWER))
        compose.runOnIdle { page = "b" }
        tap(TopBarTags.MENU)
        compose.onNodeWithTag("drawer_b").assertIsDisplayed()
        assertFalse("the previous page's content is gone", exists("drawer_a"))
    }

    @Test
    fun `Back closes the drawer`() {
        show { TopBarDrawer { _ -> Text("x") } }
        tap(TopBarTags.MENU)
        assertTrue(exists(TopBarTags.DRAWER))
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertFalse(exists(TopBarTags.DRAWER))
    }

    // ── the AI panel ──────────────────────────────────────────────────────────────────────

    @Test
    fun `AI opens an anchored panel that hosts the slot, and the slot can close it`() {
        show()
        assertFalse(exists(TopBarTags.AI_PANEL))
        tap(TopBarTags.AI)
        compose.onNodeWithTag(TopBarTags.AI_PANEL).assertIsDisplayed()
        compose.onNodeWithTag("ai_body").assertIsDisplayed().assertTextEquals("the shared chat")
        tap("ai_close")
        assertFalse("the panel's close hands control back", exists(TopBarTags.AI_PANEL))
    }

    @Test
    fun `opening Settings closes the AI panel and the other way round`() {
        show()
        tap(TopBarTags.AI)
        assertTrue(exists(TopBarTags.AI_PANEL))
        tap(TopBarTags.SETTINGS)
        assertFalse(exists(TopBarTags.AI_PANEL))
        compose.onNodeWithTag(TopBarTags.settingsItem("configs")).assertIsDisplayed()
    }

    // ── the Settings dropdown ─────────────────────────────────────────────────────────────

    @Test
    fun `Settings lists exactly the declared entries`() {
        show()
        tap(TopBarTags.SETTINGS)
        compose.onNodeWithTag(TopBarTags.settingsItem("account")).assertIsDisplayed().assertTextEquals("Account")
        compose.onNodeWithTag(TopBarTags.settingsItem("configs")).assertIsDisplayed().assertTextEquals("Configs")
    }

    @Test
    fun `a Settings with only Configs declared has no Account`() {
        show(TopBarDecl(settings = listOf("configs")))
        tap(TopBarTags.SETTINGS)
        compose.onNodeWithTag(TopBarTags.settingsItem("configs")).assertIsDisplayed()
        assertFalse(exists(TopBarTags.settingsItem("account")))
    }

    @Test
    fun `Configs calls the host's callback and closes the menu`() {
        configsTaps = 0
        show()
        tap(TopBarTags.SETTINGS)
        tap(TopBarTags.settingsItem("configs"))
        assertEquals(1, configsTaps)
        assertFalse("the menu closes", exists(TopBarTags.settingsItem("configs")))
        assertEquals("and Account was not called", 0, accountTaps)
    }

    @Test
    fun `Account calls the host's account action`() {
        accountTaps = 0
        show()
        tap(TopBarTags.SETTINGS)
        tap(TopBarTags.settingsItem("account"))
        assertEquals(1, accountTaps)
    }

    @Test
    fun `Account launches Cloud Account by its OPEN intent, pinned to its package`() {
        show(realAccount = true)
        tap(TopBarTags.SETTINGS)
        tap(TopBarTags.settingsItem("account"))
        val started = shadowOf(compose.activity).nextStartedActivity
        assertNotNull("Account started an activity", started)
        assertEquals("com.diegonmarcos.cloudaccount.OPEN", started.action)
        assertEquals("com.diegonmarcos.cloudaccount", started.`package`)
        assertTrue("launched as a new task", started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(AccountLaunch.intent().action, started.action)
    }

    @Test
    fun `an app that is not installed is a false return, not a crash`() {
        val refusing = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun startActivity(intent: Intent) {
                throw android.content.ActivityNotFoundException("no cloud account")
            }
        }
        assertFalse(AccountLaunch.open(refusing))
    }

    // ── accessibility ─────────────────────────────────────────────────────────────────────

    @Test
    fun `every button is announced by its label as a button and is a 48dp target, the title is a heading`() {
        show()
        for ((label, tag) in listOf("Open menu" to TopBarTags.MENU, "AI" to TopBarTags.AI, "Settings" to TopBarTags.SETTINGS)) {
            val node = compose.onNodeWithContentDescription(label)
            node.assertHasClickAction()
            node.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            val b = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$label is at least 48dp wide and tall (${b.width} x ${b.height})", b.width >= 48f && b.height >= 48f)
        }
        compose.onNodeWithTag(TopBarTags.TITLE).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun `the open drawer is a named pane and its scrim says what it does`() {
        show { TopBarDrawer { _ -> Text("x") } }
        tap(TopBarTags.MENU)
        compose.onNodeWithTag(TopBarTags.DRAWER).assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Menu"))
        compose.onNodeWithContentDescription("Close menu").assertHasClickAction()
    }

    // ── beside the page tabs and the island ───────────────────────────────────────────────

    @Test
    fun `the bar sits above the page tabs, and the bottom island is still there`() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val icon = rememberVectorPainter(Icons.Filled.Settings)
                AppTopBarScaffold(full, "Cloud Store", onAccount = {}, onConfigs = {}) {
                    BottomNavHost(
                        entries = listOf(BottomNavEntry("one", "One", icon), BottomNavEntry("two", "Two", icon)),
                        selectedId = "one",
                        onSelect = {},
                    ) {
                        Column {
                            PageTabs(listOf(NavPage("a", "Alpha", "home"), NavPage("b", "Beta", "folder")), "a", onSelect = {}, underTopChrome = false)
                            Text("page", Modifier.testTag("page_body"))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val bar = compose.onNodeWithTag(TopBarTags.BAR).fetchSemanticsNode().boundsInRoot
        val tabs = compose.onNodeWithTag(PageTabsTags.tab("a"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val island = compose.onNodeWithTag(TAG_ISLAND, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("page tabs start below the bar (${bar.bottom} <= ${tabs.top})", bar.bottom <= tabs.top)
        assertTrue("the island is below the tabs", tabs.bottom <= island.top)
        compose.onNodeWithTag(PageTabsTags.tab("a"), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("page_body").assertIsDisplayed()
        compose.onNodeWithTag(itemTag("two"), useUnmergedTree = true).assertIsDisplayed()
    }

    // ── #1149 inline actions: AI, Profile and Configs as three round buttons ──────────────────

    private val inline = TopBarDecl(drawer = true, settings = listOf("account", "configs"), inlineActions = true)

    @Test
    fun `inline_actions is read from build json and a bar that declares only it is valid`() {
        val d = TopBarDecl.fromBuildConfig(b64("""{"drawer":true,"inline_actions":true,"settings":["account","configs"]}"""))
        assertEquals(inline, d)
        assertTrue(d!!.problems().isEmpty())
        assertTrue(TopBarDecl(inlineActions = true).problems().isEmpty())
        assertFalse(TopBarDecl.parse("""{"drawer":true}""")!!.inlineActions)
    }

    @Test
    fun `inline draws the hamburger at the left and AI, Profile, Configs at the right in that order with no Settings button`() {
        show(inline)
        assertTrue(exists(TopBarTags.MENU))
        assertFalse("no Settings dropdown button", exists(TopBarTags.SETTINGS))
        val bar = compose.onNodeWithTag(TopBarTags.BAR).fetchSemanticsNode().boundsInRoot
        val x = listOf(TopBarTags.AI, TopBarTags.ACCOUNT, TopBarTags.CONFIGS).map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        assertTrue("AI, Profile, Configs run left to right: $x", x.zipWithNext().all { (a, b) -> a.right <= b.left })
        assertTrue("all three sit in the right half: $x", x.all { it.left > bar.width / 2 && it.right <= bar.right })
        assertTrue(compose.onNodeWithTag(TopBarTags.MENU).fetchSemanticsNode().boundsInRoot.right < bar.width / 2)
    }

    @Test
    fun `inline Profile and Configs call the host and AI calls onAi when given`() {
        var ai = 0
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AppTopBarScaffold(decl = inline, title = "Cloud Drive", onAccount = { accountTaps++ }, onConfigs = { configsTaps++ }, onAi = { ai++ }, content = {})
            }
        }
        compose.waitForIdle()
        val a = accountTaps; val c = configsTaps
        tap(TopBarTags.ACCOUNT); tap(TopBarTags.CONFIGS); tap(TopBarTags.AI)
        assertEquals(a + 1, accountTaps)
        assertEquals(c + 1, configsTaps)
        assertEquals(1, ai)
        assertFalse("onAi given: no anchored panel", exists(TopBarTags.AI_PANEL))
    }

    @Test
    fun `inline entries follow settings and AI opens the panel when the host gives no onAi`() {
        show(TopBarDecl(drawer = true, settings = listOf("configs"), inlineActions = true))
        assertFalse(exists(TopBarTags.ACCOUNT))
        assertTrue(exists(TopBarTags.CONFIGS))
        tap(TopBarTags.AI)
        assertTrue(exists(TopBarTags.AI_PANEL))
    }
}
