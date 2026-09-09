package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and the same disclaimer as
 */
class OutboxCountWiringTest {

    // -- the repository: two flows over one table, which must not become one -----------------

    @Test fun `the menu's count is counted straight off the rows, with no clock in the way`() {
        val body = body(MAIL_REPOSITORY, "outboxQueuedCount")
        assertTrue(
            "MailRepository.outboxQueuedCount must map $BADGE_ITEMS through OutboxLogic.queuedCount " +
                "— the count with no grace, which is what #70 asked for. Body was:\n$body",
            QUEUED_COUNT.containsMatchIn(body),
        )
        assertTrue(
            "outboxQueuedCount must not go through OutboxLogic.badgeCount or activeCount: that is " +
                "the dot's graced count, and handing it to the menu entry reverts #70 outright — " +
                "the message the reporter queued offline goes back to being announced nowhere for " +
                "thirty seconds. The suite stays green through that edit; this rule is what does " +
                "not. Body was:\n$body",
            "badgeCount" !in body && "activeCount" !in body,
        )
        assertTrue(
            "outboxQueuedCount must consult no clock at all — a wall clock read here is the same " +
                "grace under another name. Body was:\n$body",
            "currentTimeMillis" !in body,
        )
    }

    @Test fun `the dot keeps the graced count`() {
        // The other direction of the same edit, and the one that reopens #82: the dot sits on the
        // toolbar button and is in the field of view whether or not the user went looking, so a
        // send that goes out fine must never draw the eye to nothing.
        val body = body(MAIL_REPOSITORY, "outboxActiveCount")
        assertTrue(
            "MailRepository.outboxActiveCount must stay on OutboxLogic.badgeCount — the count that " +
                "waits out BADGE_GRACE_MILLIS before it lights the dot (#82). Body was:\n$body",
            "OutboxLogic.badgeCount(" in body && "queuedCount" !in body,
        )
    }

    @Test fun `the list and the count filter the same table through the same predicate`() {
        val body = body(MAIL_REPOSITORY, "outboxFlow")
        assertTrue(
            "MailRepository.outboxFlow must be $ALL_ROWS filtered by OutboxLogic.isWaitingInOutbox " +
                "and by NOTHING ELSE. The number the menu announces is meant to be, by " +
                "construction, the number of lines this list will show; a second exclusion here " +
                "(FAILED, say) leaves every count test green while the menu reads 2 and the screen " +
                "it opens lists 1, and dropping the filter altogether puts the row held in the " +
                "composer back on screen under a count that excludes it. Body was:\n$body",
            OUTBOX_FLOW.containsMatchIn(body),
        )
    }

    // -- the ViewModel: one flow each, from the repository ------------------------------------

    @Test fun `the ViewModel keeps the two counts on their own repository flows`() {
        val dot = body(INBOX_VIEW_MODEL, "outboxCount")
        assertTrue(
            "InboxViewModel.outboxCount — the dot — must come from repo.outboxActiveCount(). " +
                "Body was:\n$dot",
            "repo.outboxActiveCount(" in dot && "outboxQueuedCount" !in dot,
        )
        val menu = body(INBOX_VIEW_MODEL, "outboxQueuedCount")
        assertTrue(
            "InboxViewModel.outboxQueuedCount — the menu entry — must come from " +
                "repo.outboxQueuedCount(). Collapsing it onto outboxActiveCount() here reverts #70 " +
                "one layer above the repository, where the rules on MailRepository cannot see it. " +
                "Body was:\n$menu",
            "repo.outboxQueuedCount(" in menu && "outboxActiveCount" !in menu,
        )
    }

    // -- the screen: which count each indicator draws ------------------------------------------

    @Test fun `the toolbar dot is drawn from the graced count`() {
        val badge = toolbarBadge()
        assertTrue(
            "the BadgedBox on the overflow button must test $DOT — the count that keeps its " +
                "thirty-second grace (#82). Drawn from $MENU_COUNT it appears the instant any send " +
                "is queued, which is the flashing dot #82 exists to remove, and no behaviour test " +
                "here can see it. Badge was:\n$badge",
            DOT_READ.containsMatchIn(badge) && MENU_COUNT !in badge,
        )
    }

    @Test fun `the Outbox entry in the overflow menu is drawn from the immediate count`() {
        val trailing = outboxMenuTrailingIcon()
        assertTrue(
            "the Outbox DropdownMenuItem's trailingIcon must test and print $MENU_COUNT — the count " +
                "with no grace. This is the fix for #70 itself: the entry does not exist on screen " +
                "until the menu is opened, and opening it is looking on purpose, so there is " +
                "nothing to hold back. Back on $DOT it says nothing for thirty seconds after a " +
                "message is queued, which is the report. trailingIcon was:\n$trailing",
            MENU_READ.containsMatchIn(trailing) && !DOT_READ.containsMatchIn(trailing),
        )
        assertEquals(
            "the badge must print the same count it tested — printing the other one is the same " +
                "revert, spelled over two lines. trailingIcon was:\n$trailing",
            2, Regex("""\b$MENU_COUNT\b""").findAll(trailing).count(),
        )
    }

    // -- reading the sources --------------------------------------------------------------------

    /** The arguments of the single `BadgedBox(` in the inbox screen — the dot on the overflow
     *  button. A second one would have to be told apart by name here, which is the point of
     *  failing loudly rather than picking the first. */
    private fun toolbarBadge(): String {
        val calls = callArguments(code(INBOX_SCREEN), "BadgedBox")
        assertEquals(
            "InboxScreen is expected to host exactly one BadgedBox (the overflow button's dot). " +
                "Found ${calls.size} — name them apart here before adding another.",
            1, calls.size,
        )
        return calls.single()
    }

    /** The `trailingIcon` lambda of the menu entry that opens the Outbox — found by the string it
     *  labels itself with, not by its position among the entries. */
    private fun outboxMenuTrailingIcon(): String {
        val entry = callArguments(code(INBOX_SCREEN), "DropdownMenuItem")
            .filter { OUTBOX_LABEL in it }
        assertEquals(
            "exactly one DropdownMenuItem must be labelled $OUTBOX_LABEL — the entry that opens the " +
                "Outbox, whose badge is the whole of #70. Found ${entry.size}.",
            1, entry.size,
        )
        val at = entry.single().indexOf(TRAILING)
        assertTrue(
            "the Outbox menu entry must carry a $TRAILING: without one it announces no count at " +
                "all, which is the report itself. Entry was:\n${entry.single()}",
            at >= 0,
        )
        return balanced(entry.single(), at + TRAILING.length, '{', '}')
    }

    /**
     * The lines of [file] that are code, with their comments taken off — a line whose first
     */
    private fun codeLines(file: File): List<String> = file.readLines().mapNotNull { line ->
        val code = line.trimStart()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }

    /** [line] up to its first `//` outside a double-quoted string; `\` escapes the next character. */
    private fun withoutTrailingComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i).trimEnd()
            }
            i++
        }
        return line.trimEnd()
    }

    /** [file]'s code as one string: rules match across it, so a call the formatter wraps over four
     *  lines reads the same as one that fits on a line today. */
    private fun code(file: File): String = codeLines(file).joinToString("\n")

    /**
     * The declaration of `fun`/`val` [name] in [file] and its body, as text: everything up to the
     */
    private fun body(file: File, name: String): String {
        val lines = codeLines(file)
        val declaration = Regex("""\b(fun|val|var)\s+$name\b""")
        val start = lines.indexOfFirst { declaration.containsMatchIn(it) }
        check(start >= 0) { "${file.name} declares no '$name' — did it get renamed?" }
        val indent = lines[start].indentWidth()
        val out = mutableListOf<String>()
        var closed = false
        var depth = 0
        for (i in start until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            if (closed && i > start && line.indentWidth() <= indent) break
            out += line
            depth += line.count { it == '(' || it == '{' } - line.count { it == ')' || it == '}' }
            closed = depth == 0
        }
        return out.joinToString("\n")
    }

    private fun String.indentWidth() = length - trimStart().length

    /** The argument text of every call to [name] in [text], parentheses balanced. */
    private fun callArguments(text: String, name: String): List<String> =
        Regex("""\b${Regex.escape(name)}\(""").findAll(text)
            .map { balanced(text, it.range.last, '(', ')') }
            .toList()

    /** [text] from the first [open] at or after [from], up to the [close] that balances it. */
    private fun balanced(text: String, from: Int, open: Char, close: Char): String {
        val start = text.indexOf(open, from).let { if (it < 0) from else it + 1 }
        var depth = 1
        var i = start
        while (i < text.length && depth > 0) {
            when (text[i]) {
                open -> depth++
                close -> depth--
            }
            i++
        }
        return text.substring(start, (i - 1).coerceAtLeast(start)).trim()
    }

    companion object {
        /** The dot's count: graced, always in the field of view (#82). */
        private const val DOT = "outboxCount"

        /** The menu entry's count: immediate, only visible once the menu was opened (#70). */
        private const val MENU_COUNT = "outboxQueuedCount"

        /** Read as a whole word — `outboxQueuedCount` must not answer for `outboxCount`. */
        private val DOT_READ = Regex("""\b$DOT\b""")
        private val MENU_READ = Regex("""\b$MENU_COUNT\b""")

        private const val BADGE_ITEMS = "outboxDao.observeBadgeItems()"
        private const val ALL_ROWS = "outboxDao.observeAll()"

        /** The count with no clock: the rows of the badge query, counted by the shared predicate. */
        private val QUEUED_COUNT = Regex(
            """outboxDao\.observeBadgeItems\(\s*\)\s*\.map\s*\{\s*OutboxLogic\.queuedCount\(""",
        )

        /**
         * The list, pinned by SHAPE and not by presence: `isWaitingInOutbox` being named somewhere
         */
        private val OUTBOX_FLOW = Regex(
            """outboxDao\.observeAll\(\s*\)\s*\.map\s*\{\s*rows\s*->\s*rows\.filter\s*\{\s*""" +
                """OutboxLogic\.isWaitingInOutbox\(\s*it\.state\s*,?\s*\)\s*}\s*}""",
        )

        /** The label the Outbox menu entry is found by. */
        private const val OUTBOX_LABEL = "R.string.inbox_outbox"

        private const val TRAILING = "trailingIcon ="

        private const val APP_SOURCES = "app/src/main/kotlin"
        private const val INBOX_VIEW_MODEL_PATH = "$APP_SOURCES/app/sterna/ui/inbox/InboxViewModel.kt"
        private const val INBOX_SCREEN_PATH = "$APP_SOURCES/app/sterna/ui/inbox/InboxScreen.kt"
        private const val REPOSITORY_PATH = "core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt"

        /** Repo root, walked up from the module's working directory — the rules read BOTH modules. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
        private val INBOX_SCREEN: File by lazy { File(root, INBOX_SCREEN_PATH) }
        private val MAIL_REPOSITORY: File by lazy { File(root, REPOSITORY_PATH) }
    }
}
