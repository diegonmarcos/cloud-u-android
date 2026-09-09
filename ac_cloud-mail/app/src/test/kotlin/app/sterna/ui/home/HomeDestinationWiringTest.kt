package app.sterna.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — the same instrument as
 * [app.sterna.ui.inbox.CollapsedFoldersSurfaceWiringTest], for the same reason: nothing in this
 * module can run a Composable or a NavHost, so the wiring between the drawer's Home row and the
 * screen it opens exists nowhere a behavioural test can reach it. Every link of that chain can be
 * cut with one line while every other test in the repository stays green, and this file is what
 * stands over it.
 */
class HomeDestinationWiringTest {

    @Test fun `the drawer carries a Home row, with the navigation affordances and not among the folders`() {
        assertEquals(
            "the drawer's Home row changed shape. It must open the destination it is handed and " +
                "close the drawer behind itself, exactly like the Settings row at the foot of the " +
                "sheet: without the close the drawer stays open over the screen it just opened, " +
                "and on the way back the reader is looking at the sheet again.",
            listOf(
                "NavigationDrawerItem(",
                "icon = { Icon(Icons.Filled.Home, contentDescription = null) },",
                "label = { DrawerLabel(stringResource(R.string.home_title)) },",
                "selected = false,",
                "onClick = {",
                "onOpenHome()",
                "scope.launch { drawerState.close() }",
                "},",
                "modifier = drawerRowModifier,",
                ")",
            ),
            block(INBOX_SCREEN, "icon = { Icon(Icons.Filled.Home,", from = -1, count = 10),
        )
        assertEquals(
            "the Home row moved out of the drawer's navigation affordances. It must sit between " +
                "the divider under the account header and the \"All inboxes\" guard — the head of " +
                "the rows that are NOT one folder. Pushed below `mailboxTree`, it is buried under " +
                "28 folder rows, which is the placement the row exists to avoid; pushed above the " +
                "divider it lands inside the account header.",
            listOf(
                "HorizontalDivider(Modifier.padding(bottom = 12.dp))",
                "NavigationDrawerItem(",
                "icon = { Icon(Icons.Filled.Home, contentDescription = null) },",
            ),
            block(INBOX_SCREEN, "HorizontalDivider(Modifier.padding(bottom = 12.dp))", count = 3),
        )
        assertEquals(
            "the drawer content is drawn by TWO envelopes — the modal sheet under 1 200 dp and the " +
                "permanent sheet above it (#103) — and each must be handed the SAME callback. One " +
                "of the two left out, Home is missing on that window size alone, which is exactly " +
                "the shape nobody would look for.",
            listOf("onOpenHome = onOpenHome,", "onOpenHome = onOpenHome,"),
            codeLines(INBOX_SCREEN).filter { it.startsWith("onOpenHome =") },
        )
    }

    @Test fun `the row's callback resolves to the Home route, and the route to the Home screen`() {
        assertEquals(
            "the drawer's Home callback no longer navigates to the \"home\" route, or no longer " +
                "does it through the shared guard. Unguarded, a double tap during the drawer's " +
                "close animation pushes the screen twice (NavGuard); pointed at another route, the " +
                "row silently opens something else.",
            listOf("""onOpenHome = { entry.navigateOnce { nav.navigate("home") } },"""),
            block(STERNA_APP, "onOpenHome =", count = 1),
        )
        assertEquals(
            "the \"home\" route no longer resolves to HomeScreen, or no longer pops back through " +
                "the guard. A route declared but never reached from the drawer, or a drawer row " +
                "pointing at a route the NavHost does not declare, is a row that does nothing at " +
                "all — and nothing else in this repository would say so.",
            listOf(
                """composable("home") { entry ->""",
                "HomeScreen(onBack = { entry.navigateOnce { nav.popBackStack() } })",
                "}",
            ),
            block(STERNA_APP, """composable("home")""", count = 3),
        )
    }

    // ── instrument (copied from CollapsedFoldersSurfaceWiringTest) ───────────────────────────────

    /** [count] consecutive code lines starting [from] lines before the ONE line matching [prefix]. */
    private fun block(file: File, prefix: String, count: Int, from: Int = 0): List<String> {
        val lines = codeLines(file)
        val at = only(lines, prefix) + from
        return lines.subList(at, minOf(at + count, lines.size))
    }

    /** The index of the single code line starting with [needle]; fails loudly on none or several. */
    private fun only(lines: List<String>, needle: String): Int {
        val hits = lines.indices.filter { lines[it].startsWith(needle) }
        return hits.singleOrNull()
            ?: error(
                "${hits.size} code lines start with `$needle` — this lint reads the shipped source " +
                    "and must be taught the new shape rather than left green over something it " +
                    "never read",
            )
    }

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule is satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        private const val INBOX_SCREEN_PATH = "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_SCREEN_PATH).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }

        val INBOX_SCREEN: File by lazy { File(root, INBOX_SCREEN_PATH) }
        val STERNA_APP: File by lazy { File(root, "app/src/main/kotlin/app/sterna/ui/SternaApp.kt") }
    }
}
