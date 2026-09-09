package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST, and a LAST RESORT — read [UnreadRefreshTargetsTest] first.
 */
class UnreadRefreshWiringLintTest {

    @Test
    fun `the unread refresh syncs the scope's folders through the one two-protocol call`() {
        val calls = callsTo("repo.refreshAccountFolders", refreshUnreadScopeBody())
        assertEquals(
            "refreshUnreadScope() must call MailRepository.refreshAccountFolders exactly once. It is " +
                "the only function that covers BOTH protocols (IMAP via loadWatchedFolders, JMAP via " +
                "one syncMailbox per target); a second, JMAP-only path written here would be a second " +
                "thing to keep in step. Body was:\n${refreshUnreadScopeBody()}",
            1, calls.size,
        )
        assertEquals(
            "The arguments are the decision, not the call. 'extraFolderIds' must be " +
                "unreadRefreshTargets(...)'s answer, built from the account THE CREDENTIALS name and " +
                "from the scope the pager was built with — projecting unreadScopes.value directly " +
                "would send a sibling account's folder ids to this account's server (#121/#31) and " +
                "sync the inbox a second time. 'includeInbox' must stay true: this view shows the " +
                "inbox's unread mail like any other folder's, and false would leave it out of its " +
                "own refresh. 'limit' must be the bounded UNREAD_SCOPE_FOLDER_LIMIT and NOT the " +
                "account's window: N folders are read in one pass and held together, so " +
                "window.limit ('Everything' = 10 000) times a dozen folders is the heap. " +
                "Call found was:\n${calls.singleOrNull()?.joinToString("\n")}",
            listOf(
                "credentials",
                "extraFolderIds = unreadRefreshTargets(credentials.id, unreadScopes.value, store.inboxMailboxId())",
                "includeInbox = true",
                "limit = UNREAD_SCOPE_FOLDER_LIMIT",
            ),
            calls.single(),
        )
    }

    /**
     * The value that travels down the line above, EXECUTED — the same weld
     */
    @Test
    fun `the per-folder limit is the repository's own default, not a window`() {
        assertEquals(
            "UNREAD_SCOPE_FOLDER_LIMIT is the default of MailRepository.refreshAccountFolders, and " +
                "the same number the push pass reads a watched folder with. It bounds ONE folder's " +
                "one-shot IMAP page in a pass that holds every folder's envelopes at once; raising " +
                "it multiplies by the number of folders in the scope. It is safe only because " +
                "nothing here sizes what is KEPT: the JMAP full-query branch reads the account's " +
                "window from the store (SyncPaging.fullQuerySizing) and the IMAP branch only upserts.",
            50, UNREAD_SCOPE_FOLDER_LIMIT,
        )
    }

    /**
     * The rule that keeps the reader where they are, written as a rule about NAMING rather than
     */
    @Test
    fun `the unread refresh never names the view's own state`() {
        val body = refreshUnreadScopeBody()
        val mentions = Regex("""\b(selection|meta|showInbox)\b""").findAll(body)
            .map { it.value }.toList()
        assertEquals(
            "refreshUnreadScope() must not so much as mention selection or meta. Assigning " +
                "selection is what refreshFolder ends on, and it is the whole reason this is a " +
                "separate function: it drops the reader into the Inbox folder on the first " +
                "pull-to-refresh, i.e. the view ejecting the person who opened it — and emit/tryEmit/" +
                "update/showInbox do it just as well as '='. Mentions found were:" +
                "\n${mentions.joinToString("\n")}\nBody was:\n$body",
            emptyList<String>(), mentions,
        )
    }

    @Test
    fun `the unarchive-on-reply hook fires for the inbox this refresh brought back`() {
        val body = refreshUnreadScopeBody()
        assertEquals(
            "The mailbox the #50 hook is fired for must be CHOSEN — refreshedInboxId(...), run by " +
                "RefreshedInboxIdTest — out of the folders that came back, against the account's " +
                "cached inbox id. 'refreshes.firstOrNull()?.mailboxId' trusts a position produced " +
                "five hops away in another module, and onInboxRefreshed is not a read: aimed at the " +
                "wrong folder it advances that folder's notification baseline (its new mail is then " +
                "never announced) and re-files its threads. Calls found were:" +
                "\n${callsTo("refreshedInboxId", body).joinToString("\n")}",
            listOf(listOf("refreshes.map { it.mailboxId }", "store.inboxMailboxId()")),
            callsTo("refreshedInboxId", body),
        )
        assertEquals(
            "Codeberg #50: refreshFolder and refreshUnified both call FetchAndNotify.onInboxRefreshed " +
                "for the inbox they refreshed, and this branch must too — with the app in the " +
                "foreground this is the path new mail arrives by, so dropping it stops " +
                "unarchive-on-reply for as long as the reader stays in this view, without a word. " +
                "What it is handed is the CHOSEN inbox, never the first refresh of the list. Calls " +
                "found were:\n${callsTo("FetchAndNotify.onInboxRefreshed", body).joinToString("\n")}",
            listOf(listOf("getApplication()", "credentials", "refreshedInbox")),
            callsTo("FetchAndNotify.onInboxRefreshed", body),
        )
    }

    // -- reading the source ----------------------------------------------------------------------

    /**
     * The body of `refreshUnreadScope`, comments removed and whitespace collapsed: from the header
     */
    private fun refreshUnreadScopeBody(): String {
        val text = codeText(INBOX_VIEW_MODEL)
        val start = text.indexOf(HEADER)
        check(start >= 0) { "InboxViewModel.kt declares no '$HEADER' — did it get renamed?" }
        val open = text.indexOf('{', start)
        check(open >= 0) { "no block opens after '$HEADER'" }
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
        return text.substring(start, i).trim()
    }

    /** The arguments of every `[callee](` in [body], one list per call, in source order. */
    private fun callsTo(callee: String, body: String): List<List<String>> =
        Regex("""\b${Regex.escape(callee)}\s*\(""").findAll(body).toList()
            .map { splitArguments(balanced(body, it.range.last)) }

    /** [inside] split on the commas at its own bracket depth, so a nested call's arguments cannot
     *  be mistaken for the call's own; whitespace collapsed, empty pieces (a trailing comma) gone. */
    private fun splitArguments(inside: String): List<String> {
        val pieces = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (c in inside) {
            when {
                c == '(' || c == '{' || c == '[' -> { depth++; current.append(c) }
                c == ')' || c == '}' || c == ']' -> { depth--; current.append(c) }
                c == ',' && depth == 0 -> { pieces += current.toString(); current.clear() }
                else -> current.append(c)
            }
        }
        pieces += current.toString()
        return pieces.map { it.replace(Regex("""\s+"""), " ").trim() }.filter { it.isNotEmpty() }
    }

    /** [text] from the first `(` at or after [from], up to the `)` that balances it. */
    private fun balanced(text: String, from: Int): String {
        val start = text.indexOf('(', from).let { if (it < 0) from else it + 1 }
        var depth = 1
        var i = start
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return text.substring(start, (i - 1).coerceAtLeast(start))
    }

    /**
     * [file] as ONE line of code: comments out, runs of whitespace collapsed — so a call the
     */
    private fun codeText(file: File): String {
        val code = StringBuilder()
        var inBlockComment = false
        for (raw in file.readLines()) {
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
            code.append('\n')
        }
        return code.toString().replace(Regex("""\s+"""), " ").trim()
    }

    companion object {
        private const val HEADER = "private suspend fun refreshUnreadScope()"

        private const val INBOX_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        /** Repo root, walked up from the module's working directory. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
    }
}
