package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — read `RestoredMetaTest` first, it is the real one: it
 */
class RestoredHeaderWiringLintTest {

    @Test
    fun `the initial header is decided by restoredMeta, from six readings of the store`() {
        val text = codeText()
        val calls = Regex("""\brestoredMeta\s*\(""").findAll(text).map { callAt(text, it.range.first) }.toList()
        assertEquals(
            "InboxViewModel must call restoredMeta exactly once — the cold start's header. Calls " +
                "found were:\n${calls.joinToString("\n")}",
            1,
            calls.size,
        )
        assertEquals(
            "restoredMeta must be handed the RESTORED selection and five readings of the store, in " +
                "this order. store.inboxMailboxId() and store.currentId() are both String? and swap " +
                "silently, as do store.unreadCount() and store.totalUnreadCount(); the first pair " +
                "decides whether the restored folder is treated as the Inbox, the second decides " +
                "which number the badge shows. Compared as whole arguments. Found:" +
                "\n${argumentsOf(calls.single()).joinToString("\n")}",
            EXPECTED_RESTORED_META_ARGUMENTS,
            argumentsOf(calls.single()),
        )
        assertEquals(
            "The header's initial value must be nothing but that call. Anything else here is the " +
                "flat 'Inbox name, Inbox count' this fix removed. Found:\n${metaDeclaration(text)}",
            EXPECTED_META_DECLARATION,
            metaDeclaration(text),
        )
    }

    @Test
    fun `the restored folder is named after the notification's folder, never before`() {
        val body = blockLines(MAILBOXES_ONEACH)
        assertEquals(
            "The mailboxes onEach carries side effects and is collected exactly once (#91), so " +
                "everything judged on a folder list is judged HERE, in this order. " +
                "applyRestoredMeta must come AFTER applyNotificationFolder: a tapped notification " +
                "selects its folder and writes the header through select(), and only then does " +
                "restoredFolderMeta see a selection that has moved and stand down. Called first, " +
                "the memory writes the header of a folder the reader is about to leave. " +
                "folderSnapshot stays last: it is what inboxFallback and the unread scope derive " +
                "from. Compared as whole statements in source order. Body found:\n" +
                body.joinToString("\n"),
            EXPECTED_ONEACH_BODY,
            body,
        )
    }

    @Test
    fun `the one shot is spent only when the decision says the question is settled`() {
        val body = blockLines(APPLY_RESTORED_META)
        assertEquals(
            "applyRestoredMeta must hold the one shot and NOTHING else: feed restoredFolderMeta " +
                "the parked id, the selection of the moment, the whole list and the account label; " +
                "spend the id only on 'settled'; write only what comes back. Spending it whatever " +
                "the answer loses the fill on the empty first list — the header then stays blank " +
                "until a refresh succeeds, which offline never happens. Re-deciding anything here " +
                "puts a second copy of the rule out of reach of RestoredMetaTest. Body found:\n" +
                body.joinToString("\n"),
            EXPECTED_APPLY_BODY,
            body,
        )
    }

    @Test
    fun `the one shot is armed for a restored folder that is not the inbox`() {
        val declaration = declarationLines("private var restoredFolder").joinToString(" ")
        assertEquals(
            "restoredFolder must be armed from the RESTORED selection, and only for a folder that " +
                "is not the account's Inbox — every other restored view already knows its own name " +
                "without a folder list, and the Inbox arm of restoredMeta has already written it. " +
                "Armed unconditionally it re-writes a header that was right; never armed, the " +
                "Trash keeps the blank name restoredMeta left it, offline for ever. Compared whole " +
                "because '!= store.inboxMailboxId()' is contained in a longer condition. Found:\n" +
                declaration,
            EXPECTED_RESTORED_FOLDER_DECLARATION,
            declaration,
        )
    }

    @Test
    fun `the one shot has exactly two writers - the arming and its consumption`() {
        val text = codeText()
        val writes = Regex("""\brestoredFolder\b\s*(?::\s*[\w?<>., ]+?\s*)?=(?!=)""")
            .findAll(text)
            .map { text.substring(it.range.first, minOf(text.length, it.range.first + 100)) }
            .toList()
        assertEquals(
            "restoredFolder must be WRITTEN in exactly two places: armed once in its declaration, " +
                "cleared once in applyRestoredMeta when restoredFolderMeta says the question is " +
                "settled. A third writer spends the one shot before it can serve — " +
                "'restoredFolder = null' at the top of refresh() is the shape that survives, " +
                "because init { refresh() } runs at construction, BEFORE the first mailboxes " +
                "emission: the restored folder then never gets its name from the cache and keeps " +
                "the empty one restoredMeta left it, for ever in airplane mode. Nothing else here " +
                "counts assignments, so nothing else would go red. Writes found were:\n" +
                writes.joinToString("\n"),
            2,
            writes.size,
        )
    }

    @Test
    fun `the placeholder header carries the restored view, not the inbox`() {
        val lines = initialValueLines().filter { HEADER_FIELD.containsMatchIn(it) }
        assertEquals(
            "The initialValue of 'state' is a SECOND SITE of the combine's rules — it builds its " +
                "own MailUi by hand and it is what the reader sees until the first emission, so " +
                "every rule corrected in the combine has to be corrected here too (#155 fixed " +
                "atInbox in the combine and left this copy). Two mutations live here and no other " +
                "rule reads this block: 'mailboxName = store.inboxMailboxName()' is the original " +
                "defect put back in the cold-start window, and a missing 'unreadView' line falls " +
                "back on the field's default false, which sends InboxScreen down the folder arm " +
                "and titles a restored unread view with the Inbox's RAW, untranslated name over a " +
                "list that spans the account (same for the search hint). Whole lines, in source " +
                "order. Lines found were:\n" + lines.joinToString("\n"),
            EXPECTED_PLACEHOLDER_HEADER,
            lines,
        )
    }

    // -- reading the source ----------------------------------------------------------------------

    /**
     * The lines of `initialValue = MailUi(…)`, trimmed, blanks dropped, in source order.
     */
    private fun initialValueLines(): List<String> {
        val text = codeLines().joinToString("\n")
        val hits = Regex("""initialValue = MailUi\(""").findAll(text).toList()
        check(hits.size == 1) { "expected exactly one 'initialValue = MailUi(' in InboxViewModel — found ${hits.size}" }
        val open = hits.single().range.last
        var depth = 0
        var i = text.indexOf('(', open - 1)
        val start = i
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
            if (depth == 0) break
        }
        return text.substring(start + 1, i - 1).lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** The whole `private val meta = MutableStateFlow(…)` declaration, whitespace collapsed. */
    private fun metaDeclaration(text: String): String {
        val hits = Regex("""\bprivate val meta = MutableStateFlow\s*\(""").findAll(text).toList()
        check(hits.size == 1) { "expected exactly one 'private val meta = MutableStateFlow(' — found ${hits.size}" }
        return callAt(text, hits.single().range.first).replace(Regex("""\s+"""), " ").trim()
    }

    /**
     * The statements inside the block that opens at [anchor]'s line, trimmed, blanks dropped, in
     * source order — the braces' own lines excluded.
     */
    private fun blockLines(anchor: Regex): List<String> {
        val text = codeLines().joinToString("\n")
        val hits = anchor.findAll(text).toList()
        check(hits.size == 1) { "expected exactly one match of $anchor in InboxViewModel — found ${hits.size}" }
        val block = balancedFrom(text, hits.single().range.first).lines()
        return block.drop(1).dropLast(1).map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** The declaration starting on the line that begins with [prefix], continuations included. */
    private fun declarationLines(prefix: String): List<String> {
        val lines = codeLines().map { it.trim() }
        val start = lines.indexOfFirst { it.startsWith(prefix) }
        check(start >= 0) { "no declaration starting with '$prefix' in InboxViewModel" }
        val out = mutableListOf<String>()
        var depth = 0
        var i = start
        while (i < lines.size) {
            val line = lines[i]
            if (line.isNotEmpty()) out += line
            depth += line.count { it == '(' || it == '{' || it == '[' }
            depth -= line.count { it == ')' || it == '}' || it == ']' }
            if (depth == 0 && line.isNotEmpty() && !line.endsWith("=") && !line.endsWith(",")) break
            i++
        }
        return out
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
        return args.map { it.replace(Regex("""\s+"""), " ").trim() }.filter { it.isNotEmpty() }
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
        return text.substring(open, i).trim()
    }

    /** The file as ONE line of code: comments out, runs of whitespace collapsed. */
    private fun codeText(): String =
        codeLines().joinToString(" ").replace(Regex("""\s+"""), " ").trim()

    /**
     * The file line by line, comments taken out and line structure KEPT — the order of two
     */
    private fun codeLines(): List<String> {
        val out = mutableListOf<String>()
        var inBlockComment = false
        for (raw in INBOX_VIEW_MODEL.readLines()) {
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
        /** What the cold-start header is built from, argument by argument. */
        private val EXPECTED_RESTORED_META_ARGUMENTS = listOf(
            "selection.value",
            "store.inboxMailboxId()",
            "store.accountLabel()",
            "store.inboxMailboxName()",
            "store.unreadCount()",
            "store.totalUnreadCount()",
        )

        /** The whole initial value of the header, whitespace collapsed. */
        private const val EXPECTED_META_DECLARATION =
            "private val meta = MutableStateFlow( restoredMeta( selection.value, " +
                "store.inboxMailboxId(), store.accountLabel(), store.inboxMailboxName(), " +
                "store.unreadCount(), store.totalUnreadCount(), ), )"

        /** The three things judged when a folder list arrives, in source order. */
        private val EXPECTED_ONEACH_BODY = listOf(
            "applyNotificationFolder(folders)",
            "applyRestoredMeta(folders)",
            "folderSnapshot.value = currentAccountId.value to folders",
        )

        /** The one shot, whole. */
        private val EXPECTED_APPLY_BODY = listOf(
            "val step = restoredFolderMeta(restoredFolder, selection.value, folders, store.accountLabel())",
            "if (!step.settled) return",
            "restoredFolder = null",
            "step.meta?.let { meta.value = it }",
        )

        /**
         * The five fields of the placeholder header this rule owns: the three the header is drawn
         */
        private val EXPECTED_PLACEHOLDER_HEADER = listOf(
            "accountName = meta.value.accountName,",
            "mailboxName = meta.value.mailboxName,",
            "unreadCount = meta.value.unread,",
            "unified = selection.value is Sel.Unified,",
            "unreadView = selection.value is Sel.Unread,",
        )

        /** The field names [EXPECTED_PLACEHOLDER_HEADER] is about, anchored at the line's start. */
        private val HEADER_FIELD =
            Regex("""^(accountName|mailboxName|unreadCount|unified|unreadView)\s*=""")

        /** How the one shot is armed, whole. */
        private const val EXPECTED_RESTORED_FOLDER_DECLARATION =
            "private var restoredFolder: String? = " +
                "(selection.value as? Sel.Folder)?.id?.takeIf { it != store.inboxMailboxId() }"

        private val MAILBOXES_ONEACH = Regex("""\}\.onEach \{ folders ->""")
        private val APPLY_RESTORED_META = Regex("""private fun applyRestoredMeta\(folders: List<Mailbox>\) \{""")

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
