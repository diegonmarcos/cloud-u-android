package app.sterna.ui.inbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — read this before trusting it.
 */
class PermanentDrawerWiringLintTest {

    @Test fun `the permanent drawer is decided once, and on the Desk layout`() {
        assertEquals(
            "InboxScreen must derive `permanentDrawer` from the host's split, once, and from " +
                "PaneLayout.Desk — the 1 200 dp class. Read as Wide it turns the drawer permanent " +
                "from 600 dp, on a phone in landscape, where the sheet takes half the window; " +
                "recomputed at each of the four sites that read it, one of them drifts and the " +
                "screen half-believes it (PaneLayoutTest executes the threshold itself).",
            listOf("val permanentDrawer = panes?.layout == PaneLayout.Desk"),
            codeLines(INBOX_SCREEN).filter { it.startsWith("val permanentDrawer") },
        )
        assertEquals(
            "PaneLayout.Desk must be named ONCE in InboxScreen.kt — in the derivation above. A " +
                "second reading is a second answer to a question that already has one.",
            1,
            codeLines(INBOX_SCREEN).count { "PaneLayout.Desk" in it },
        )
    }

    @Test fun `everything that could hand a drag to the drawer is told there is none`() {
        assertEquals(
            "the three sites that feed the gesture rules must pass `!permanentDrawer`, whole: the " +
                "start-edge strip's width (drawerBandPx), the list row, and the conversation child " +
                "below it. A flat `true` at any of them compiles, leaves DrawerGestureTest green — " +
                "it executes the rule, not the wiring — and puts the defect back on exactly the " +
                "surface it was removed from.",
            listOf("drawerCanOpen = !permanentDrawer,", "drawerCanOpen = !permanentDrawer,", "drawerCanOpen = !permanentDrawer,"),
            codeLines(INBOX_SCREEN).filter { it.startsWith("drawerCanOpen = ") && it != "drawerCanOpen = drawerCanOpen," },
        )
        assertEquals(
            "ThreadChildren must pass its own parameter straight down to the child row, once: " +
                "hard-coded there, an unfolded conversation's children keep the behaviour the " +
                "parent row no longer has, on the same screen.",
            1,
            codeLines(INBOX_SCREEN).count { it == "drawerCanOpen = drawerCanOpen," },
        )
    }

    @Test fun `the two ways in that a permanent drawer makes meaningless are closed`() {
        assertEquals(
            "the Menu icon must be the FIRST thing behind `if (!permanentDrawer) {` — pinned as " +
                "the adjacent pair, not as a count, so this rule names the icon and nothing else. " +
                "Left unguarded it is a button that opens a modal drawer over the permanent one " +
                "already on screen; guarded the wrong way round it is the only way into the " +
                "folders that a phone has, gone.",
            listOf("if (!permanentDrawer) {", "IconButton(onClick = { scope.launch { drawerState.open() } }) {"),
            adjacentPair(codeLines(INBOX_SCREEN), "if (!permanentDrawer) {"),
        )
        assertEquals(
            "the tap on an empty list must hand PullableCenter a null onClick where the drawer is " +
                "permanent — null, so the empty state is not even clickable, rather than a lambda " +
                "that opens nothing. Compared whole: `if (permanentDrawer) null` alone is contained " +
                "in every weaker form of this line.",
            listOf(
                "PullableCenter(onClick = if (permanentDrawer) null else ({ scope.launch { drawerState.open() } })) {",
            ),
            codeLines(INBOX_SCREEN).filter { it.startsWith("PullableCenter(onClick = ") },
        )
    }

    @Test fun `one drawer content, two envelopes, one list`() {
        val lines = codeLines(INBOX_SCREEN)
        assertEquals(
            "the PERMANENT envelope must be the branch taken when the drawer IS permanent, and the " +
                "modal one the else. Swap the two and every window under 1 200 dp — every phone — " +
                "loses its hamburger AND its slide-out drawer at once, while a desk gets a modal " +
                "sheet over a list sized as if a drawer stood beside it. Pinned as adjacent pairs: " +
                "a count of `if (permanentDrawer) {` cannot see which envelope follows it.",
            listOf(
                listOf("if (permanentDrawer) {", "PermanentNavigationDrawer("),
                listOf("} else {", "ModalNavigationDrawer("),
            ),
            listOf(adjacentPair(lines, "if (permanentDrawer) {"), adjacentPair(lines, "ModalNavigationDrawer(", back = 1)),
        )
        assertEquals(
            "there must be exactly one DrawerContent declaration and exactly two calls — one per " +
                "envelope. A second content is the drawer written twice, and the two copies drift " +
                "apart on whichever window size nobody looks at.",
            listOf(1, 2),
            listOf(
                lines.count { it == "private fun DrawerContent(" },
                lines.count { it == "DrawerContent(" },
            ),
        )
        assertEquals(
            "both sheets must take their width from DRAWER_SHEET_WIDTH_DP — the same constant " +
                "paneSplit() already subtracts when it sizes the list pane (PaneLayoutTest). A " +
                "literal here and the list is measured against a drawer of another width.",
            listOf(
                "PermanentDrawerSheet(Modifier.width(DRAWER_SHEET_WIDTH_DP.dp)) {",
                "ModalDrawerSheet(modifier = Modifier.width(DRAWER_SHEET_WIDTH_DP.dp)) {",
            ),
            lines.filter { it.startsWith("PermanentDrawerSheet(") || it.startsWith("ModalDrawerSheet(") },
        )
        assertEquals(
            "the list and its reading pane must be composed from ONE hoisted lambda. ⛔ Not to " +
                "SAVE anything across 1 200 dp: the two `content()` are invoked from the two " +
                "branches of an `if`, so the group is torn down on the way through either way, " +
                "and the manifest declares no configChanges for MainActivity, so the resize " +
                "recreates the activity outright. What the hoisting buys is ONE source: written " +
                "out inside each branch, the two copies drift, and the drift lands on whichever " +
                "window size nobody looks at.",
            1,
            lines.count { it == "val content: @Composable () -> Unit = {" },
        )
        assertEquals(
            "`content = content,` must appear three times: once in each envelope, plus " +
                "PullableCenter's own pass-through of its content slot. Two means an envelope " +
                "stopped taking the hoisted lambda and now composes a list of its own.",
            3,
            lines.count { it == "content = content," },
        )
    }

    /**
     * The drawer's whole STATE, pinned at both call sites — the two envelopes must be handed the
     */
    @Test fun `both envelopes hand the drawer content the same state, argument for argument`() {
        val call = listOf(
            "DrawerContent(",
            "accounts = accounts,",
            "currentAccountId = currentAccountId,",
            "ui = ui,",
            "watchedFolders = watchedFolders,",
            "collapsedFolders = collapsedFolders,",
            "folderRowsBadgeUnread = folderRowsBadgeUnread,",
            "viewModel = viewModel,",
            "scope = scope,",
            "drawerState = drawerState,",
            "onSwitchAccount = onSwitchAccount,",
            "onOpenAccountSettings = onOpenAccountSettings,",
            "onOpenSettings = onOpenSettings,",
            "onOpenHome = onOpenHome,",
            "onOpenStarred = onOpenStarred,",
            "onCreateFolder = { showCreateFolder = true },",
            "onAddSubfolder = { folderToAddChild = it },",
            "onRenameFolder = { folderToRename = it },",
            "onDeleteFolder = { folderToDelete = it },",
            ")",
        )
        assertEquals(
            "the two DrawerContent calls no longer hand the sheet the same state. Both envelopes " +
                "(permanent from 1 200 dp, modal below) must pass these eighteen arguments, whole " +
                "and in this order. A single one rewritten in a single branch compiles and is " +
                "invisible to every other rule here: `folderRowsBadgeUnread = true` puts #185's " +
                "fold-by-default back on an IMAP account, where a folder folds itself and the mail " +
                "under it is badged nowhere; `watchedFolders = emptySet()` unticks every 'watch " +
                "this folder' box on a wide window while the folders stay watched; dropping " +
                "`onOpenStarred` from ONE branch leaves the Starred row drawn but inert on exactly " +
                "one window size, which is the hardest kind of dead button to notice. Nothing in " +
                "this module can compose the drawer, so this list is all there is.",
            listOf(call, call),
            blocksAt(codeLines(INBOX_SCREEN), "DrawerContent(", call.size),
        )
    }

    private companion object {
        /** The [count] consecutive code lines starting at EACH line equal to [head]. */
        fun blocksAt(lines: List<String>, head: String, count: Int): List<List<String>> =
            lines.indices.filter { lines[it] == head }
                .map { lines.subList(it, minOf(it + count, lines.size)) }

        /**
         * The single line equal to [needle] and the one after it — or, with [back], the line
         */
        fun adjacentPair(lines: List<String>, needle: String, back: Int = 0): List<String> {
            val hits = lines.indices.filter { lines[it] == needle }
            val at = hits.singleOrNull() ?: return emptyList()
            val from = at - back
            return if (from < 0 || from + 2 > lines.size) emptyList() else lines.subList(from, from + 2)
        }

        /** [file]'s lines, trimmed, with comment-only lines dropped so no rule is met by prose. */
        fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
            it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
        }

        const val INBOX_SCREEN_PATH = "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt"

        val INBOX_SCREEN: File by lazy {
            val root = generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_SCREEN_PATH).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
            File(root, INBOX_SCREEN_PATH)
        }
    }
}
