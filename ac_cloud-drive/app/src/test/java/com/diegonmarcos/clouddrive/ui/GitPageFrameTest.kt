package com.diegonmarcos.clouddrive.ui

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.diegonmarcos.clouddrive.sync.GitPageFrame
import com.diegonmarcos.superapp.bottomnav.BottomNavTags
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
 * The Git page's frame inside the REAL shell: the page's last item (the History section, the last thing under Local) must end
 * fully above the shared bottom bar, in every scroll state: at rest, scrolled to the end, with the chrome collapsed by a swipe
 * and with it restored again. The body is page-shaped: GitPageFrame (the same frame GitReposScreen scrolls in), many rows, then
 * the last item.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GitPageFrameTest {
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

    private val rows = (1..120).map { "repo-$it" }

    private fun open(rowCount: Int) {
        compose.setContent {
            DriveTheme {
                DriveShell(select = "git") { _, _ ->
                    GitPageFrame(refreshing = false, onRefresh = {}) {
                        items(rows.take(rowCount), key = { it }) { Text(it, Modifier.fillMaxWidth().heightIn(min = 48.dp)) }
                        item(key = "last") { Text("History", Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(LAST)) }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertLastAboveBar(state: String) {
        val last = compose.onNodeWithTag(LAST).fetchSemanticsNode().boundsInRoot
        val bar = compose.onNodeWithTag(BottomNavTags.ISLAND).fetchSemanticsNode().boundsInRoot
        assertTrue("$state: the last item ($last) must end above the bottom bar ($bar)", last.bottom <= bar.top + 1f)
        assertTrue("$state: the last item must start on screen ($last)", last.top >= 0f)
    }

    private fun scrollToEnd(rowCount: Int) { compose.onNodeWithTag(DriveTags.SYNC_GIT_LIST).performScrollToIndex(rowCount); compose.waitForIdle() }

    @Test fun `a short page shows its last item above the bottom bar at rest`() {
        open(3)
        assertLastAboveBar("short page")
    }

    @Test fun `a long page scrolled to the end shows its last item above the bottom bar`() {
        open(rows.size)
        scrollToEnd(rows.size)
        assertLastAboveBar("scrolled to the end")
    }

    @Test fun `the last item stays above the bar while the chrome is collapsed and after it restores`() {
        open(rows.size)
        repeat(3) { compose.onNodeWithTag(DriveTags.SYNC_GIT_LIST).performTouchInput { swipeUp() } }
        compose.waitForIdle()
        scrollToEnd(rows.size)
        assertLastAboveBar("chrome collapsed")
        repeat(3) { compose.onNodeWithTag(DriveTags.SYNC_GIT_LIST).performTouchInput { swipeDown() } }
        compose.waitForIdle()
        scrollToEnd(rows.size)
        assertLastAboveBar("chrome restored")
    }

    @Test fun `#1257 with the nav EXPANDED the last row is reachable after a scroll that collapsed it`() {
        open(rows.size)
        scrollToEnd(rows.size)
        val listBefore = compose.onNodeWithTag(DriveTags.SYNC_GIT_LIST).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag(DriveTags.SYNC_GIT_LIST).performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithTag(DriveTags.SYNC_GIT_LIST).performTouchInput { swipeDown() }
        compose.waitForIdle()
        scrollToEnd(rows.size)
        assertTrue("the nav is expanded again (every label shown)", compose.onAllNodesWithTag(BottomNavTags.label("git"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodesWithTag(BottomNavTags.ISLAND).fetchSemanticsNodes().isNotEmpty())
        val listAfter = compose.onNodeWithTag(DriveTags.SYNC_GIT_LIST).fetchSemanticsNode().boundsInRoot
        assertEquals("the list's viewport keeps its size through collapse and restore", listBefore.bottom, listAfter.bottom, 1f)
        assertLastAboveBar("nav expanded after a collapse")
    }

    private companion object { const val LAST = "git_frame_last_item" }
}
