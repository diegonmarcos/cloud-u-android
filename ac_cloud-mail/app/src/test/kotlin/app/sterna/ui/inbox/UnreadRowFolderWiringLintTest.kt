package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — read this before trusting it.
 */
class UnreadRowFolderWiringLintTest {

    @Test
    fun `the origin chip is asked for on every row, and the account still wins it`() {
        val lines = codeLines(INBOX_SCREEN).map { it.trim() }
            .filter { Regex("""\boriginLabel\b""").containsMatchIn(it) }
        assertEquals(
            "Every line carrying the row's origin chip must stay exactly as it is. Two of them are " +
                "the whole of #169 on screen — the list row and the children of an unfolded " +
                "conversation, which is a row too — and both put the ACCOUNT first: in the unified " +
                "inbox the account is the question, the folder is not, and there is only one chip. " +
                "Whole lines, because 'ownerAccount?.label()' is contained in every mutation of it. " +
                "Lines found were:\n${lines.joinToString("\n")}",
            EXPECTED_ORIGIN_LABEL_LINES,
            lines.sorted(),
        )
    }

    @Test
    fun `the folder is resolved for the list row and for the children, each with its own trust`() {
        val lines = codeLines(INBOX_SCREEN).map { it.trim() }
            .filter { Regex("""\bunreadRowFolder\s*\(""").containsMatchIn(it) }
        assertEquals(
            "The two call sites must ask with the trust their rows deserve: the list row passes " +
                "'!fromSearch' (a search hit carries the folder it was crawled in, which the server " +
                "may have contradicted since), the children pass true (they come from the cache the " +
                "list pages, never from the index). And with the count their row STANDS FOR: the list " +
                "row passes 'row.threadCount' (a collapsed conversation spans several folders and " +
                "must name none), a child passes 1 (a child is one message). The declaration is " +
                "pinned with them so a rename cannot make this rule read an empty list. Whole lines " +
                "— 'folderTrusted = true' is contained in a dozen mutations of itself. Lines found " +
                "were:" +
                "\n${lines.joinToString("\n")}",
            EXPECTED_CALL_LINES,
            lines.sorted(),
        )
    }

    @Test
    fun `the children get their own folder, per child`() {
        val lines = codeLines(INBOX_SCREEN).map { it.trim() }
            .filter { Regex("""\bfolderFor\b""").containsMatchIn(it) }
        assertEquals(
            "ThreadChildren must take the folder as a per-child lambda — the same shape as " +
                "showRecipientsFor / showDraftBadgeFor beside it, and for the same reason: an " +
                "unfolded conversation spans several folders, so the parent's answer is not its " +
                "children's. Declaration, wiring and use, whole. Lines found were:" +
                "\n${lines.joinToString("\n")}",
            EXPECTED_FOLDER_FOR_LINES,
            lines.sorted(),
        )
    }

    /**
     * The value the whole volet stands on. [unreadRowFolder] compares the row's account to
     */
    @Test
    fun `the account on screen is folded into the state and carried into it`() {
        val copies = codeLines(INBOX_VIEW_MODEL).map { it.trim() }
            .filter { Regex("""\bbase\.copy\s*\(""").containsMatchIn(it) }
        assertEquals(
            "The fold that gives the state its drawer badge must give it the ACCOUNT as well — it " +
                "is the same flow, already in hand, and it is what qualifies a mailbox id (#31/#121). " +
                "Whole line, arguments included. Lines found were:\n${copies.joinToString("\n")}",
            EXPECTED_BASE_COPY_LINES,
            copies.sorted(),
        )
        val carried = stateBlock().map { it.trim() }
            .filter { Regex("""\baccountId\b""").containsMatchIn(it) }
        assertEquals(
            "…and the state the screen reads must carry it from there: 'accountId = null' compiles, " +
                "so does dropping the line (the field defaults to null so the fixtures already " +
                "written keep compiling), and either one turns unreadRowFolder into a function that " +
                "answers null for every row on earth. Whole lines, taken from the MailUi(...) the " +
                "state is built with. Lines found were:\n${carried.joinToString("\n")}",
            listOf("accountId = base.accountId,"),
            carried,
        )
    }

    // -- reading the source ------------------------------------------------------------------------

    /**
     * The lines of the `val state: StateFlow<MailUi> = combine(…) { … }` builder, comments out.
     */
    private fun stateBlock(): List<String> {
        val lines = codeLines(INBOX_VIEW_MODEL)
        val start = lines.indexOfFirst { it.contains("val state: StateFlow<MailUi>") }
        // Searched AFTER the start, not from the top: InboxViewModel.kt closes several other
        // flows with '}.stateIn(' long before this one, and the first of them made this rule read
        // a backwards slice — i.e. nothing at all.
        val end = lines.drop(start + 1).indexOfFirst { it.contains("}.stateIn(") } + start + 1
        check(start >= 0 && end > start + 1) {
            "InboxViewModel.kt is expected to build 'val state: StateFlow<MailUi>' from a combine " +
                "closed by '}.stateIn(' — this rule reads the lines between them. Found start at " +
                "$start, end at $end."
        }
        return lines.subList(start, end)
    }

    /** [file] line by line, comments removed. Same scanner as `UnreadViewWiringLintTest`'s, and for
     *  the same reason: a `//` inside a string literal is not a comment. */
    private fun codeLines(file: File): List<String> {
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
        /** The single chip slot, at each of the places it is filled — account first, always. */
        private val EXPECTED_ORIGIN_LABEL_LINES = listOf(
            "originLabel = originLabel,",
            "originLabel = ownerAccount?.label() ?: folderFor(child)?.let { mailboxDisplayName(it.role, it.name) },",
            "originLabel = ownerAccount?.label() ?: rowFolder?.let { mailboxDisplayName(it.role, it.name) },",
            "originLabel: String?,",
        )

        /** The declaration and its two call sites, each with the trust it is entitled to. */
        private val EXPECTED_CALL_LINES = listOf(
            "folderFor = { child -> unreadRowFolder(child, ui, folderTrusted = true, inViewCount = 1) },",
            "internal fun unreadRowFolder(",
            "val rowFolder = unreadRowFolder(email, ui, folderTrusted = !fromSearch, inViewCount = row.threadCount)",
        )

        /** The per-child seam: declared, wired, used. */
        private val EXPECTED_FOLDER_FOR_LINES = listOf(
            "folderFor = { child -> unreadRowFolder(child, ui, folderTrusted = true, inViewCount = 1) },",
            "folderFor: (Email) -> Mailbox?,",
            "originLabel = ownerAccount?.label() ?: folderFor(child)?.let { mailboxDisplayName(it.role, it.name) },",
        )

        /** The one fold into [MailUi], carrying the badge AND the account that qualifies a folder. */
        private val EXPECTED_BASE_COPY_LINES = listOf(
            "base.copy(accountId = accountId, unreadViewCount = unreadViewCount(accountId, scopes, base.mailboxes), visibleMailboxes = visibleFolders(base.mailboxes, onlySubscribed), showRefreshIndicator = showing)",
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
