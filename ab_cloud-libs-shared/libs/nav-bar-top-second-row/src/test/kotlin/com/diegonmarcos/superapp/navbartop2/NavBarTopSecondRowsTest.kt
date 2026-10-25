package com.diegonmarcos.superapp.navbartop2

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.NavGroup
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.NavSection
import com.diegonmarcos.superapp.bottomnav.SecondRowMode
import com.diegonmarcos.superapp.navbarbottom.BottomSpec
import com.diegonmarcos.superapp.themebg.ThemeBackground
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavBarTopSecondRowsTest {
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

    private fun p(id: String) = NavPage(id, "Page $id", "folder")
    private val plan = NavGroup("plan", "Plan", (1..12).map { p("a$it") }, "ic")
    private val trackers = NavGroup("trackers", "Trackers", (1..5).map { p("t$it") }, "ic")
    private val health = NavSection("health", "Health", "heart", plan.pages + trackers.pages, rows = SecondRowMode.ManyRows, groups = listOf(plan, trackers))

    @Test fun `the declaration reads nav_mode and groups that reference the section's flat pages`() {
        val json = """[{"id":"health","label":"Health","icon":"heart","nav_mode":"many_rows",
            "pages":[{"id":"a1","label":"A1","icon":"i"},{"id":"a2","label":"A2","icon":"i"},{"id":"t1","label":"T1","icon":"i"}],
            "groups":[{"id":"trackers","label":"Trackers","icon":"g","pages":["t1"]},{"id":"plan","label":"Plan","icon":"g","pages":["a2","a1"]}]},
            {"id":"me","label":"Me","icon":"i","pages":[{"id":"x","label":"X","icon":"i"}]}]"""
        val d = NavDecl.parse(json)
        val h = d.section("health")!!
        assertEquals(SecondRowMode.ManyRows, h.rows)
        assertEquals("pages stays the flat list", listOf("a1", "a2", "t1"), h.pages.map { it.id })
        assertEquals(listOf("trackers", "plan"), h.groups.map { it.id })
        assertEquals("group order is the declared id order", listOf("a2", "a1"), h.groups[1].pages.map { it.id })
        assertEquals("g", h.groups[0].icon)
        val me = d.section("me")!!
        assertEquals("one_row is the default", SecondRowMode.OneRow, me.rows)
        assertEquals("no groups: one group named like the section", listOf("me"), me.rowGroups().map { it.id })
        assertEquals(null, SecondRowMode.of("nonsense"))
        // a page no group names still gets a row (the guard rejects the declaration, the phone keeps it reachable)
        val loose = NavSection("s", "S", "i", listOf(p("u"), p("v")), rows = SecondRowMode.ManyRows, groups = listOf(NavGroup("g", "G", listOf(p("u")))))
        assertEquals(listOf("g", "s"), loose.rowGroups().map { it.id })
        assertEquals(listOf("v"), loose.rowGroups().last().pages.map { it.id })
    }

    @Test fun `many_rows draws one row per group, each label | its icon chips, selected highlighted`() {
        compose.setContent { NavBarTopSecondRows(health, "t2", {}, dark = true, underTopChrome = false) }
        compose.onNodeWithTag(SecondRowGroupTags.row("plan")).assertIsDisplayed()
        compose.onNodeWithTag(SecondRowGroupTags.row("trackers")).assertIsDisplayed()
        for (g in listOf("plan", "trackers")) {
            compose.onNodeWithTag(SecondRowGroupTags.index(g)).assertIsDisplayed()
            compose.onNodeWithTag(SecondRowGroupTags.divider(g)).assertIsDisplayed()
        }
        val plan = compose.onNodeWithTag(SecondRowGroupTags.row("plan")).fetchSemanticsNode().boundsInRoot
        val trk = compose.onNodeWithTag(SecondRowGroupTags.row("trackers")).fetchSemanticsNode().boundsInRoot
        assertTrue("one row under the other", plan.bottom <= trk.top + 0.5f)
        // label, then divider, then chips, left to right
        val idx = compose.onNodeWithTag(SecondRowGroupTags.index("trackers")).fetchSemanticsNode().boundsInRoot
        val div = compose.onNodeWithTag(SecondRowGroupTags.divider("trackers")).fetchSemanticsNode().boundsInRoot
        val chip = compose.onNodeWithTag(SecondRowTags.tab("t1")).fetchSemanticsNode().boundsInRoot
        assertTrue("index | chips", idx.right <= div.left + 0.5f && div.right <= chip.left + 0.5f)
        compose.onNodeWithTag(SecondRowTags.tab("t2")).assertIsSelected()
        compose.onNodeWithTag(SecondRowTags.label("t2"), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(SecondRowTags.label("t1"), useUnmergedTree = true).assertDoesNotExist()
        // the plan row's 12 chips scroll sideways instead of clipping the row
        assertTrue("the row stays inside the screen", plan.right <= 360f)
    }

    @Test fun `the group label opens a bounded index of that group's pages and a pick selects it`() {
        var picked: NavPage? = null
        compose.setContent { NavBarTopSecondRows(health, "a1", { picked = it }, dark = true, underTopChrome = false) }
        compose.onNodeWithTag(SecondRowGroupTags.index("plan")).performClick()
        compose.onNodeWithTag(SecondRowGroupTags.DROPDOWN).assertIsDisplayed()
        val h = compose.onNodeWithTag(SecondRowGroupTags.DROPDOWN).fetchSemanticsNode().boundsInRoot.height
        assertTrue("the 12-page index is height-bounded: $h", h <= SecondRowGroupDropdownMax.value + 1f)
        compose.onNodeWithTag(SecondRowGroupTags.item("a3")).performScrollTo().performClick()
        assertEquals("a3", picked?.id)
        compose.onNodeWithTag(SecondRowGroupTags.DROPDOWN).assertDoesNotExist()
        // the other group's index lists only its own pages
        compose.onNodeWithTag(SecondRowGroupTags.index("trackers")).performClick()
        compose.onNodeWithTag(SecondRowGroupTags.item("t1")).assertExists()
        compose.onNodeWithTag(SecondRowGroupTags.item("a3")).assertDoesNotExist()
    }

    @Test fun `many_rows compacts with the scroll collapse like the one row`() {
        compose.setContent { NavBarTopSecondRows(health, "a1", {}, dark = true, underTopChrome = false, compact = true) }
        compose.waitForIdle()
        assertEquals(SecondRowSpec.COMPACT_TAB_HEIGHT.value, compose.onNodeWithTag(SecondRowTags.tab("a1")).fetchSemanticsNode().boundsInRoot.height, 0.5f)
    }

    @Test fun `one_row (the default) is today's single strip over every page`() {
        val one = NavSection("health", "Health", "heart", plan.pages, groups = listOf(plan))
        assertEquals(SecondRowMode.OneRow, one.rows)
        compose.setContent { NavBarTopSecondRows(one, "a1", {}, dark = true, underTopChrome = false) }
        compose.onNodeWithTag(SecondRowTags.STRIP).assertIsDisplayed()
        compose.onNodeWithTag(SecondRowGroupTags.row("plan")).assertDoesNotExist()
        compose.onNodeWithTag(SecondRowGroupTags.index("plan")).assertDoesNotExist()
        compose.onNodeWithTag(SecondRowTags.label("a2"), useUnmergedTree = true).assertExists()
    }
}
