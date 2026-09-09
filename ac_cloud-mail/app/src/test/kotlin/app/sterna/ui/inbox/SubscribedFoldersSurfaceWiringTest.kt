package app.sterna.ui.inbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — the instrument of [app.sterna.ui.settings] copied once more.
 */
class SubscribedFoldersSurfaceWiringTest {

    @Test fun `the drawer draws the FILTERED list`() {
        assertEquals(
            "the drawer tree is built from something other than MailUi.visibleMailboxes — from the " +
                "whole list, the setting hides nothing at all; from a list built here, the rule " +
                "lives in a Composable no test can run. The second argument is the set derived " +
                "from the fold registry and the default (collapsedIds); what that may fold " +
                "is CollapsedFoldersSurfaceWiringTest's business, the first argument is this " +
                "test's and it must stay ui.visibleMailboxes.",
            listOf("mailboxTree(ui.visibleMailboxes, collapsedIds).forEach { node ->"),
            block(INBOX_SCREEN, "mailboxTree(", 1),
        )
        assertEquals(
            "the set of folded ids is derived against something other than MailUi.visibleMailboxes. " +
                "Since #185 that derivation folds a user folder that has children, so it must be " +
                "asked about the list the drawer DRAWS: from the whole list, a parent whose only " +
                "child this setting hides is folded and carries a chevron while hiding nothing on " +
                "screen. WHAT it folds is CollapsedFoldersSurfaceWiringTest's business — including " +
                "the third argument, the account's badge-availability flag; that both arguments " +
                "below name the filtered list is this test's.",
            listOf("val collapsedIds = collapsedFolderIds(ui.visibleMailboxes, collapsedFolders, folderRowsBadgeUnread)"),
            block(INBOX_SCREEN, "val collapsedIds =", 1),
        )
        assertEquals(
            "the row's unread badge is summed over something other than MailUi.visibleMailboxes. " +
                "Since #185 a folded folder badges what it HIDES, so the sum must be taken over " +
                "the list the drawer DRAWS: over the whole list it adds up mail sitting in a " +
                "descendant this setting hides (#174) — a number that is on no row, inside no " +
                "chevron, and that no gesture in this drawer can reach. WHAT it sums is " +
                "CollapsedFoldersSurfaceWiringTest's business; that this argument is the filtered " +
                "list is this test's.",
            listOf("val unread = drawerUnreadCount(mailbox, ui.visibleMailboxes, collapsedIds)"),
            block(INBOX_SCREEN, "val unread = ", 1),
        )
    }

    @Test fun `the list picker filters its TARGETS and resolves paths against the whole list`() {
        assertEquals(
            "the list's move picker no longer passes the account's whole folder list plus the flag. " +
                "Filtering at the call site instead hands moveTargets the same filtered list twice, " +
                "and a child whose parent is hidden loses a segment of its path (#109). The three " +
                "remember() keys are pinned too: the targets are memoized so typing in the filter " +
                "field (#182) does not re-sort the whole account on every keystroke, and a key " +
                "dropped from that list freezes the picker on a stale folder list.",
            listOf(
                "val targets = remember(moveTargetMailboxes, moveExcludedMailbox, moveTargetsOnlySubscribed) {",
                "moveTargets(moveTargetMailboxes, moveExcludedMailbox, moveTargetsOnlySubscribed)",
                "}",
            ),
            block(INBOX_SCREEN, "val targets = remember(", 3),
        )
        assertEquals(
            "the picker's second line under a folder name must be resolved against the WHOLE list. " +
                "It is lifted OUT of the painting loop and memoized on that list (#182): the filter " +
                "field re-runs the block on every keystroke, and mailboxPathLabel rebuilds a map " +
                "over the whole account each call — but the list it is handed must still be the " +
                "whole one, or a child whose parent is hidden loses a segment of its path (#109).",
            listOf(
                "val movePaths = remember(targets, moveTargetMailboxes) {",
                "targets.map { folder -> mailboxPathLabel(folder, moveTargetMailboxes) }",
                "}",
            ),
            block(INBOX_SCREEN, "val movePaths = remember(", 3),
        )
        assertEquals(
            "the flag the picker uses must come from the SELECTION's account, not from a screen-" +
                "local guess: in the unified inbox the selected message can belong to a sibling " +
                "account with a different answer (#92).",
            listOf("val moveTargetsOnlySubscribed by viewModel.selectionOnlySubscribed.collectAsStateWithLifecycle()"),
            block(INBOX_SCREEN, "val moveTargetsOnlySubscribed by", 1),
        )
    }

    @Test fun `the reader's picker filters on the OPEN MESSAGE's account`() {
        assertEquals(
            "the reader's move picker changed shape. It must filter on the account that owns the " +
                "open message (#92), and hand moveTargets the whole folder list plus the flag.",
            listOf(
                "val moveTargets: StateFlow<List<Mailbox>> = combine(",
                "movePickerAccountId.flatMapLatest { id ->",
                "if (id == null) flowOf(emptyList()) else repo.observeMailboxes(id)",
                "},",
                "combine(_moveAccountId, _ownerAccountId, _mailboxId) { chosen, owner, current -> pickerExcludedMailbox(chosen, owner, current) },",
                "combine(store.accountsFlow, movePickerAccountId) { accounts, id -> showOnlySubscribedFor(id, accounts) },",
                ") { folders, current, onlySubscribed -> moveTargets(folders, current, onlySubscribed) }",
            ),
            block(MESSAGE_VIEW_MODEL, "val moveTargets: StateFlow<List<Mailbox>> = combine(", 7),
        )
    }

    @Test fun `the unread view's scope is recomputed on the list AND on the setting`() {
        assertEquals(
            "the 'unread' view's scope no longer follows both the folder list and the setting. " +
                "Recomputed only when the list arrives, ticking the box leaves the view paging the " +
                "folders it hides; recomputed from a second collector of [mailboxes], #89 and #91 " +
                "are replayed on a setting change.",
            listOf(
                "private val unreadScopes: StateFlow<List<Pair<String, String>>> =",
                "combine(folderSnapshot, showOnlySubscribed) { (accountId, folders), onlySubscribed ->",
                "unreadViewScopes(accountId, visibleFolders(folders, onlySubscribed))",
                "}.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())",
            ),
            block(INBOX_VIEW_MODEL, "private val unreadScopes:", 4),
        )
    }

    /**
     * WHICH account each surface asks. Nothing else in this file reaches these two flows, and a
     */
    @Test fun `each surface reads the setting of the account it is showing`() {
        assertEquals(
            "the drawer's flag must come from the CURRENT account, and from that one alone: " +
                "an aggregate over every account lets one tick hide folders in all of them.",
            listOf(
                "private val showOnlySubscribed: StateFlow<Boolean> =",
                "combine(store.accountsFlow, currentAccountId) { accounts, id -> showOnlySubscribedFor(id, accounts) }",
                ".stateIn(viewModelScope, SharingStarted.Eagerly, false)",
            ),
            block(INBOX_VIEW_MODEL, "private val showOnlySubscribed:", 3),
        )
        assertEquals(
            "the list picker's flag must come from the account the picker LISTS — the selection's, " +
                "falling back to the current one, or the one chosen on the picker's account row " +
                "(#189) — never straight from the current one (#92): mailbox ids collide between " +
                "two accounts of one server, and the two accounts' answers differ.",
            listOf(
                "val selectionOnlySubscribed: StateFlow<Boolean> =",
                "combine(movePickerAccountId, store.accountsFlow) { accountId, accounts -> showOnlySubscribedFor(accountId, accounts) }",
                ".stateIn(viewModelScope, SharingStarted.Eagerly, false)",
            ),
            block(INBOX_VIEW_MODEL, "val selectionOnlySubscribed:", 3),
        )
    }

    @Test fun `the side effects of the folder flow are NOT replayed on a setting change`() {
        assertEquals(
            "#91's parked notification folder must be judged against the folder list as it arrives, " +
                "unfiltered, and only when it arrives.",
            listOf("applyNotificationFolder(folders)"),
            block(INBOX_VIEW_MODEL, "applyNotificationFolder(folders)", 1),
        )
        assertEquals(
            "the folder a notification switches to must go through notificationFolderToShow, with " +
                "the WHOLE list and the current account's flag: resolve() is given the whole list on " +
                "purpose (a hidden folder is not an unknown one) and it is its RESULT that is judged. " +
                "Selecting resolve()'s answer directly parks the list in a folder no drawer offers, " +
                "which [inboxFallback] then bounces out of — intermittently, since the snapshot " +
                "conflates equal folder lists (#91 vs #174, arbitration of 2026-08-25). And the " +
                "pane is emptied UNLESS the message in it is the notification's OWN, which " +
                "ReadingPaneRule.onFolderFromNotification decides and ReadingPaneRuleTest EXECUTES. " +
                "It is handed the notification's (emailId, accountId) — never notificationAnchor, " +
                "which is a park the account switch has already spent by the time a folder list " +
                "arrives, and reading it here emptied the very message the tap had just posted. " +
                "With `emptyPane = false` written flat and no rule above it, a notification tapped " +
                "for another folder while a composer is on top (verdict Navigate, nothing posted in " +
                "the pane) moves the list to that folder and leaves the PREVIOUS folder's message " +
                "on the right, which is what the person meets on her way out of the reader (#103). " +
                "The rule runs BEFORE select(), whose emptyPane is then false because the question " +
                "is already answered.",
            listOf(
                "val show = notificationFolderToShow(target, folders, showOnlySubscribed.value) ?: return",
                "setReadingPane(ReadingPaneRule.onFolderFromNotification(_readingPane.value, emailId, accountId))",
                "folders.firstOrNull { it.id == show }?.let { select(it, emptyPane = false) }",
            ),
            block(INBOX_VIEW_MODEL, "val show = notificationFolderToShow(", 3),
        )
        assertEquals(
            "the folder flow grew a line. It carries side effects and is collected ONCE: anything " +
                "judged in here runs when a LIST ARRIVES and at no other moment, which is why the " +
                "Inbox fallback no longer lives here (see the next test) and why the setting must " +
                "never be read here — the box being ticked does not make this flow emit. The third " +
                "line is the restored view's missing folder name, filled in from this cached list " +
                "and pinned in ORDER by RestoredHeaderWiringLintTest: it must stay AFTER the " +
                "notification's folder, whose select() the memory then stands down for.",
            listOf(
                "}.onEach { folders ->",
                "applyNotificationFolder(folders)",
                "applyRestoredMeta(folders)",
                "folderSnapshot.value = currentAccountId.value to folders",
                "}",
            ),
            block(INBOX_VIEW_MODEL, "}.onEach { folders ->", 5),
        )
        assertEquals(
            "the folder list must be recorded WITH the account it belongs to. Recomputing the scope " +
                "later against `currentAccountId.value` pairs the new account with the previous " +
                "account's folder ids (#121/#31).",
            listOf("folderSnapshot.value = currentAccountId.value to folders"),
            block(INBOX_VIEW_MODEL, "folderSnapshot.value = currentAccountId", 1),
        )
    }

    /**
     * The Inbox fallback, and WHAT it is judged against — the 2026-08-25 arbitration, which
     */
    @Test fun `the Inbox fallback follows the folder list AND the setting`() {
        assertEquals(
            "the Inbox fallback must be derived from the recorded snapshot AND the setting, and " +
                "must hand selectionIsUnreachable the WHOLE list plus the flag — the filtering is " +
                "that function's own business, and it needs both lists to keep #89 (deleted) apart " +
                "from a list nobody has loaded yet.",
            listOf(
                "private val inboxFallback: Job =",
                "combine(folderSnapshot, showOnlySubscribed) { (_, folders), onlySubscribed ->",
                "selectionIsUnreachable((selection.value as? Sel.Folder)?.id, folders, onlySubscribed)",
                "}.onEach { unreachable -> if (unreachable) showInbox() }",
                ".launchIn(viewModelScope)",
            ),
            block(INBOX_VIEW_MODEL, "private val inboxFallback:", 5),
        )
        assertEquals(
            "the selection rule must be applied in ONE place. A second call site is a second " +
                "answer to give when they disagree.",
            1,
            codeLines(INBOX_VIEW_MODEL).count { "selectionIsUnreachable(" in it },
        )
    }

    @Test fun `the state carries BOTH lists, and the whole one is still the whole one`() {
        assertEquals(
            "the filtered list must be a SECOND field. Replacing MailUi.mailboxes with it breaks " +
                "subfolderIdsOf (which reads state.value.mailboxes): a folder deleted with a hidden " +
                "child leaves that child's mail behind, unreachable — data loss.",
            listOf(NEW_BASE_COPY),
            block(INBOX_VIEW_MODEL, "base.copy(", 1),
        )
        val lines = codeLines(INBOX_VIEW_MODEL)
        assertEquals(
            "MailUi.mailboxes must still be fed the account's whole folder list.",
            1,
            lines.count { it == "mailboxes = base.mailboxes," },
        )
        assertEquals(
            "MailUi must still carry the filtered list next to it.",
            1,
            lines.count { it == "visibleMailboxes = base.visibleMailboxes," },
        )
        assertEquals(
            "subfolderIdsOf must keep reading the WHOLE list, or a folder delete misses its hidden " +
                "children and orphans their mail.",
            listOf("val all = state.value.mailboxes"),
            block(INBOX_VIEW_MODEL, "val all = state.value.mailboxes", 1),
        )
    }

    @Test fun `the filter is applied at the screen, never at the source`() {
        val callers = MAIN_SOURCES.filter { file ->
            codeLines(file).any { "visibleFolders(" in it }
        }.map { it.name }.sorted()
        assertEquals(
            "the subscription filter moved. It belongs to the three files below and nowhere else — " +
                "in the repository or in the folder cache it would take with it every reader of the " +
                "list: subfolderIdsOf (a delete that misses hidden children), the reader's parent " +
                "paths, the rule editor, the watched folders.",
            listOf("FolderSelection.kt", "InboxViewModel.kt", "SubscribedFolders.kt"),
            callers,
        )
    }

    // ── instrument (copied from ShowOnlySubscribedFoldersWiringTest) ─────────────────────────────

    /** [count] consecutive code lines from the ONE line starting with [prefix]. */
    private fun block(file: File, prefix: String, count: Int): List<String> {
        val lines = codeLines(file)
        val at = only(lines, prefix, exact = false)
        return lines.subList(at, minOf(at + count, lines.size))
    }

    /** The index of the single matching code line; fails loudly on none or several. */
    private fun only(lines: List<String>, needle: String, exact: Boolean = true): Int {
        val hits = lines.indices.filter { if (exact) lines[it] == needle else lines[it].startsWith(needle) }
        return hits.singleOrNull()
            ?: error(
                "${hits.size} code lines match `$needle` — this lint reads the shipped source and " +
                    "must be taught the new shape rather than left green over something it never read",
            )
    }

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule is satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        /** Whole line, arguments included — the shape `PageKeyWiringLintTest` and
         *  `UnreadViewWiringLintTest` pin from their own angle. */
        private const val NEW_BASE_COPY =
            "base.copy(accountId = accountId, unreadViewCount = unreadViewCount(accountId, " +
                "scopes, base.mailboxes), visibleMailboxes = visibleFolders(base.mailboxes, " +
                "onlySubscribed), showRefreshIndicator = showing)"

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
        val MESSAGE_VIEW_MODEL: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt")
        }
        val MAIN_SOURCES: List<File> by lazy {
            File(root, "app/src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        }
    }
}
