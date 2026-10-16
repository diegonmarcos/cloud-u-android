package com.diegonmarcos.cloudme

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.bottomnav.BottomNavTags
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * #531 — Cloud Me's bottom nav is the fleet's Compose island, MEASURED in the real shell body.
 *
 * Composes [MeBody] — the very column MainActivity puts the section above the island in — over
 * build.json's bar sections (through Sections), and drives it the way a finger would. The island's own
 * geometry, colours and insets are measured where they live, in libs:bottomnav's BottomNavIslandTest;
 * what is Cloud Me's own is asserted here: WHICH sections, in what order, which one wears the pill,
 * what a tap does, and that scrolling the page collapses the bar (#673).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeBottomNavTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    // The app manifest declares no ComponentActivity; register it instead of shipping
    // ui-test-manifest into the APK.
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

    private val opened = mutableListOf<String>()
    private val launched = mutableListOf<String>()
    private var selected by mutableStateOf<String?>(null)

    private val bar get() = Sections.bottom()
    private val pages get() = bar.filter { it.target.isBlank() }

    /** [MeBody] as MainActivity composes it; [tall] puts a page taller than the viewport in the content slot. */
    private fun show(select: String?, tall: Boolean = false) {
        selected = select
        compose.setContent {
            CloudKitTheme(KitPalette(Color.Black, Color.DarkGray, Color.White, Color.LightGray, Color.Magenta, Color.Gray, Color.Black)) {
                MeBody(
                    selectedSectionId = selected,
                    onOpen = { opened += it; selected = it },
                    onTarget = { launched += it },
                ) {
                    if (tall) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag(PAGE_TAG)) {
                            Spacer(Modifier.height(PAGE_DP.dp))
                        }
                    } else {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /** How many of the bar's items currently draw a label node. */
    private fun labelCount() = bar.count {
        compose.onAllNodesWithTag(BottomNavTags.label(it.id), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun `the bar is build_json's bar sections, left to right in their order`() {
        show(pages.first().id)
        assertTrue("build.json declares no bar sections", bar.isNotEmpty())
        val xs = bar.map { bounds(BottomNavTags.item(it.id)).left }
        assertEquals("the items are not drawn in `order`, left to right: $xs", xs.sorted(), xs)
        for (s in bar) {
            compose.onNodeWithTag(BottomNavTags.label(s.id), useUnmergedTree = true).assertTextEquals(s.label)
        }
    }

    @Test
    fun `the page ends where the island begins`() {
        show(pages.first().id)
        val content = bounds(MeBottomNav.CONTENT_TAG)
        val island = bounds(BottomNavTags.ISLAND)
        assertTrue("the page ($content) runs under the island ($island)", content.bottom <= island.top + 1f)
    }

    @Test
    fun `the selected page wears the pill and nothing else does`() {
        val sel = pages.first().id
        show(sel)
        compose.onAllNodes(isSelected(), useUnmergedTree = true).assertCountEquals(1)
        compose.onNode(
            isSelected().and(hasTestTag(BottomNavTags.item(sel)).or(hasAnyAncestor(hasTestTag(BottomNavTags.item(sel))))),
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun `a page tap opens it and moves the pill, a launch tap leaves and the pill stays`() {
        val start = pages.first().id
        val next = pages.last().id
        val launch = bar.firstOrNull { it.target.isNotBlank() }
        show(start)
        compose.onNodeWithTag(BottomNavTags.item(next), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals("a tap on $next did not open it", listOf(next), opened)
        assertEquals("the pill did not move to $next", next, MeBottomNav.pillFor(selected))
        compose.onNode(
            isSelected().and(hasTestTag(BottomNavTags.item(next)).or(hasAnyAncestor(hasTestTag(BottomNavTags.item(next))))),
            useUnmergedTree = true,
        ).assertExists()
        if (launch != null) {
            compose.onNodeWithTag(BottomNavTags.item(launch.id), useUnmergedTree = true).performClick()
            compose.waitForIdle()
            assertEquals("a tap on ${launch.id} did not launch its target", listOf(launch.target), launched)
            assertEquals("a launch section was opened inside the app", listOf(next), opened)
            compose.onAllNodes(isSelected(), useUnmergedTree = true).assertCountEquals(1)
            compose.onNode(
                isSelected().and(hasTestTag(BottomNavTags.item(next)).or(hasAnyAncestor(hasTestTag(BottomNavTags.item(next))))),
                useUnmergedTree = true,
            ).assertExists()
        }
    }

    @Test
    fun `an off-bar section leaves no item lit`() {
        val offBar = Sections.all().first { s -> bar.none { it.id == s.id } }.id
        assertEquals("an off-bar section must light nothing", null, MeBottomNav.pillFor(offBar))
        show(offBar)
        compose.onAllNodes(isSelected(), useUnmergedTree = true).assertCountEquals(0)
        compose.runOnUiThread { selected = pages.first().id }
        compose.waitForIdle()
        compose.onAllNodes(isSelected(), useUnmergedTree = true).assertCountEquals(1)
    }

    /**
     * #673 the bar collapses to icons when the page scrolls, and returns when it scrolls back.
     *
     * NOT "the collapse parameter exists": this scrolls the content slot MeBody was handed and
     * asserts the RENDERED bar lost its labels, which needs a live nested-scroll driver on that slot.
     */
    @Test
    fun `scrolling the page collapses the bar to icons, and scrolling up brings the labels back`() {
        show(pages.first().id, tall = true)
        assertTrue("no labels are drawn before any scroll", labelCount() > 0)
        val expanded = bounds(BottomNavTags.ISLAND).height

        compose.onNodeWithTag(PAGE_TAG).performTouchInput { swipeUp() }
        compose.waitForIdle()

        assertEquals("labels survived the collapse", 0, labelCount())
        val collapsed = bounds(BottomNavTags.ISLAND).height
        assertTrue(
            "the collapse did not shrink the island: $expanded px expanded, $collapsed px collapsed",
            collapsed < expanded - 1f,
        )

        compose.onNodeWithTag(PAGE_TAG).performTouchInput { swipeDown() }
        compose.waitForIdle()

        assertFalse("scrolling up left the bar without its labels", labelCount() == 0)
        assertEquals("the labels did not come back", bar.size, labelCount())
    }

    private companion object {
        const val PAGE_TAG = "me:page"
        const val PAGE_DP = 6000
    }
}
