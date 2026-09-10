package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — read this before trusting it.
 */
class UnreadViewWiringLintTest {

    // -- the drawer entry -------------------------------------------------------------------------

    @Test
    fun `the drawer entry says which view it selects, and selects it`() {
        val entry = unreadEntryCall()
        assertEquals(
            "The drawer entry for the unread view must be wired exactly like the unified one it was " +
                "written from, and to ITS OWN view: 'selected = ui.unified' puts the tick on the " +
                "wrong row, and an onClick that closes the drawer without calling selectUnread() " +
                "leaves the entry inert. Compared as whole arguments — 'selected = ui.unreadView' is " +
                "contained in 'selected = ui.unreadView && false'. Arguments found were:" +
                "\n${argumentsOf(entry).joinToString("\n")}",
            EXPECTED_ENTRY_ARGUMENTS,
            argumentsOf(entry),
        )
    }

    @Test
    fun `the entry carries the count, and drops it when there is none`() {
        val block = unreadEntryBlock()
        assertEquals(
            "The label must be picked on the count itself. Zero unread has to fall back to the bare " +
                "label, exactly as the unified entry does — 'Unread  (0)' is a promise of nothing. " +
                "Compared as the whole condition. Conditions found in the entry were:" +
                "\n${ifConditions(block).joinToString("\n")}",
            listOf(EXPECTED_COUNT_CONDITION),
            ifConditions(block),
        )
        assertEquals(
            "The counted arm must be handed the SAME count the condition tested — a second source " +
                "(ui.unreadCount is the unified aggregate, and is the neighbouring entry's) would " +
                "print one account's view under another's number. ⭐ Unlike that neighbour, this " +
                "entry carries its number whether or not it is the view on screen, and that is a " +
                "CHOICE: K-9 and Gmail badge a mailbox permanently, and the badge is only useful " +
                "before you tap it. The unified entry hides its own outside its view for a reason " +
                "of its own — ui.unreadCount holds the unified total only while unified is selected. " +
                "Compared as whole calls, in source order: counted arm first, bare label second. " +
                "Calls found were:\n${callsTo("stringResource", block).joinToString("\n")}",
            EXPECTED_LABEL_CALLS,
            callsTo("stringResource", block),
        )
    }

    /**
     * The one difference from the unified entry, and the reason this rule exists: the unread view
     */
    @Test
    fun `the entry is not hidden behind a second account`() {
        val blocks = multiAccountBlocks()
        assertEquals(
            "Exactly one 'accounts.size > 1' block is expected to hold the unified entry — this rule " +
                "is anchored on it, so any other answer means the guard moved and the rule below " +
                "stopped guarding anything. Blocks were:\n${blocks.joinToString("\n")}",
            1,
            blocks.count { Regex("""\bselectUnified\s*\(""").containsMatchIn(it) },
        )
        assertEquals(
            "The unread entry must stay OUT of every multi-account guard: it is one account's unread " +
                "mail across its own folders, and a single-account install is precisely where the " +
                "drawer has no other way to reach it. Blocks were:\n${blocks.joinToString("\n")}",
            0,
            blocks.count { Regex("""\bselectUnread\s*\(""").containsMatchIn(it) },
        )
    }

    // -- the toolbar ------------------------------------------------------------------------------

    @Test
    fun `the unread funnel is not offered in a view that is already filtered`() {
        val actions = toolbarActions()
        assertEquals(
            "The toolbar's unread funnel must be hidden in the unread view. [listUnreadOnly] forces " +
                "the filter on there, so the button would be drawn in its OFF colour over a filtered " +
                "list (it reads ui.unreadOnly, which the scope does not touch) and tapping it would " +
                "change nothing on screen. Compared as whole conditions: an empty answer means the " +
                "guard was dropped, and a longer one means it grew a term. Conditions mentioning the " +
                "view, found in the toolbar's actions, were:\n${viewConditions(actions).joinToString("\n")}",
            listOf(EXPECTED_FUNNEL_GUARD),
            viewConditions(actions),
        )
        assertEquals(
            "The toolbar's actions are expected to hold exactly one toggleUnreadOnly() — the funnel " +
                "the rule above guards. Actions block was:\n$actions",
            1,
            Regex("""\btoggleUnreadOnly\s*\(""").findAll(actions).count(),
        )
    }

    // -- the state the screen reads ---------------------------------------------------------------

    /**
     * The regex is `\bunreadView(Count)?\b`, and the parenthesis is not decoration: `\bunreadView\b`
     */
    @Test
    fun `the view flag and the badge are carried whole, from their own state`() {
        val lines = codeLines(INBOX_VIEW_MODEL).map { it.trim() }
            .filter { Regex("""\bunreadView(Count)?\b""").containsMatchIn(it) }
        assertEquals(
            "MailUi.unreadView is what the drawer tick, the title, the search hint and the hidden " +
                "funnel are all decided on, and 'sel is Sel.Unified' would compile in its place; " +
                "MailUi.unreadViewCount is the number on the entry, and any other Int compiles " +
                "there. Whole lines, because a fragment is blind to a term added. Lines found were:" +
                "\n${lines.joinToString("\n")}",
            EXPECTED_VIEW_FLAG_LINES,
            lines.sorted(),
        )
    }

    /**
     * SOURCE LINT, and the rule the one above cannot be: it pins WHERE THE VALUES COME FROM.
     */
    @Test
    fun `the badge is folded in from the unread scope, and the state reads the folded flow`() {
        val lines = codeLines(INBOX_VIEW_MODEL).map { it.trim() }
            .filter { Regex("""\b(baseState|badgedState)\b""").containsMatchIn(it) }
        assertEquals(
            "The three declarations that carry the drawer badge must stay exactly as they are. " +
                "The scope handed to the fold is the one the PAGER is built from (unreadScopes, " +
                "account-pinned pairs — #121); the flow the UI state is built from is the folded " +
                "one. Whole lines, arguments and lambda headers included. Lines found were:" +
                "\n${lines.joinToString("\n")}",
            EXPECTED_STATE_LINES,
            lines.sorted(),
        )
        val calls = codeLines(INBOX_VIEW_MODEL).map { it.trim() }
            .filter { Regex("""\bunreadViewCount\s*\(""").containsMatchIn(it) }
        assertEquals(
            "The badge must be summed by unreadViewCount(...) — run by UnreadViewCountTest — from " +
                "the account, the scope and the folder list the state already carries. Calls found " +
                "were:\n${calls.joinToString("\n")}",
            EXPECTED_BADGE_CALL,
            calls,
        )
    }

    /**
     * Not this volet's feature, but this volet's accident. `atInbox` drives the system Back
     */
    @Test
    fun `no view without a folder can pass for the inbox`() {
        val lines = codeLines(INBOX_VIEW_MODEL).map { it.trim() }
            .filter { Regex("""\batInbox\s*=""").containsMatchIn(it) }
        assertEquals(
            "Every atInbox in the ViewModel must either carry the flag or delegate to " +
                "isAtInbox(sel, inboxMailboxId) — the one rule, run by AtInboxTest. Restated " +
                "inline it drifts, and the form it drifts back to, '(sel as? Sel.Folder)?.id == …', " +
                "is true for ANY non-folder selection the moment the cached inbox id is null. It is " +
                "the Back gesture: true here and Back LEAVES THE APP, from a view the reader chose. " +
                "Compared as whole lines, sorted. Lines found were:" +
                "\n${lines.joinToString("\n")}",
            EXPECTED_AT_INBOX_LINES,
            lines.sorted(),
        )
    }

    /**
     * The delegation above is only worth what the NAME resolves to. Kotlin resolves a member
     */
    @Test
    fun `the ViewModel declares no isAtInbox of its own`() {
        val declarations = codeLines(INBOX_VIEW_MODEL).map { it.trim() }
            .filter { Regex("""\bfun\s+isAtInbox\b""").containsMatchIn(it) }
        assertEquals(
            "InboxViewModel.kt must declare NO function named isAtInbox. A member of that name wins " +
                "over the top-level isAtInbox of the same package, so both 'atInbox = isAtInbox(…)' " +
                "sites silently go to it, the rule above keeps passing on their text, AtInboxTest " +
                "keeps exercising a function nothing calls, and the defect returns whole: Back " +
                "LEAVES THE APP from the unread view. The rule lives in InboxBack.kt and is called " +
                "from here, never redeclared. Declarations found were:" +
                "\n${declarations.joinToString("\n")}",
            emptyList<String>(),
            declarations,
        )
    }

    /**
     * The last link, and the only one: `InboxScreen.kt` is where `atInbox` is READ. Everything
     */
    @Test
    fun `the screen asks the back rule the right question, and binds its answer`() {
        val lines = codeLines(INBOX_SCREEN).map { it.trim() }
            .filter { Regex("""\bbackAction\b""").containsMatchIn(it) }
        assertEquals(
            "The list screen must hand inboxBackAction exactly these four flags and bind a handler " +
                "for each of its four non-default outcomes. Widening the third argument (e.g. " +
                "'ui.selectedMailboxId == null || ui.atInbox') restores the defect at the reading " +
                "end, and dropping the SHOW_INBOX handler turns every Back outside the Inbox into " +
                "leaving the app — neither is visible to any other test in this repo. Whole lines, " +
                "in source order. Lines found were:\n${lines.joinToString("\n")}",
            EXPECTED_BACK_ACTION_LINES,
            lines,
        )
    }

    /**
     * The search field's hint says what the search covers. In this view it covers the whole account
     */
    @Test
    fun `the search hint names the view, not the folder last visited`() {
        val block = searchScopeLabel()
        assertEquals(
            "The hint's arms must be the title's arms: the unread view first (it selects no folder " +
                "either, so it falls through to the folder name otherwise), then the unified inbox, " +
                "then the folder by its role. Compared as whole conditions. Conditions found were:" +
                "\n${ifConditions(block).joinToString("\n")}",
            listOf("ui.unreadView", "ui.unified"),
            ifConditions(block),
        )
        assertEquals(
            "Each arm must name its own scope, and the unread one reuses the title's string — no " +
                "tenth translation for the same word. Compared as whole calls, in source order. " +
                "Calls found were:\n${scopeCalls(block).joinToString("\n")}",
            EXPECTED_SEARCH_SCOPE_CALLS,
            scopeCalls(block),
        )
    }

    // -- reading the source ------------------------------------------------------------------------

    /** The `NavigationDrawerItem(...)` call that selects the unread view, whole. */
    private fun unreadEntryCall(): String {
        val text = codeText(INBOX_SCREEN)
        val entries = Regex("""\bNavigationDrawerItem\s*\(""").findAll(text)
            .map { callAt(text, it.range.first) }
            .filter { Regex("""\bselectUnread\s*\(""").containsMatchIn(it) }
            .toList()
        check(entries.size == 1) {
            "InboxScreen.kt is expected to hold exactly ONE NavigationDrawerItem calling " +
                "selectUnread() — the drawer's entry into the unread view. Found ${entries.size}: " +
                "was the entry removed, renamed, or is there now a second way in that would have to " +
                "be told apart here?"
        }
        return entries.single()
    }

    /** The label statement plus the entry it labels: from `val unreadLabel` to the end of the call. */
    private fun unreadEntryBlock(): String {
        val text = codeText(INBOX_SCREEN)
        val start = text.indexOf("val unreadLabel")
        check(start >= 0) {
            "InboxScreen.kt is expected to build the unread entry's label in a local named " +
                "'unreadLabel', as the unified entry does with 'unifiedLabel' — this rule reads the " +
                "text between that name and the entry it feeds."
        }
        val entry = unreadEntryCall()
        val end = text.indexOf(entry, start)
        check(end > start) { "the unread drawer entry does not follow 'val unreadLabel'" }
        return text.substring(start, end + entry.length)
    }

    /** Every body of `if (accounts.size > 1)`, i.e. what only a second account makes reachable. */
    private fun multiAccountBlocks(): List<String> {
        val text = codeText(INBOX_SCREEN)
        val hits = Regex("""\bif\s*\(\s*accounts\.size\s*>\s*1\s*\)""").findAll(text).toList()
        check(hits.isNotEmpty()) {
            "InboxScreen.kt is expected to guard at least one block on 'accounts.size > 1' — the " +
                "unified entry's. Found none; was the guard rewritten?"
        }
        return hits.map { balancedFrom(text, it.range.last) }
    }

    /** The `actions = { … }` lambda of the browse toolbar (never the search bar's). */
    private fun toolbarActions(): String {
        val text = codeText(INBOX_SCREEN)
        val bars = Regex("""\bMediumTopAppBar\s*\(""").findAll(text).toList()
        check(bars.size == 1) {
            "InboxScreen.kt is expected to call MediumTopAppBar exactly once — the browse toolbar, " +
                "the one carrying the unread funnel. Found ${bars.size}."
        }
        val bar = callAt(text, bars.single().range.first)
        val at = bar.indexOf("actions =")
        check(at >= 0) { "the browse toolbar has no actions block:\n$bar" }
        return balancedFrom(bar, at)
    }

    /**
     * The `val scopeLabel = …` statement of the search bar, up to the statement that follows it.
     */
    private fun searchScopeLabel(): String {
        val text = codeText(INBOX_SCREEN)
        val start = text.indexOf("val scopeLabel")
        val end = text.indexOf("val focusManager", start + 1)
        check(start >= 0 && end > start) {
            "InboxScreen.kt is expected to build the search field's hint in a local named " +
                "'scopeLabel', followed by 'val focusManager' — this rule reads the text between " +
                "them. Found scopeLabel at $start, focusManager at $end."
        }
        return text.substring(start, end)
    }

    /** The scope-naming calls of [block], whole, in source order. */
    private fun scopeCalls(block: String): List<String> =
        Regex("""\b(stringResource|mailboxDisplayName)\s*\(""").findAll(block)
            .map { callAt(block, it.range.first) }
            .toList()

    /** Every `if` condition of [block] that mentions the unread VIEW, each one whole. */
    private fun viewConditions(block: String): List<String> =
        ifConditions(block).filter { Regex("""\bunreadView\b""").containsMatchIn(it) }

    /** Every `if` condition in [block], whole, in source order. */
    private fun ifConditions(block: String): List<String> =
        Regex("""\bif\s*\(""").findAll(block).map { hit ->
            val call = callAt(block, hit.range.first)
            call.substring(call.indexOf('(') + 1, call.length - 1).trim()
        }.toList()

    /** Every call to [name] in [block], whole, arguments included, in source order. */
    private fun callsTo(name: String, block: String): List<String> =
        Regex("""\b${Regex.escape(name)}\s*\(""").findAll(block)
            .map { callAt(block, it.range.first) }
            .toList()

    /** The arguments of the call [text], split at the commas of its own depth, blanks dropped. */
    private fun argumentsOf(text: String): List<String> {
        val open = text.indexOf('(')
        check(open >= 0) { "not a call: $text" }
        val args = mutableListOf<String>()
        var depth = 0
        var start = open + 1
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '(', '{', '[' -> depth++
                ')', ']', '}' -> depth--
            }
            if (depth == 0) {
                args += text.substring(start, i)
                break
            }
            if (depth == 1 && text[i] == ',') {
                args += text.substring(start, i)
                start = i + 1
            }
            i++
        }
        return args.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** The whole call starting at [from], up to the `)` that balances its first `(`. */
    private fun callAt(text: String, from: Int): String {
        val open = text.indexOf('(', from)
        check(open >= 0) { "no argument list opens after offset $from" }
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
            if (depth == 0) break
        }
        return text.substring(from, i).trim()
    }

    /** [text] from [from] up to the `}` that balances the first `{` at or after it. */
    private fun balancedFrom(text: String, from: Int): String {
        val open = text.indexOf('{', from)
        check(open >= 0) { "no block opens after offset $from" }
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
            if (depth == 0) break
        }
        return text.substring(from, i).trim()
    }

    /**
     * [file] as ONE line of code: comments taken out, runs of whitespace collapsed — so a call the
     */
    private fun codeText(file: File): String = strippedLines(file).joinToString(" ")
        .replace(Regex("""\s+"""), " ").trim()

    /** The same source, comments removed, but kept LINE BY LINE for the whole-line rules. */
    private fun codeLines(file: File): List<String> = strippedLines(file)

    private fun strippedLines(file: File): List<String> {
        val out = mutableListOf<String>()
        var inBlockComment = false
        for (raw in file.readLines()) {
            val code = StringBuilder()
            var inString = false
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                when {
                    inBlockComment -> if (c == '*' && raw.getOrNull(i + 1) == '/') {
                        inBlockComment = false
                        i++
                    }
                    inString -> {
                        code.append(c)
                        when {
                            c == '\\' -> raw.getOrNull(i + 1)?.let { code.append(it); i++ }
                            c == '"' -> inString = false
                        }
                    }
                    c == '"' -> {
                        code.append(c)
                        inString = true
                    }
                    c == '/' && raw.getOrNull(i + 1) == '*' -> {
                        inBlockComment = true
                        i++
                    }
                    c == '/' && raw.getOrNull(i + 1) == '/' -> i = raw.length
                    else -> code.append(c)
                }
                i++
            }
            out += code.toString()
        }
        return out
    }

    companion object {
        /** The drawer entry, argument by argument — the gabarit of the unified one, its own view. */
        private val EXPECTED_ENTRY_ARGUMENTS = listOf(
            "icon = { Icon(Icons.Filled.MarkEmailUnread, contentDescription = null) }",
            "label = { DrawerLabel(unreadLabel) }",
            "selected = ui.unreadView",
            "onClick = { viewModel.selectUnread() scope.launch { drawerState.close() } }",
            "modifier = drawerRowModifier",
        )

        /** No count, no number — the unified entry's rule, on this entry's own count. */
        private const val EXPECTED_COUNT_CONDITION = "ui.unreadViewCount > 0"

        private val EXPECTED_LABEL_CALLS = listOf(
            "stringResource(R.string.inbox_unread_view_unread, ui.unreadViewCount)",
            "stringResource(R.string.inbox_unread_view)",
        )

        /** The funnel's guard, whole. */
        private const val EXPECTED_FUNNEL_GUARD = "!ui.unreadView"

        /** Every line of the ViewModel that names the flag, or the number, the screen reads. */
        private val EXPECTED_VIEW_FLAG_LINES = listOf(
            "base.copy(accountId = accountId, unreadViewCount = unreadViewCount(accountId, scopes, base.mailboxes), visibleMailboxes = visibleFolders(base.mailboxes, onlySubscribed), showRefreshIndicator = showing)",
            "unreadView = base.unreadView,",
            "unreadView = sel is Sel.Unread,",
            // The placeholder `state` starts on, below `}.stateIn(` — a SECOND SITE of the line
            // above, and what the reader sees until the first emission. Left out it took the
            // field's default `false`, so a RESTORED unread view opened on the folder arm of the
            // title. Its own rule is `RestoredHeaderWiringLintTest`'s; here it must merely exist.
            "unreadView = selection.value is Sel.Unread,",
            "unreadViewCount = base.unreadViewCount,",
            "val unreadView: Boolean = false,",
            "val unreadView: Boolean,",
            "val unreadViewCount: Int = 0,",
            "val unreadViewCount: Int = 0,",
        )

        /** The three declarations the badge travels through, whole. */
        private val EXPECTED_STATE_LINES = listOf(
            // The fold gained a FIFTH flow, the refresh indicator's visibility (#178): the
            // display of the tern is derived, and the truth `refreshing` — carried by baseState
            // above, untouched — is what still empties the centre of the screen (#63).
            "private val badgedState = combine(baseState, unreadScopes, currentAccountId, showOnlySubscribed, indicatorShowing) { base, scopes, accountId, onlySubscribed, showing ->",
            "private val baseState = combine(mailboxes, selection, meta, status, unifiedUnread) { mailboxes, sel, meta, status, unifiedUnread ->",
            "val state: StateFlow<MailUi> = combine(badgedState, searchState, settings.sortOrder, unreadOnly, connectivity.online) { base, search, sortOrder, unreadOnly, online ->",
        )

        /** The badge, computed once, from the scope the pager uses. */
        private val EXPECTED_BADGE_CALL = listOf(
            "base.copy(accountId = accountId, unreadViewCount = unreadViewCount(accountId, scopes, base.mailboxes), visibleMailboxes = visibleFolders(base.mailboxes, onlySubscribed), showRefreshIndicator = showing)",
        )

        /** Back's answer: carried once, decided nowhere but [isAtInbox]. Sorted. */
        private val EXPECTED_AT_INBOX_LINES = listOf(
            "atInbox = base.atInbox,",
            "atInbox = isAtInbox(sel) { store.inboxMailboxId() },",
            "atInbox = isAtInbox(selection.value) { store.inboxMailboxId() },",
        )

        /**
         * The screen's whole use of the Back rule: the question, then the four handlers. The
         */
        private val EXPECTED_BACK_ACTION_LINES = listOf(
            "val backAction = inboxBackAction(selectionActive, detailOpen = detail != null && pane.anchor != null, ui.searching, ui.atInbox)",
            "BackHandler(enabled = backAction == InboxBackAction.CLEAR_SELECTION) { viewModel.clearSelection() }",
            "BackHandler(enabled = backAction == InboxBackAction.CLOSE_DETAIL) { viewModel.closePane() }",
            "BackHandler(enabled = backAction == InboxBackAction.CLOSE_SEARCH) { viewModel.setSearchActive(false) }",
            "BackHandler(enabled = backAction == InboxBackAction.SHOW_INBOX) { viewModel.showInbox() }",
        )

        /** The search hint's three scopes, in source order. */
        private val EXPECTED_SEARCH_SCOPE_CALLS = listOf(
            "stringResource(R.string.inbox_unread_view)",
            "stringResource(R.string.inbox_all_inboxes)",
            "mailboxDisplayName(searchRole, ui.mailboxName)",
        )

        private const val INBOX_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt"
        private const val INBOX_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        /** Repo root, walked up from the module's working directory. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_SCREEN: File by lazy { File(root, INBOX_SCREEN_PATH) }
        private val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
    }
}
