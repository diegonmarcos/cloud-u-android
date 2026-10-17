package com.diegonmarcos.cloudbrowser

import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.diegonmarcos.superapp.browser.BrowserAddressOverlay
import com.diegonmarcos.superapp.browser.SuggestState
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import org.junit.Assert.assertEquals
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

/** Brave-style address focus: a tap opens a search box and, under it, the current address with Copy and Edit. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserAddressOverlayUiTest {

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

    private val palette = KitPalette.fromArgb(0xFF1A0033.toInt(), 0xFF2D0A55.toInt(), 0xFFFFFFFF.toInt(), 0x99FFFFFF.toInt(), 0xFF7C3AED.toInt(), 0x33FFFFFF, 0xFF000000.toInt())
    private val url = "https://example.com/a/long/path?q=1"
    private val copied = mutableListOf<String>()
    private var dismissed = 0

    private fun show() {
        compose.setContent {
            CloudKitTheme(palette) {
                BrowserAddressOverlay("Example Domain", url, "Search DuckDuckGo, or type a URL", SuggestState(),
                    onQuery = {}, onSubmit = {}, onPick = {}, onCopied = { copied += it }, onDismiss = { dismissed++ })
            }
        }
        compose.waitForIdle()
    }

    private fun fieldText(): String =
        compose.onNodeWithTag("browser:address:search").fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test
    fun `a tap shows two boxes - an empty search and the current address`() {
        show()
        compose.onNodeWithTag("browser:address:search").assertIsDisplayed()
        compose.onNodeWithTag("browser:address:current").assertIsDisplayed()
        compose.onNodeWithTag("browser:address:copy").assertIsDisplayed()
        compose.onNodeWithTag("browser:address:edit").assertIsDisplayed()
        assertEquals("", fieldText())
    }

    @Test
    fun `Copy puts the URL on the clipboard`() {
        show()
        compose.onNodeWithTag("browser:address:copy").performClick()
        compose.waitForIdle()
        val cm = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(url, cm.primaryClip?.getItemAt(0)?.text?.toString())
        assertEquals(listOf(url), copied)
    }

    @Test
    fun `Edit fills the search box with the URL, all selected`() {
        show()
        compose.onNodeWithTag("browser:address:edit").performClick()
        compose.waitForIdle()
        assertEquals(url, fieldText())
        val sel = compose.onNodeWithTag("browser:address:search").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertEquals(0, sel.start); assertEquals(url.length, sel.end)
    }

    @Test
    fun `a tap outside closes it`() {
        show()
        compose.onNodeWithTag("browser:address:scrim").performClick()
        compose.waitForIdle()
        assertEquals(1, dismissed)
    }
}
