package com.diegonmarcos.clouddrive.ui

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.files.FilesController
import com.diegonmarcos.clouddrive.files.FilesScreen
import com.diegonmarcos.clouddrive.files.FilesUiState
import com.diegonmarcos.clouddrive.files.Location
import com.diegonmarcos.superapp.bottomnav.BottomNavTags
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * #579 the layout-tree test of the shell and the Files pane, rendered under Robolectric
 * (the libs:bottomnav precedent): the island draws the DECLARED tabs in order with
 * their tags, the content is tagged per tab, and the Files pane composes its tree
 * — tab strip, breadcrumbs, toolbar, list — in that order from the top, with the
 * selection bar appearing on a long press. Every expected id is read from the
 * declaration, never restated here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DriveShellTest {

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

    private val fakeActions = object : DriveActions {
        override fun requestStorageAccess() {}
        override fun requestTreeGrant() {}
        override fun openEngine(engine: String, target: String, url: String) {}
        override fun openImage(path: String, siblings: List<String>) {}
        override fun openPdf(path: String) {}
        override fun openWith(path: String) {}
        override fun share(paths: List<String>) {}
        override fun shareText(title: String, text: String) {}
        override fun copyText(text: String) {}
        override fun openUrl(url: String) {}
        override fun launchApp(packageName: String, fallbackUrl: String): Boolean = false
    }

    @Test
    fun `shell draws the declared tabs in order and tags the content per tab`() {
        val tabs = Declarations.tabs
        assertTrue(tabs.size >= 3)
        compose.setContent { DriveTheme { DriveShell { id, _ -> Text("content:$id") } } }
        compose.onNodeWithTag(DriveTags.SHELL).assertIsDisplayed()
        compose.onNodeWithTag(BottomNavTags.ISLAND).assertIsDisplayed()
        // Every declared tab is an item of the island, left to right in declared order.
        val lefts = tabs.map { t -> compose.onNodeWithTag(BottomNavTags.item(t.id)).fetchSemanticsNode().boundsInRoot.left }
        assertTrue("island items follow the declared order: $lefts", lefts.zipWithNext().all { (a, b) -> a < b })
        compose.onNodeWithTag(BottomNavTags.item(Declarations.defaultTab)).assertIsSelected()
        compose.onNodeWithText("content:" + Declarations.defaultTab).assertIsDisplayed()
        // Selecting another declared tab swaps the content and moves the pill.
        val other = tabs.first { it.id != Declarations.defaultTab }
        compose.onNodeWithTag(BottomNavTags.item(other.id)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(BottomNavTags.item(other.id)).assertIsSelected()
        compose.onNodeWithText("content:" + other.id).assertIsDisplayed()
        compose.onNodeWithTag(DriveTags.tab(other.id)).assertIsDisplayed()
    }

    @Test
    fun `files pane composes strip, crumbs, toolbar and list top to bottom and selects on long press`() {
        val ctx = RuntimeEnvironment.getApplication()
        val root = Files.createTempDirectory("pane").toFile()
        File(root, "alpha.txt").writeText("a"); File(root, "beta").mkdirs()
        val second = Files.createTempDirectory("pane2").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = FilesController(ctx, FilesUiState.initial(Location.Local(root.absolutePath), Location.Local(second.absolutePath), "name", false, false), scope, DrivePrefs(ctx))
        compose.setContent { DriveTheme { FilesScreen(controller, fakeActions, hasAccess = true) } }
        compose.waitUntil(5_000) { controller.listings.value[Location.Local(root.absolutePath).key]?.loading == false }
        compose.waitForIdle()
        val strip = compose.onNodeWithTag(DriveTags.FILES_TAB_STRIP).fetchSemanticsNode().boundsInRoot.top
        val crumbs = compose.onNodeWithTag(DriveTags.FILES_BREADCRUMBS).fetchSemanticsNode().boundsInRoot.top
        val toolbar = compose.onNodeWithTag(DriveTags.FILES_TOOLBAR).fetchSemanticsNode().boundsInRoot.top
        val list = compose.onNodeWithTag(DriveTags.FILES_LIST).fetchSemanticsNode().boundsInRoot.top
        assertTrue("strip $strip < crumbs $crumbs < toolbar $toolbar < list $list", strip < crumbs && crumbs < toolbar && toolbar < list)
        compose.onNodeWithTag(DriveTags.ISLAND).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag(DriveTags.FILES_ROW).fetchSemanticsNodes().size == 2)
        compose.onAllNodesWithTag(DriveTags.FILES_SELECTION_BAR).fetchSemanticsNodes().let { assertTrue("no selection bar before a long press", it.isEmpty()) }
        compose.onAllNodesWithTag(DriveTags.FILES_ROW).onFirst().performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag(DriveTags.FILES_SELECTION_BAR).assertIsDisplayed()
        assertTrue(controller.state.value.a.selecting)
    }

    @Test
    fun `every declared icon renders through the catalog`() {
        val names = Declarations.iconNames(Declarations.tabs, Declarations.sync, Declarations.files)
        assertTrue(names.all { IconCatalog.knows(it) })
    }
}
