package com.diegonmarcos.superapp.navbartop1

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.bottomnav.AppTopBarLabels
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.NavSection
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
class NavBarTopFirstRowTest {
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

    private val labels = AppTopBarLabels("Menu", "Close", "Drawer", "AI", "AI panel", "Settings", "Profile", "Configs")
    private val section = NavSection(
        "files", "Files", "folder",
        listOf(NavPage("recent", "Recent"), NavPage("shared", "Shared", pages = listOf(NavPage("links", "Links")))),
    )

    @Test fun `hierarchy lists the bottom page then its sub-pages depth first, the current path selected`() {
        val h = titleHierarchy(section, "links")
        assertEquals(listOf("files", "recent", "shared", "links"), h.map { it.id })
        assertEquals(listOf(0, 1, 1, 2), h.map { it.depth })
        assertEquals(listOf(false, false, true, true), h.map { it.selected })
        assertTrue(titleHierarchy(section, null).first().selected)
        assertTrue("bottom page selected on its own", titleHierarchy(section, "files").first().selected)
    }

    @Test fun `island max width leaves two equal slots`() {
        assertEquals(360.dp - TopFirstRowSpec.SLOT * 2, TopFirstRowSpec.islandMaxWidth(360.dp))
        assertEquals(0.dp, TopFirstRowSpec.islandMaxWidth(50.dp))
        assertEquals(TopFirstRowSpec.ICON_TARGET * 3, TopFirstRowSpec.SLOT)
    }

    private fun show(onNavigate: (HierarchyEntry) -> Unit = {}, onMenu: () -> Unit = {}) {
        compose.setContent {
            NavBarTopFirstRow(
                "Files", ThemeBackground.colors(true), labels, titleHierarchy(section, "recent"),
                onNavigate, onMenu, {}, {}, {},
            )
        }
    }

    @Test fun `the island is centred and the plain icons sit left and right`() {
        show()
        val row = compose.onNodeWithTag(TopFirstRowTags.ROW).fetchSemanticsNode().boundsInRoot
        val island = compose.onNodeWithTag(TopFirstRowTags.ISLAND).fetchSemanticsNode().boundsInRoot
        val rowCentre = (row.left + row.right) / 2
        val islandCentre = (island.left + island.right) / 2
        assertEquals("island centre vs row centre", rowCentre, islandCentre, 1.5f)
        val menu = compose.onNodeWithTag(TopFirstRowTags.MENU).fetchSemanticsNode().boundsInRoot
        val configs = compose.onNodeWithTag(TopFirstRowTags.CONFIGS).fetchSemanticsNode().boundsInRoot
        assertTrue(menu.right <= island.left && configs.left >= island.right)
        val ai = compose.onNodeWithTag(TopFirstRowTags.AI).fetchSemanticsNode().boundsInRoot
        val profile = compose.onNodeWithTag(TopFirstRowTags.PROFILE).fetchSemanticsNode().boundsInRoot
        assertTrue("AI, Profile, Configs left to right", ai.right <= profile.left + 0.5f && profile.right <= configs.left + 0.5f)
        // Plain icons: each target is exactly the declared touch size, i.e. no larger circle/pill around it.
        assertEquals(TopFirstRowSpec.ICON_TARGET.value, menu.width, 0.5f)
    }

    @Test fun `tapping the title opens the hierarchy and a tap navigates`() {
        var picked: HierarchyEntry? = null
        show(onNavigate = { picked = it })
        compose.onNodeWithTag(TopFirstRowTags.ISLAND).performClick()
        compose.onNodeWithTag(TopFirstRowTags.entry("links")).assertIsDisplayed()
        compose.onNodeWithTag(TopFirstRowTags.entry("shared")).performClick()
        assertEquals("shared", picked?.id)
    }

    @Test fun `the hamburger is a tap and calls the side menu`() {
        var menus = 0
        show(onMenu = { menus++ })
        compose.onNodeWithTag(TopFirstRowTags.MENU).performClick()
        assertEquals(1, menus)
    }
}
