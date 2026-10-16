package com.diegonmarcos.cloudc3

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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

    @Test
    fun `the shipped routes render with the owner's three filters`() {
        show(data = Declarations.routes)
        compose.onNodeWithTag(RoutesTags.COUNT).assertTextEquals("${Declarations.routes.size} rows")
        for (id in listOf("bearer", "woauth", "mesh")) tap(RoutesTags.authChip(id))
        compose.onNodeWithTag(RoutesTags.COUNT).assertTextContains("of ${Declarations.routes.size} rows", substring = true)
    }
}
