package com.diegonmarcos.cloudc3

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.diegonmarcos.cloudc3.pages.TopologyModel
import com.diegonmarcos.cloudc3.pages.TopologyScreen
import com.diegonmarcos.cloudc3.pages.TopologyTags
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
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

/** Each topology page renders its tree over the fixture fleet, states what no endpoint provides, falls back to a list and pivots. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TopologyScreensComposeTest {

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

    private val palette = KitPalette(Color.Black, Color.DarkGray, Color.White, Color.LightGray, Color.Cyan, Color.Gray, Color.Black)
    private val labels = TopologyModel.ALL.associateWith { it.replaceFirstChar { c -> c.uppercase() } }
    private val inputs = TopologyFixtures.inputs.copy(
        decl = TopologyModel.Decl(
            TopologyModel.ALL.map { TopologyModel.ViewDecl(it, "grouped by $it", "metric of $it", "question of $it", listOf("missing of $it")) },
            TopologyFixtures.inputs.decl.release,
        ),
    )

    private fun show(view: String, focus: String? = null, onPivot: (String, String) -> Unit = { _, _ -> }, onFocus: (String?) -> Unit = {}) {
        compose.setContent { CloudKitTheme(palette) { TopologyScreen(view, inputs, focus, labels, onFocus, onPivot) } }
    }

    /** The compose rule takes one setContent per test, so each view is its own test. */
    private fun drawsTreeAndMissing(view: String) {
        show(view)
        compose.onNodeWithTag(TopologyTags.screen(view)).assertExists()
        compose.onNodeWithTag(TopologyTags.missing(view)).assertExists()
        val top = TopologyModel.build(view, inputs).first()
        compose.onNodeWithTag(TopologyTags.row(view, top.id)).assertExists()
    }

    @Test fun `service view draws its tree and prints what no endpoint provides`() = drawsTreeAndMissing(TopologyModel.SERVICE)
    @Test fun `infrastructure view draws its tree and prints what no endpoint provides`() = drawsTreeAndMissing(TopologyModel.INFRASTRUCTURE)
    @Test fun `orchestration view draws its tree and prints what no endpoint provides`() = drawsTreeAndMissing(TopologyModel.ORCHESTRATION)
    @Test fun `network view draws its tree and prints what no endpoint provides`() = drawsTreeAndMissing(TopologyModel.NETWORK)
    @Test fun `release view draws its tree and prints what no endpoint provides`() = drawsTreeAndMissing(TopologyModel.RELEASE)

    @Test
    fun `the list fallback shows every leaf`() {
        show(TopologyModel.INFRASTRUCTURE)
        compose.onNodeWithTag(TopologyTags.mode(TopologyModel.INFRASTRUCTURE, "list")).performClick()
        TopologyModel.flatten(TopologyModel.build(TopologyModel.INFRASTRUCTURE, inputs)).forEach {
            compose.onNodeWithTag(TopologyTags.row(TopologyModel.INFRASTRUCTURE, it.node.id)).assertExists()
        }
    }

    @Test
    fun `tapping a service offers the other four views and a pivot reports the entity`() {
        var got: Pair<String, String>? = null
        show(TopologyModel.SERVICE, onPivot = { v, k -> got = v to k })
        val web = TopologyModel.build(TopologyModel.SERVICE, inputs)[0].children.first { it.label == "web" }
        compose.onAllNodesWithTag(TopologyTags.pivot(TopologyModel.SERVICE, TopologyModel.RELEASE)).assertCountEquals(0)
        compose.onNodeWithTag(TopologyTags.row(TopologyModel.SERVICE, web.id)).performClick()
        TopologyModel.ALL.filter { it != TopologyModel.SERVICE }.forEach {
            compose.onNodeWithTag(TopologyTags.pivot(TopologyModel.SERVICE, it)).assertExists()
        }
        compose.onNodeWithTag(TopologyTags.pivot(TopologyModel.SERVICE, TopologyModel.RELEASE)).performClick()
        assertEquals(TopologyModel.RELEASE to TopologyModel.Keys.service("web"), got)
    }

    @Test
    fun `a focused view shows only the entity and offers to show all`() {
        var cleared = false
        show(TopologyModel.ORCHESTRATION, focus = TopologyModel.Keys.service("web"), onFocus = { cleared = it == null })
        compose.onNodeWithTag(TopologyTags.FOCUS).assertExists()
        val orphanless = TopologyModel.walk(TopologyModel.focus(TopologyModel.build(TopologyModel.ORCHESTRATION, inputs), TopologyModel.Keys.service("web")))
        assertTrue(orphanless.none { it.first.label == "orphan" })
        compose.onNodeWithTag(TopologyTags.FOCUS_CLEAR).performClick()
        assertTrue(cleared)
    }
}
