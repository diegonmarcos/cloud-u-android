package app.sterna.ui.inbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — the instrument of [SubscribedFoldersSurfaceWiringTest],
 */
class CollapsedFoldersSurfaceWiringTest {

    @Test fun `the drawer reads the stored registry, and holds no collapse state of its own`() {
        assertEquals(
            "the drawer's collapse state must come from the ViewModel's persisted registry. A " +
                "screen-local remember() is the shipped defect: it is re-created on a rotation, a " +
                "locale change, a cold start and every return from a message (InboxScreen is a " +
                "NavHost destination), so every chevron the user touched is forgotten.",
            listOf("val collapsedFolders by viewModel.collapsedFolders.collectAsStateWithLifecycle()"),
            block(INBOX_SCREEN, "val collapsedFolders by", 1),
        )
        assertEquals(
            "InboxScreen writes the collapse state into a local variable again. Every write must " +
                "go through viewModel.setFolderCollapsed, or the choice dies with the Composable. " +
                "A named ARGUMENT is not a write and is told apart by its trailing comma: since " +
                "#103 volet 6 the drawer content is a function of its own and is HANDED the " +
                "collected registry — which the rule below pins, so the exemption cannot be used " +
                "to smuggle a screen-local one back in.",
            emptyList<String>(),
            codeLines(INBOX_SCREEN).filter {
                it.startsWith("var collapsedFolders") ||
                    (it.startsWith("collapsedFolders =") && !it.endsWith(","))
            },
        )
        assertEquals(
            "the drawer content must be handed the ViewModel's registry, once per envelope — the " +
                "modal sheet under 1 200 dp and the permanent sheet above it (#103 volet 6). A " +
                "branch that passes something else to one of the two forgets the chevrons on that " +
                "window size alone, which is exactly the shape nobody would look for.",
            listOf("collapsedFolders = collapsedFolders,", "collapsedFolders = collapsedFolders,"),
            codeLines(INBOX_SCREEN).filter { it.startsWith("collapsedFolders =") },
        )
    }

    @Test fun `the registry becomes the folded set in ONE place, and the tree still draws the filtered list`() {
        assertEquals(
            "the drawer no longer derives what is folded through collapsedFolderIds, or no longer " +
                "builds the tree from the tab-filtered list. The derivation is the single point " +
                "that decides what an untouched folder does, and it is resolved against " +
                "ui.visibleMailboxes: handed the account's whole list it would put a chevron on a " +
                "parent whose only child the 'only subscribed folders' setting hides (#174), and " +
                "folding it would hide nothing. ⛔ It is deliberately NOT resolved against " +
                "drawnFolders, though that is what the tree draws — what is folded must not change " +
                "under the reader as they tap All|Unread (#247); the tab narrows the drawing, " +
                "never the registry. The THIRD argument is " +
                "the account's answer to 'can a folder row here badge unread at all': drop it and " +
                "the derivation goes back to folding by default on an account where nothing on " +
                "screen can say what a folded row hides.",
            listOf(
                "val collapsedIds = collapsedFolderIds(ui.visibleMailboxes, collapsedFolders, folderRowsBadgeUnread)",
                "mailboxTree(drawnFolders, collapsedIds, folderDisplayName).forEach { node ->",
            ),
            block(INBOX_SCREEN, "val collapsedIds =", 2),
        )
        assertEquals(
            "the chevron's own state must be read from the same derived set the tree was built " +
                "from, or the glyph and the tree disagree.",
            listOf("val collapsed = mailbox.id in collapsedIds"),
            block(INBOX_SCREEN, "val collapsed = mailbox.id", 1),
        )
    }

    /**
     * The badge, pinned with its ARGUMENTS and with the branch that reads it.
     */
    @Test fun `the row's badge is the folded-aware count, asked about the drawn list and the same folded set`() {
        assertEquals(
            "the drawer's unread badge changed shape. It must be drawerUnreadCount(mailbox, " +
                "ui.visibleMailboxes, collapsedIds) — the list the tree is built from and the set " +
                "the tree hides children with — and the label must read THAT number, on both " +
                "branches. Back on mailbox.unreadForList it is the defect this volet closes: a " +
                "folder folded on the first run shows only its own unread, and everything under it " +
                "is on no row at all. Nothing in this module can run a Composable, so these lines " +
                "are the only thing standing over that wiring.",
            listOf(
                "val unread = drawerUnreadCount(mailbox, ui.visibleMailboxes, collapsedIds)",
                "val label = if (unread > 0) {",
                "stringResource(R.string.inbox_folder_unread, displayName, unread)",
                "} else {",
                "displayName",
            ),
            block(INBOX_SCREEN, "val unread = ", 5),
        )
    }

    /**
     * The two writes, pinned with their ARGUMENTS and in source order.
     */
    @Test fun `both writes go through the ViewModel, with the argument each one owes`() {
        assertEquals(
            "the drawer's writes changed. Creating a subfolder must UNFOLD its parent " +
                "(`false`, never `true`, and never a key removal), and the chevron must write the " +
                "opposite of what it currently shows. Swap either argument and the drawer folds " +
                "what the user opened, with every behavioural test still green.",
            listOf(
                "viewModel.setFolderCollapsed(parent.id, false) // reveal the new child",
                "viewModel.setFolderCollapsed(mailbox.id, !collapsed)",
            ),
            codeLines(INBOX_SCREEN).filter { it.startsWith("viewModel.setFolderCollapsed(") },
        )
    }

    @Test fun `the flow is initialised inline from the CURRENT account`() {
        assertEquals(
            "the registry flow must be seeded inline, from the current account's stored registry. " +
                "Seeded from the init block it NPEs (init runs before this declaration's " +
                "initialiser); seeded from a constant, the first drawer opened after a cold start " +
                "shows every folder unfolded whatever the user chose.",
            listOf(
                "private val _collapsedFolders =",
                "MutableStateFlow(store.currentId()?.let { store.collapsedFolders(it) } ?: emptyMap())",
            ),
            block(INBOX_VIEW_MODEL, "private val _collapsedFolders", 2),
        )
        assertEquals(
            "InboxViewModel.setFolderCollapsed changed shape. It must resolve the CURRENT account " +
                "(and give up when there is none), write through the store, and re-read. Writing " +
                "under a remembered account id files one account's fold under another's; skipping " +
                "the re-read leaves the drawer showing the state it had before the tap.",
            listOf(
                "fun setFolderCollapsed(mailboxId: String, collapsed: Boolean) {",
                "val accountId = store.currentId() ?: return",
                "store.setFolderCollapsed(accountId, mailboxId, collapsed)",
                "refreshCollapsedFolders()",
                "}",
            ),
            block(INBOX_VIEW_MODEL, "fun setFolderCollapsed(", 5),
        )
    }

    /**
     * The account switch. `InboxScreen` switches accounts IN PLACE, so without this re-read the
     */
    @Test fun `the registry is re-read on an account switch, and REPLACED not merged`() {
        assertEquals(
            "an account switch no longer re-reads the collapse registry. The screen re-points at " +
                "the new account in place, so the previous account's folds stay on screen — and on " +
                "IMAP the ids are paths, so a namesake folder folds itself (#92/#121).",
            listOf(
                "folderSnapshot.value = null to emptyList()",
                "meta.value = Meta(store.accountLabel(), store.inboxMailboxName(), store.unreadCount())",
                "refreshWatchedFolders()",
                "refreshCollapsedFolders()",
            ),
            block(INBOX_VIEW_MODEL, "folderSnapshot.value = null to emptyList()", 4),
        )
        assertEquals(
            "the re-read REPLACES the registry with the arriving account's, and does not merge it " +
                "into what was on screen. Merging keeps the departing account's folds — and on " +
                "IMAP the ids are paths, so the namesake folder on the account being opened is " +
                "drawn folded with nobody having asked (#92/#121), which is the very defect this " +
                "branch closes. An empty body passes every other assertion in this file and kills " +
                "the feature outright: the chevron stops moving. BOTH statements are the rule: the " +
                "second re-reads whether the ARRIVING account's rows can badge unread at all, and " +
                "without it a JMAP account opened after an IMAP one keeps folding nothing, or an " +
                "IMAP account opened after a JMAP one folds by default with no badge anywhere to " +
                "say what went. Nothing executes this method — no test in this module can build an " +
                "InboxViewModel — so these lines are the only thing standing over it.",
            listOf(
                "private fun refreshCollapsedFolders() {",
                "val accountId = store.currentId()",
                "_collapsedFolders.value = accountId?.let { store.collapsedFolders(it) } ?: emptyMap()",
                "_folderRowsBadgeUnread.value = accountId?.let { repo.folderRowsBadgeUnread(it) } ?: false",
                "}",
            ),
            block(INBOX_VIEW_MODEL, "private fun refreshCollapsedFolders(", 5),
        )
        assertEquals(
            "the registry is re-read from somewhere new, or no longer from all of the places it " +
                "must be. THREE call sites, pinned as a number and not as a count taken from the " +
                "file itself: the account switch above, setFolderCollapsed's own re-read, and the " +
                "folder RENAME — on IMAP the id is the path, MailRepository.renameFolder re-keys " +
                "the stored registry (AccountStore.replaceCollapsedFolder) beside the watch flags, " +
                "and without this re-read the flow keeps the OLD key: the renamed folder reads as " +
                "'nobody decided' and the default folds it on the spot, closing the folder the " +
                "user had opened by hand. ⛔ Still NOT the folder delete — nothing prunes the " +
                "registry, so a re-read there answers the same thing and only looks like a " +
                "cleanup that does not exist (#185, arbitration A7).",
            3,
            codeLines(INBOX_VIEW_MODEL).count { it == "refreshCollapsedFolders()" },
        )
    }

    /**
     * V5's flag, end to end, in the two places nothing else can see it.
     */
    @Test fun `the badge-availability flag is seeded inline, and reaches the screen`() {
        assertEquals(
            "InboxViewModel's badge-availability flag must be seeded INLINE from the current " +
                "account, by asking the repository — not from init (which NPEs), and not from a " +
                "constant (which pins every account to the same answer and puts the IMAP default " +
                "back). It is the account's property, so it is read per account id.",
            listOf(
                "private val _folderRowsBadgeUnread =",
                "MutableStateFlow(store.currentId()?.let { repo.folderRowsBadgeUnread(it) } ?: false)",
            ),
            block(INBOX_VIEW_MODEL, "private val _folderRowsBadgeUnread", 2),
        )
        assertEquals(
            "the drawer no longer collects the badge-availability flag from the ViewModel. " +
                "Computed in the Composable instead it would be out of reach of every test here; " +
                "not collected at all, the derivation below is handed something that does not " +
                "follow the account switch.",
            listOf("val folderRowsBadgeUnread by viewModel.folderRowsBadgeUnread.collectAsStateWithLifecycle()"),
            block(INBOX_SCREEN, "val folderRowsBadgeUnread by", 1),
        )
    }

    /**
     * The two lines that EXPOSE the flows, pinned whole — the weakest link of the chain above.
     */
    @Test fun `both flows are exposed as themselves, never as a constant`() {
        assertEquals(
            "the fold registry no longer reaches the screen as itself. Handed a constant or a " +
                "rebuilt flow, the drawer stops seeing what refreshCollapsedFolders writes and " +
                "every chevron is forgotten again — the shipped defect, with this whole file " +
                "still green over it.",
            listOf("val collapsedFolders: StateFlow<Map<String, Boolean>> = _collapsedFolders"),
            block(INBOX_VIEW_MODEL, "val collapsedFolders: StateFlow", 1),
        )
        assertEquals(
            "the badge-availability flag no longer reaches the screen as itself. Exposed as a " +
                "constant true it never answers false, so the default folds on an IMAP account " +
                "with no badge anywhere to say what went — V5 undone by one line, and nothing " +
                "else in this module can see it.",
            listOf("val folderRowsBadgeUnread: StateFlow<Boolean> = _folderRowsBadgeUnread"),
            block(INBOX_VIEW_MODEL, "val folderRowsBadgeUnread: StateFlow", 1),
        )
    }

    // ── instrument (copied from SubscribedFoldersSurfaceWiringTest) ──────────────────────────────

    /** [count] consecutive code lines from the ONE line starting with [prefix]. */
    private fun block(file: File, prefix: String, count: Int): List<String> {
        val lines = codeLines(file)
        val at = only(lines, prefix)
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
        val INBOX_VIEW_MODEL: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt")
        }
    }
}
