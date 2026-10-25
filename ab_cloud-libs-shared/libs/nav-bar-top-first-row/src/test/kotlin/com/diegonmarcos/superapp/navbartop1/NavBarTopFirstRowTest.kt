package com.diegonmarcos.superapp.navbartop1

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
        listOf(NavPage("recent", "Recent", "history"), NavPage("shared", "Shared", "groups", pages = listOf(NavPage("links", "Links", "link")))),
    )

    @Test fun `hierarchy lists the bottom page then its sub-pages depth first, the current path selected`() {
        val h = titleHierarchy(section, "links")
        assertEquals(listOf("files", "recent", "shared", "links"), h.map { it.id })
        assertEquals(listOf(0, 1, 1, 2), h.map { it.depth })
        assertEquals(listOf(false, false, true, true), h.map { it.selected })
        assertTrue(titleHierarchy(section, null).first().selected)
        assertTrue("bottom page selected on its own", titleHierarchy(section, "files").first().selected)
    }

    @Test fun `island max width leaves the right slot`() {
        assertEquals(360.dp - TopFirstRowSpec.SLOT, TopFirstRowSpec.islandMaxWidth(360.dp))
        assertEquals(0.dp, TopFirstRowSpec.islandMaxWidth(50.dp))
        assertEquals(TopFirstRowSpec.ICON_TARGET * 3, TopFirstRowSpec.SLOT)
    }

    private fun show(
        onNavigate: (HierarchyEntry) -> Unit = {},
        extra: (@androidx.compose.runtime.Composable androidx.compose.foundation.layout.ColumnScope.(() -> Unit) -> Unit)? = null,
        leaf: String? = null,
    ) {
        compose.setContent {
            NavBarTopFirstRow(
                "Files", ThemeBackground.colors(true), labels, titleHierarchy(section, "recent"),
                onNavigate, {}, {}, {}, appName = "Cloud Files", sections = listOf(section, other), leaf = leaf, extra = extra,
            )
        }
    }

    private val other = NavSection("tools", "Tools", "build", listOf(NavPage("a", "A", "a")))

    @Test fun `no hamburger, the title is left aligned and the plain icons sit at the right`() {
        show()
        compose.onNodeWithTag("topbar_menu").assertDoesNotExist()
        val row = compose.onNodeWithTag(TopFirstRowTags.ROW).fetchSemanticsNode().boundsInRoot
        val island = compose.onNodeWithTag(TopFirstRowTags.ISLAND).fetchSemanticsNode().boundsInRoot
        assertTrue("title starts at the left edge: ${island.left} in ${row.left}", island.left - row.left < TopFirstRowSpec.ICON_TARGET.value)
        assertTrue("title is not centred", (island.left + island.right) / 2 < (row.left + row.right) / 2)
        val configs = compose.onNodeWithTag(TopFirstRowTags.CONFIGS).fetchSemanticsNode().boundsInRoot
        assertTrue(island.right <= configs.left)
        val ai = compose.onNodeWithTag(TopFirstRowTags.AI).fetchSemanticsNode().boundsInRoot
        val profile = compose.onNodeWithTag(TopFirstRowTags.PROFILE).fetchSemanticsNode().boundsInRoot
        assertTrue("AI, Profile, Configs left to right", ai.right <= profile.left + 0.5f && profile.right <= configs.left + 0.5f)
        // Plain icons: each target is exactly the declared touch size, i.e. no larger circle/pill around it.
        assertEquals(TopFirstRowSpec.ICON_TARGET.value, configs.width, 0.5f)
    }

    @Test fun `tapping the title opens the path line, the siblings and the children, and a tap navigates`() {
        var picked: HierarchyEntry? = null
        show(onNavigate = { picked = it })
        compose.onNodeWithTag(TopFirstRowTags.ISLAND).performClick()
        // path: Cloud Files / Files / Recent, each segment tappable
        for (i in 0..2) compose.onNodeWithTag(TopFirstRowTags.crumb(i)).assertIsDisplayed()
        compose.onNodeWithTag(TopFirstRowTags.crumb(3)).assertDoesNotExist()
        // siblings of Recent: Recent and Shared; Recent has no children; the grandchild "links" is not listed
        compose.onNodeWithTag(TopFirstRowTags.entry("shared")).assertIsDisplayed()
        compose.onNodeWithTag(TopFirstRowTags.entry("links")).assertDoesNotExist()
        compose.onNodeWithTag(TopFirstRowTags.entry("shared")).performClick()
        assertEquals("shared", picked?.id)
    }

    @Test fun `a tap on a path segment navigates to that ancestor`() {
        var picked: HierarchyEntry? = null
        show(onNavigate = { picked = it })
        compose.onNodeWithTag(TopFirstRowTags.ISLAND).performClick()
        compose.onNodeWithTag(TopFirstRowTags.crumb(1)).performClick()
        assertEquals("the section segment goes to the section", "files", picked?.id)
        assertEquals(0, picked?.depth)
        compose.onNodeWithTag(TopFirstRowTags.ISLAND).performClick()
        compose.onNodeWithTag(TopFirstRowTags.crumb(0)).performClick()
        assertEquals("the app segment goes to the app's first section", "files", picked?.id)
    }

    @Test fun `the open message ends the path, is not a link, and the app's actions come under a second separator`() {
        var ran = 0
        show(leaf = "Hello", extra = { close ->
            androidx.compose.material3.Text("Reload", Modifier.testTag("act_reload").clickable { ran++; close() })
        })
        compose.onNodeWithTag(TopFirstRowTags.ISLAND).performClick()
        compose.onNodeWithTag(TopFirstRowTags.crumb(3)).assertIsDisplayed()
        compose.onNodeWithTag(TopFirstRowTags.SEPARATOR_PAGES).assertIsDisplayed()
        compose.onNodeWithTag(TopFirstRowTags.SEPARATOR_ACTIONS).assertIsDisplayed()
        compose.onNodeWithTag("act_reload").performClick()
        assertEquals(1, ran)
        compose.onNodeWithTag(TopFirstRowTags.DROPDOWN).assertDoesNotExist()
    }
}
