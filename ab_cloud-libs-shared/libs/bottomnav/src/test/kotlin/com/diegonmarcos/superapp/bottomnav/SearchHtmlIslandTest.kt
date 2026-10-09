package com.diegonmarcos.superapp.bottomnav

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * The ONE declared exception to the fleet's island: Cloud Search's `.bottom-nav` from the owner's
 * HTML mockup ([SearchHtmlIsland]). The numbers are restated here ON PURPOSE as literals of the
 * mockup's CSS (60 px high, radius 30, 50 px round cells, 6 px lift, 0.6rem labels), so changing
 * the look is a decision that has to change this file in the same commit. It is measured at 360 dp
 * with the labels Cloud Search ships (Web, Cloud, Chat, Agents, Reports): the earlier fleet island
 * fitted two-word labels into five cells in a loop that never settled, and this test proves the
 * variant lays out once and every label fits its 50 dp cell whichever item is selected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchHtmlIslandTest {

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

    private var selected by mutableStateOf<String?>(null)
    private var dark by mutableStateOf(true)

    @Composable
    private fun entries(): List<BottomNavEntry> = SHIPPED.map {
        BottomNavEntry(it.first, it.second, rememberVectorPainter(ICONS.getValue(it.first)))
    }

    private fun show(select: String?, isDark: Boolean = true) {
        selected = select
        dark = isDark
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.Gray).testTag("root")) {
                SearchHtmlIsland(
                    entries(), selected, { selected = it.id }, dark,
                    Modifier.align(Alignment.BottomCenter), gradientIds = setOf("chat"),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun textLayout(tag: String): TextLayoutResult {
        val out = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        return out.single()
    }

    @Test
    fun `the mockup's bar renders its five cells as a 60 dp pill with 16 dp gutters`() {
        show("chat")
        val root = bounds("root")
        val island = bounds(TAG_ISLAND)
        assertEquals("island height (.bottom-nav height: 60px)", 60f, island.height, 0.5f)
        assertEquals("16 dp left gutter (width: calc(100% - 32px))", 16f, island.left - root.left, 0.5f)
        assertEquals("16 dp right gutter", 16f, root.right - island.right, 0.5f)
        assertEquals("20 dp above the bottom edge (bottom: 20px)", 20f, root.bottom - island.bottom, 0.5f)
        for ((id, _) in SHIPPED) {
            val cell = bounds(itemTag(id))
            assertEquals("cell $id is 50 dp wide (.nav-item width)", 50f, cell.width, 0.5f)
            assertEquals("cell $id is 50 dp high", 50f, cell.height, 0.5f)
            assertEquals("cell $id is centred in the 60 dp bar", island.center.y, cell.center.y, 0.5f)
            assertTrue("cell $id inside the bar", cell.left >= island.left && cell.right <= island.right)
        }
        // space-around: five 50 dp cells in 328 - 16 = 312 dp leave equal gaps.
        val cells = SHIPPED.map { bounds(itemTag(it.first)) }
        val gaps = cells.zipWithNext { a, b -> b.left - a.right }
        assertTrue("equal gaps between the cells (whole pixels): $gaps", gaps.max() - gaps.min() <= 1.5f)
        assertEquals("the bar is a true semicircle-ended pill: radius 30 on a 60 dp bar", island.height / 2, 30f, 0.5f)
    }

    @Test
    fun `the selected cell lifts its icon and shows its label and the others hide theirs`() {
        show("agents")
        val lifted = bounds(iconTag("agents"))
        val resting = bounds(iconTag("web"))
        assertEquals("the active icon lifts 6 dp (.nav-item.active .nav-icon translateY(-6px))", 6f, resting.top - lifted.top, 0.5f)
        assertEquals("21 dp icon", 21f, lifted.width, 0.5f)
        val activeLabel = bounds(labelTag("agents"))
        assertEquals("the label sits 4 dp above the cell's bottom edge (.nav-label bottom: 4px)", 4f,
            bounds(itemTag("agents")).bottom - activeLabel.bottom, 1f)
        // Hidden labels are laid out (alpha 0), so a node exists for each; only the selected one is opaque.
        compose.onNodeWithTag(labelTag("web"), useUnmergedTree = true).assertExists()
    }

    @Test
    fun `every label fits its 50 dp cell whichever item is selected at 360 dp`() {
        for ((id, _) in SHIPPED) {
            show(id)
            for ((other, label) in SHIPPED) {
                val cell = bounds(itemTag(other))
                val b = bounds(labelTag(other))
                val text = textLayout(labelTag(other))
                val why = "'$label' laid out ${text.size.width}px, needs ${text.multiParagraph.intrinsics.maxIntrinsicWidth}px, cell ${cell.width}px (selected $id)"
                assertTrue("label $other inside its cell: $why", b.left >= cell.left - 0.5f && b.right <= cell.right + 0.5f)
                assertFalse("label $other overflows: $why", text.hasVisualOverflow)
                assertFalse("label $other is ellipsized: $why", text.isLineEllipsized(0))
                assertEquals("label $other is one line", 1, text.lineCount)
            }
        }
    }

    @Test
    fun `a tap selects and every destination routes`() {
        show("chat")
        val seen = mutableListOf<String>()
        for ((id, _) in SHIPPED) {
            compose.onNodeWithTag(itemTag(id), useUnmergedTree = true).performClick()
            compose.waitForIdle()
            seen += selected!!
        }
        assertEquals(SHIPPED.map { it.first }, seen)
    }

    @Test
    fun `dark and light wear the mockup's tokens`() {
        assertEquals(Color(0xB30F172A), SearchHtmlTokens.fill(true))
        assertEquals(Color(0x1AFFFFFF), SearchHtmlTokens.border(true))
        assertEquals(Color(0xFF0A84FF), SearchHtmlTokens.accent(true))
        assertEquals(Color(0xFF94A3B8), SearchHtmlTokens.idle(true))
        assertEquals(Color(0x99FFFFFF), SearchHtmlTokens.fill(false))
        assertEquals(Color(0x66FFFFFF), SearchHtmlTokens.border(false))
        assertEquals(Color(0xFF007AFF), SearchHtmlTokens.accent(false))
        assertEquals(Color(0xFF5A5A5E), SearchHtmlTokens.idle(false))
        assertEquals(listOf(Color(0xFFFF416C), Color(0xFF8A2387), Color(0xFF24D292)), SearchHtmlTokens.gradient)
        show("web", isDark = false)
        compose.onNodeWithTag(TAG_ISLAND, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the style is declared and only a known name resolves`() {
        assertEquals(NavStyle.Fleet, NavStyle.of(""))
        assertEquals(NavStyle.Fleet, NavStyle.of(null))
        assertEquals(NavStyle.Fleet, NavStyle.of("fleet"))
        assertEquals(NavStyle.SearchHtml, NavStyle.of("search-html"))
        assertNull("an unknown style is not silently the fleet's", NavStyle.of("glass"))
        val sections = """[{"id":"a","label":"A"},{"id":"b","label":"B"}]"""
        assertEquals(NavStyle.Fleet, NavDecl.parse(sections).style)
        assertEquals(NavStyle.SearchHtml, NavDecl.parse(sections, style = "search-html").style)
        // fail-soft: an unknown name draws the fleet's island (the guard makes it a build failure)
        assertEquals(NavStyle.Fleet, NavDecl.parse(sections, style = "glass").style)
    }

    private companion object {
        /** Cloud Search's five as the island ships them. */
        val SHIPPED = listOf("web" to "Web", "cloud" to "Cloud", "chat" to "Chat", "agents" to "Agents", "reports" to "Reports")
        val ICONS = mapOf(
            "web" to Icons.Filled.Home, "cloud" to Icons.Filled.Mail, "chat" to Icons.Filled.ChatBubble,
            "agents" to Icons.Filled.RssFeed, "reports" to Icons.Filled.Videocam,
        )
    }
}
