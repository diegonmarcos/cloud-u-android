package com.diegonmarcos.cloudc3

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertTrue
import com.diegonmarcos.cloudc3.pages.RoutesTableModel
import com.diegonmarcos.cloudc3.pages.RoutesTableModel.Column
import com.diegonmarcos.cloudc3.pages.RoutesTableScreen
import com.diegonmarcos.cloudc3.pages.RoutesTags
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

/** The Containers > Table page, DRIVEN: header taps sort, chips filter, the count follows, the state is handed out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoutesTableComposeTest {

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

    private fun r(name: String, auth: String, vm: String, pub: String = "$name.example.com") =
        RoutesTableModel.Row("app", name, pub, auth, "$name.app:80", vm, "10.0.0.6:80")

    private val rows = listOf(
        r("charlie", "bearer", "vm-b"), r("alpha", "woauth", "vm-a"), r("bravo", "mesh", "vm-a", pub = ""), r("delta", "mesh", "vm-c", pub = ""),
    )
    private val states = mutableListOf<RoutesTableModel.State>()

    private fun show(initial: RoutesTableModel.State = RoutesTableModel.State(), data: List<RoutesTableModel.Row> = rows) {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { RoutesTableScreen(data, initial, onState = { states += it }) }
        }
        compose.waitForIdle()
    }

    private fun containers(): List<String> = compose.onAllNodesWithTag(RoutesTags.cell(Column.CONTAINER))
        .fetchSemanticsNodes().map { n ->
            n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
        }

    private fun tap(tag: String) { compose.onNodeWithTag(tag).performClick(); compose.waitForIdle() }

    @Test
    fun `all seven columns are headed in the owner's order`() {
        show()
        val want = listOf("Category", "Container", "Public URL", "Auth Type", "Private URL", "VM Name", "VM IP:PORT")
        Column.entries.zip(want).forEach { (c, text) -> compose.onNodeWithTag(RoutesTags.header(c)).assertTextContains(text, substring = true) }
    }

    @Test
    fun `rows show every column and the count`() {
        show()
        compose.onNodeWithTag(RoutesTags.COUNT).assertTextEquals("4 rows")
        assertEquals(listOf("alpha", "bravo", "charlie", "delta"), containers())
    }

    @Test
    fun `a header tap sorts that column, again flips it, and the indicator follows`() {
        show()
        tap(RoutesTags.header(Column.AUTH_TYPE))
        compose.onNodeWithTag(RoutesTags.header(Column.AUTH_TYPE)).assertTextContains("▲", substring = true)
        assertEquals("ascending by auth label: Bearer, Mesh-only x2, WOAuth", listOf("charlie", "bravo", "delta", "alpha"), containers())
        tap(RoutesTags.header(Column.AUTH_TYPE))
        compose.onNodeWithTag(RoutesTags.header(Column.AUTH_TYPE)).assertTextContains("▼", substring = true)
        assertEquals(listOf("alpha", "bravo", "delta", "charlie"), containers())
        compose.onNodeWithTag(RoutesTags.header(Column.CONTAINER)).assertTextEquals("Container")
    }

    @Test
    fun `the auth chips multi-select and the count follows`() {
        show()
        tap(RoutesTags.authChip("mesh"))
        assertEquals(listOf("bravo", "delta"), containers())
        compose.onNodeWithTag(RoutesTags.COUNT).assertTextEquals("2 of 4 rows")
        tap(RoutesTags.authChip("bearer"))
        assertEquals(listOf("bravo", "charlie", "delta"), containers())
        tap(RoutesTags.authChip("mesh"))
        assertEquals(listOf("charlie"), containers())
    }

    @Test
    fun `the vm chips multi-select and combine with auth`() {
        show()
        tap(RoutesTags.vmChip("vm-a"))
        assertEquals(listOf("alpha", "bravo"), containers())
        tap(RoutesTags.vmChip("vm-c"))
        assertEquals(listOf("alpha", "bravo", "delta"), containers())
        tap(RoutesTags.authChip("mesh"))
        assertEquals(listOf("bravo", "delta"), containers())
        compose.onNodeWithTag(RoutesTags.COUNT).assertTextEquals("2 of 4 rows")
    }

    @Test
    fun `every sort and filter change is handed out to be persisted, and a restored state is honoured`() {
        show()
        tap(RoutesTags.header(Column.VM_NAME))
        tap(RoutesTags.authChip("woauth"))
        assertEquals(2, states.size)
        assertEquals(RoutesTableModel.State(Column.VM_NAME, true, setOf("woauth")), states.last())
    }

    @Test
    fun `a restored state is honoured on the first draw`() {
        val restored = RoutesTableModel.decode(RoutesTableModel.encode(RoutesTableModel.State(Column.CONTAINER, false, setOf("mesh"))))
        show(initial = restored)
        assertEquals(listOf("delta", "bravo"), containers())
        compose.onNodeWithTag(RoutesTags.COUNT).assertTextEquals("2 of 4 rows")
        compose.onNodeWithTag(RoutesTags.header(Column.CONTAINER)).assertTextContains("\u25BC", substring = true)
    }

    private fun type(text: String) {
        compose.onNodeWithTag(RoutesTags.SEARCH).performTextInput(text)
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
    }

    @Test
    fun `typing filters live across all columns and updates the count`() {
        show()
        type("EXAMPLE")
        assertEquals(listOf("alpha", "charlie"), containers())
        compose.onNodeWithTag(RoutesTags.COUNT).assertTextEquals("2 of 4 rows")
        compose.onNodeWithTag(RoutesTags.SEARCH).performTextClearance()
        type("bravo.app")
        assertEquals("private URL column is searched too", listOf("bravo"), containers())
    }

    @Test
    fun `search combines with the chips and is handed out for persistence`() {
        show()
        tap(RoutesTags.vmChip("vm-a"))
        type("a")
        assertEquals(listOf("alpha", "bravo"), containers())
        type("lph")
        assertEquals(listOf("alpha"), containers())
        assertTrue(states.last().query.contains("lph") && states.last().vms == setOf("vm-a"))
    }

    @Test
    fun `the clear button empties the box and restores every row`() {
        show()
        type("alpha")
        assertEquals(listOf("alpha"), containers())
        tap(RoutesTags.SEARCH_CLEAR)
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        assertEquals(4, containers().size)
        compose.onNodeWithTag(RoutesTags.COUNT).assertTextEquals("4 rows")
        assertTrue(compose.onAllNodesWithTag(RoutesTags.SEARCH_CLEAR).fetchSemanticsNodes().isEmpty())
        assertEquals("", states.last().query)
    }

    @Test
    fun `a restored query is shown in the box and applied`() {
        show(initial = RoutesTableModel.State(query = "delta"))
        assertEquals(listOf("delta"), containers())
        compose.onNodeWithTag(RoutesTags.SEARCH).assertTextContains("delta")
    }

    private val opened = mutableListOf<RoutesTableModel.OpenTarget>()

    private fun showWithOpen() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { RoutesTableScreen(rows, onState = { states += it }, onOpen = { opened += it }) }
        }
        compose.waitForIdle()
    }

    private fun longPress(col: Column, index: Int = 0) {
        compose.onAllNodesWithTag(RoutesTags.cell(col))[index].performTouchInput { longClick() }
        compose.waitForIdle()
    }

    private fun clipboard(): String? {
        val cm = RuntimeEnvironment.getApplication().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        return cm.primaryClip?.getItemAt(0)?.text?.toString()
    }

    @Test
    fun `long-press on any cell opens Copy, Open and Copy and Open`() {
        showWithOpen()
        for (col in Column.entries) {
            longPress(col)
            for (tag in listOf(RoutesTags.MENU_COPY, RoutesTags.MENU_OPEN, RoutesTags.MENU_COPY_OPEN)) compose.onNodeWithTag(tag).assertExists()
            compose.onNodeWithTag(RoutesTags.MENU_COPY).performClick()
            compose.waitForIdle()
        }
    }

    @Test
    fun `Copy puts the cell value on the clipboard`() {
        showWithOpen()
        longPress(Column.CONTAINER, 2)
        compose.onNodeWithTag(RoutesTags.MENU_COPY).performClick()
        assertEquals("charlie", clipboard())
        assertTrue("Copy alone opens nothing", opened.isEmpty())
    }

    @Test
    fun `Open on a public url opens its https address, Copy and Open does both`() {
        showWithOpen()
        longPress(Column.PUBLIC_URL, 0)
        compose.onNodeWithTag(RoutesTags.MENU_OPEN).performClick()
        assertEquals(listOf<RoutesTableModel.OpenTarget>(RoutesTableModel.OpenTarget.Url("https://alpha.example.com")), opened)
        longPress(Column.PRIVATE_URL, 0)
        compose.onNodeWithTag(RoutesTags.MENU_COPY_OPEN).performClick()
        assertEquals("alpha.app:80", clipboard())
        assertEquals(RoutesTableModel.OpenTarget.Url("http://alpha.app:80"), opened.last())
    }

    @Test
    fun `Open on a container and a vm hands out their targets`() {
        showWithOpen()
        longPress(Column.CONTAINER, 0)
        compose.onNodeWithTag(RoutesTags.MENU_OPEN).performClick()
        longPress(Column.VM_NAME, 0)
        compose.onNodeWithTag(RoutesTags.MENU_OPEN).performClick()
        assertEquals(listOf(RoutesTableModel.OpenTarget.Container("alpha"), RoutesTableModel.OpenTarget.Vm("vm-a")), opened)
    }

    @Test
    fun `Open on a category filters the table to it, and the chip clears it`() {
        showWithOpen()
        longPress(Column.CATEGORY, 0)
        compose.onNodeWithTag(RoutesTags.MENU_OPEN).performClick()
        compose.waitForIdle()
        assertEquals("app", states.last().category)
        compose.onNodeWithTag(RoutesTags.CATEGORY_CHIP).assertExists()
        tap(RoutesTags.CATEGORY_CHIP)
        assertEquals("", states.last().category)
    }

    @Test
    fun `values that cannot be opened disable Open and Copy and Open, and a blank cell disables Copy`() {
        showWithOpen()
        longPress(Column.AUTH_TYPE, 0)
        compose.onNodeWithTag(RoutesTags.MENU_OPEN).assertIsNotEnabled()
        compose.onNodeWithTag(RoutesTags.MENU_COPY_OPEN).assertIsNotEnabled()
        compose.onNodeWithTag(RoutesTags.MENU_COPY).assertIsEnabled()
        compose.onNodeWithTag(RoutesTags.MENU_COPY).performClick()
        compose.waitForIdle()
        // charlie is the only row with a public url... bravo/delta have none: sorted by container, bravo is index 1
        longPress(Column.PUBLIC_URL, 1)
        compose.onNodeWithTag(RoutesTags.MENU_COPY).assertIsNotEnabled()
        compose.onNodeWithTag(RoutesTags.MENU_OPEN).assertIsNotEnabled()
    }

    @Test
    fun `the shipped routes render with the owner's three filters`() {
        show(data = Declarations.routes)
        compose.onNodeWithTag(RoutesTags.COUNT).assertTextEquals("${Declarations.routes.size} rows")
        for (id in listOf("bearer", "woauth", "mesh")) tap(RoutesTags.authChip(id))
        compose.onNodeWithTag(RoutesTags.COUNT).assertTextContains("of ${Declarations.routes.size} rows", substring = true)
    }
}
