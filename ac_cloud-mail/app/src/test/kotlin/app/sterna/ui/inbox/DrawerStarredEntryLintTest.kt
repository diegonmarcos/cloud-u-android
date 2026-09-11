package app.sterna.ui.inbox

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE STARRED DRAWER ENTRY IS A QUERY, NOT A FOLDER — pinned as source text.
 *
 * There is no runtime assertion that can prove "this app never creates a Starred mailbox on the
 * server", because the proof is the ABSENCE of a call. So this rule reads the drawer and pins the
 * shape of the entry instead: where it sits, what it opens, and — the part that matters — that
 * nothing anywhere near it creates or selects a mailbox.
 *
 * Every check here FAILS CLOSED: a missing file, a missing anchor or a restructured drawer ERRORS
 * rather than passing vacuously, so the rule cannot go quietly inert the way a `grep` that stops
 * matching does.
 */
class DrawerStarredEntryLintTest {

    /**
     * Starred is the LAST of the drawer's views, immediately above the `All | Unread` tabs and so
     * directly against the folder list.
     *
     * Deliberate, and not the alphabetical mass: a `$flagged` query has no JMAP role, so
     * [folderRank] would answer 6 and file it among the custom folders under "S" — next to whatever
     * else starts with S, which is exactly where a reader would never look for it. Ranking it
     * properly would mean synthesising a fake [Mailbox] to carry the rank, which is the very thing
     * this feature must not do. Placing it last among the views puts it one row above the folders
     * without being one of them.
     */
    @Test fun `starred is the last view, directly above the folder tabs`() {
        val starred = FLAT.indexOf(STARRED_LABEL)
        check(starred >= 0) {
            "$STARRED_LABEL is gone from $INBOX_SCREEN_PATH — the drawer's Starred entry has been " +
                "removed or renamed, and every rule in this file is inert until it is repaired."
        }
        val tabs = FLAT.indexOf(FOLDER_TABS_ANCHOR)
        check(tabs >= 0) { "the drawer's All|Unread tabs ($FOLDER_TABS_ANCHOR) are gone" }

        assertTrue(
            "Starred must sit ABOVE the All|Unread tabs, i.e. last among the views and directly " +
                "against the folder list — not inside the folder list, where a role-less entry " +
                "would sort alphabetically and become unfindable",
            starred < tabs,
        )
        assertTrue(
            "nothing may be inserted between Starred and the folder tabs: the adjacency IS the " +
                "placement decision",
            FLAT.substring(starred, tabs).count { it == '{' } < ADJACENCY_SLACK_BRACES,
        )
    }

    /**
     * The entry opens a SEARCH, and carries no mailbox id.
     *
     * This is the whole safety property. `onOpenStarred` routes to the search screen on the
     * `$flagged` criterion; it never calls `viewModel.select(...)`, which is what opening a real
     * folder does. A Starred row that selected a mailbox would need a mailbox to select, and there
     * is none — so the day someone "fixes" that by creating one is the day mail starts moving.
     */
    @Test fun `the starred entry opens a search and selects no mailbox`() {
        val slot = starredEntry()

        assertTrue(
            "the Starred entry must call onOpenStarred() — the route to the search screen on the " +
                "flagged criterion",
            slot.contains("onOpenStarred()"),
        )
        assertTrue(
            "the Starred entry must NOT call viewModel.select(...): that is how a real folder row " +
                "opens, and Starred has no folder to open",
            !slot.contains("viewModel.select"),
        )
        assertTrue(
            "the Starred entry must never draw as a selected folder",
            slot.contains("selected = false"),
        )
    }

    /**
     * NO MAILBOX IS EVER CREATED FOR STARRED, and no message is moved by it.
     *
     * Scoped to the drawer entry rather than searched file-wide, so the drawer's legitimate
     * "New folder" action cannot fail this rule and cannot hide a violation either.
     */
    @Test fun `the starred entry creates no mailbox and moves no message`() {
        val slot = starredEntry()

        for (forbidden in listOf("createMailbox", "createFolder", "onCreateFolder", "move", "mailboxIds")) {
            assertTrue(
                "`$forbidden` appears in the drawer's Starred entry. Starred is a keyword query " +
                    "(RFC 8621 §4.1.1 `\$flagged`), not a container: creating a mailbox for it " +
                    "would put a folder on the real mail server that every other client then " +
                    "shows, and anything that moved mail into it would take that mail OUT of the " +
                    "folders it belongs in (#67).",
                !slot.contains(forbidden, ignoreCase = true),
            )
        }
    }

    /**
     * Starred does NOT vanish under the Unread tab.
     *
     * The tab hides zero-count FOLDER ROWS; Starred is not one, and carries no count to be zero.
     * Hiding it would mean computing how many starred messages are unread, and that number is
     * resolved by the server — a network call on every drawer open, which #247 bought its way out
     * of. It is a destination, like Home, and destinations do not come and go with a filter.
     */
    @Test fun `starred is outside the unread-tab filtering`() {
        val starred = FLAT.indexOf(STARRED_LABEL)
        check(starred >= 0) { "$STARRED_LABEL is gone from $INBOX_SCREEN_PATH" }
        val slot = starredEntry()

        assertTrue(
            "the Starred entry must not be gated on the drawer's unread filter — it holds no " +
                "count that could be zero",
            !slot.contains("unreadOnly") && !slot.contains("foldersTab"),
        )
    }

    // -- reading the source ------------------------------------------------------------------------

    /** The Starred `NavigationDrawerItem(...)`, from its label to the modifier that closes it. */
    private fun starredEntry(): String {
        val label = FLAT.indexOf(STARRED_LABEL)
        check(label >= 0) {
            "$STARRED_LABEL is gone from $INBOX_SCREEN_PATH, so this rule guards nothing."
        }
        // Back up to the NavigationDrawerItem that owns the label, forward to the row's modifier:
        // the whole entry, and nothing of its neighbours.
        val start = FLAT.lastIndexOf("NavigationDrawerItem(", label)
        check(start >= 0) { "the Starred label is not inside a NavigationDrawerItem(" }
        val end = FLAT.indexOf(ROW_END_ANCHOR, label)
        check(end >= 0) { "the Starred entry is not closed by `$ROW_END_ANCHOR`" }
        return FLAT.substring(start, end)
    }

    companion object {
        private const val STARRED_LABEL = "DrawerLabel(stringResource(R.string.folder_flagged))"
        private const val FOLDER_TABS_ANCHOR = "R.string.inbox_folders_tab_all"
        private const val ROW_END_ANCHOR = "modifier = drawerRowModifier"

        /**
         * How much may sit between Starred and the tabs, counted in opening braces on the flattened
         * source.
         *
         * The gap measures 6 today and the smallest extra `NavigationDrawerItem` costs 6 more, so
         * the threshold sits at 9 — above the real value with room for an icon or a trailing
         * lambda, below anything that could be another row. It was 14 first, and a mutation that
         * inserted a whole drawer row between Starred and the tabs scored 12 and PASSED; the number
         * is tight because a loose one made this rule decorative.
         */
        private const val ADJACENCY_SLACK_BRACES = 9

        private const val INBOX_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        /** Comments stripped, whitespace collapsed: a rule must match the CODE, never a comment
         *  that happens to quote the thing the rule forbids — which is how one guard in this
         *  repository spent its life matching its own KDoc. */
        private val FLAT: String by lazy {
            File(root, INBOX_SCREEN_PATH).readText()
                .replace(Regex("""//[^\n]*"""), " ")
                .replace(Regex("""\s+"""), " ")
        }
    }
}
