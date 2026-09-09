package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and the same disclaimer as
 */
class OwnMessageWiringTest {

    @Test fun `the reader decides through the shared function`() {
        val body = body(MESSAGE_VIEW_MODEL, "isOwnMessage")
        assertTrue(
            "MessageViewModel.isOwnMessage must answer with showsRecipients(...) — the decision the " +
                "list row makes. A second copy of the rule here is how the header and the row it " +
                "was opened from come to disagree. Body was:\n$body",
            "showsRecipients(" in body,
        )
        assertTrue(
            "the reader must hand it the role of the folder the message is actually in. " +
                "Body was:\n$body",
            "role = mailboxRole" in body.replace(Regex("""\s+"""), " "),
        )
        assertTrue(
            "the reader must not keep its own folder test beside the shared one: a stray " +
                "role == \"sent\" here is a second rule that will drift. Body was:\n$body",
            !Regex(""""(sent|drafts|inbox)"""").containsMatchIn(body),
        )
    }

    @Test fun `the list's top-level row decides through the shared function, unified included`() {
        val row = showRecipientsArgument()
        assertTrue(
            "the row's showRecipients must be showsRecipients(...): the shared decision. Was:\n$row",
            "showsRecipients(" in row,
        )
        val call = callArguments(row, "showsRecipients").single()
        assertEquals(
            "the row must be handed exactly these arguments, whole — the VISIBLE folder's role, " +
                "ui.unified (the all-inboxes view selects no folder, so its role is null and a " +
                "constant false there leaves #115 standing on the screen the app opens on), and " +
                "the message's own authorship (a constant false takes #59/#69 away from Sent, " +
                "Drafts and the Trash). Arguments were:\n$call",
            listOf(
                "role = visibleFolderRole(ui)",
                "unified = ui.unified",
                "selfAuthored = isSelfAuthored(email.from, sendAsIdentities(email, accounts))",
            ),
            arguments(call),
        )
    }

    @Test fun `the rows of an unfolded conversation are judged by their own folder`() {
        // The THIRD surface. It carried the pre-#115 rule under its own name, which made the list
        // contradict the reader one level down: your own message echoed into the Inbox read
        val screen = code(INBOX_SCREEN)
        assertEquals(
            "showsRecipientsInThread must be called exactly once, for the children of an unfolded " +
                "conversation.",
            1, Regex("""showsRecipientsInThread\(""").findAll(body(INBOX_SCREEN, "emailRow")).count(),
        )
        val call = callArguments(body(INBOX_SCREEN, "emailRow"), "showsRecipientsInThread").single()
        assertEquals(
            "the child row must be handed exactly these arguments, whole. The role must come from " +
                "the CHILD (accountId + mailboxId, resolved account-qualified in folderRoles), not " +
                "from the folder on screen, and the authorship must be the child's own sender — " +
                "'child.to' here silently takes #69's in-thread half away. Arguments were:\n$call",
            listOf(
                "accountId = child.accountId",
                "mailboxId = child.mailboxId",
                "roles = folderRoles",
                "selfAuthored = isSelfAuthored(child.from, sendAsIdentities(child, accounts))",
            ),
            arguments(call),
        )
        assertTrue(
            "the screen must not keep a second folder rule beside the shared one: isOwnMailContext " +
                "was the visible folder's test, and applying it to a message that lives somewhere " +
                "else is what made the child row and the reader disagree.",
            !Regex("""\bisOwnMailContext\(""").containsMatchIn(screen),
        )
        assertTrue(
            "the screen must not reach the pre-#115 rule under its old name anywhere: isOwnMessage " +
                "was the top-level row's rule and is exactly what #115 changed.",
            !Regex("""\bisOwnMessage\(""").containsMatchIn(screen),
        )
    }

    @Test fun `the in-thread rule answers through the shared decision, not beside it`() {
        // showsRecipientsInThread is a behaviour-tested function (ShowsRecipientsTest), but what
        // stops it drifting from its two neighbours is that it CALLS them. A body that re-decided
        // for itself would pass its own tests and disagree with the reader again.
        val body = body(FOLDER_ACTIONS, "showsRecipientsInThread").replace(Regex("""\s+"""), " ")
        assertTrue(
            "showsRecipientsInThread must answer with showsRecipients(...). Body was:\n$body",
            "showsRecipients(" in body,
        )
        assertTrue(
            "it must hand it the role of the folder the message is IN (messageFolderRole), never " +
                "the visible folder's. Body was:\n$body",
            "role = messageFolderRole(accountId, mailboxId, roles)" in body,
        )
        assertTrue(
            "it must pass the message's own authorship through, not a constant. Body was:\n$body",
            "selfAuthored = selfAuthored" in body,
        )
        assertTrue(
            "it must not re-test folder names on its own: a stray \"sent\" here is a second rule " +
                "that will drift from showsRecipients. Body was:\n$body",
            !Regex(""""(sent|drafts|inbox)"""").containsMatchIn(body),
        )
    }

    // -- the "(Draft)" chip: the same two rows, the same role map -------------------------------

    @Test fun `the list's top-level row asks for the draft chip through the shared function`() {
        val body = body(INBOX_SCREEN, "emailRow")
        assertEquals(
            "showsDraftBadge must be called exactly twice in the row renderer: once for the " +
                "top-level row, once for the children of an unfolded conversation. One call is a " +
                "surface left showing no '(Draft)' after a restart, or none in an IMAP account.",
            2, Regex("""showsDraftBadge\(""").findAll(body).count(),
        )
        assertEquals(
            "the top-level row's showDraftBadge must be exactly this, whole — TWO branches, and " +
                "both of them load-bearing in opposite directions. The search branch keeps the " +
                "keyword alone, which is what this renderer already did for the Inbox's own " +
                "search list before this branch existed: a plain 'false' there REMOVES a chip " +
                "main was showing. The other branch must keep the showsDraftBadge call, or the " +
                "Drafts folder falls silent after a force-stop and never says '(Draft)' at all " +
                "on IMAP.",
            "showDraftBadge = if (fromSearch) email.isDraft else showsDraftBadge( " +
                "isDraft = email.isDraft, accountId = email.accountId, " +
                "mailboxId = email.mailboxId, roles = folderRoles, )",
            namedArgument(body, SHOW_DRAFT_BADGE).replace(Regex("""\s+"""), " ").trim(),
        )
        assertEquals(
            "the top-level row must hand the decision exactly these arguments, whole: the row's " +
                "OWN keyword, and the row's OWN account and folder. 'ui.selectedMailboxId' here " +
                "gives the unified list no chip at all (it selects no folder) and elsewhere " +
                "decides on the folder next door — the cross-account confusion of #31.",
            listOf(
                "isDraft = email.isDraft",
                "accountId = email.accountId",
                "mailboxId = email.mailboxId",
                "roles = folderRoles",
            ),
            arguments(callArguments(body, "showsDraftBadge").first()),
        )
    }

    @Test fun `the children of an unfolded conversation ask on their own folder`() {
        val body = body(INBOX_SCREEN, "emailRow")
        val call = callArguments(body, "showsDraftBadge").lastOrNull()
        assertEquals(
            "a child row must be judged by the folder IT is filed in, not by the folder on screen: " +
                "an unfolded conversation spans the viewed folder(s) plus Sent, so a draft child " +
                "unfolded from the Inbox is the case that goes wrong. Arguments were:\n$call",
            listOf(
                "isDraft = child.isDraft",
                "accountId = child.accountId",
                "mailboxId = child.mailboxId",
                "roles = folderRoles",
            ),
            call?.let { arguments(it) } ?: emptyList<String>(),
        )
        assertEquals(
            "ThreadChildren must pass each child's own answer down to the row: " +
                "'showDraftBadge = showDraftBadgeFor(child),'. A constant there is every child of " +
                "every unfolded conversation losing (or gaining) its '(Draft)' at once.",
            1,
            body(INBOX_SCREEN, "ThreadChildren").lines()
                .count { it.trim() == "showDraftBadge = showDraftBadgeFor(child)," },
        )
    }

    @Test fun `the swipeable row hands the answer on to the row it draws`() {
        // The link nothing else holds: EmailListItem's parameter DEFAULTS to the keyword, so
        // deleting this one line compiles, leaves every behaviour test green (the pure function is
        assertEquals(
            "SwipeableEmailRow must pass 'showDraftBadge = showDraftBadge,' on to EmailListItem, " +
                "on one line and exactly once. Without it EmailListItem falls back to its default " +
                "— the '\$draft' keyword alone — and the fix is silently undone everywhere.",
            1,
            body(INBOX_SCREEN, "SwipeableEmailRow").lines()
                .count { it.trim() == "showDraftBadge = showDraftBadge," },
        )
    }

    @Test fun `the row draws the chip from what it was told, not from the keyword`() {
        val lines = code(EMAIL_LIST_ITEM).lines().map { it.trim() }
        assertEquals(
            "EmailListItem must gate the '(Draft)' chip on 'if (showDraftBadge) {'. Left as " +
                "'if (email.isDraft) {' the whole wiring above is dead code and the chip still " +
                "disappears from the Drafts folder after a force-stop.",
            1, lines.count { it == "if (showDraftBadge) {" },
        )
        assertEquals(
            "no row may go back to testing the keyword itself: that is the flag that does not " +
                "survive process death and does not exist on IMAP.",
            0, lines.count { it == "if (email.isDraft) {" },
        )
        assertEquals(
            "the parameter must default to 'showDraftBadge: Boolean = email.isDraft' — the search " +
                "results render this row with no folder-role map in hand, and the default is what " +
                "leaves them exactly as they are today.",
            1, lines.count { it == "showDraftBadge: Boolean = email.isDraft," },
        )
    }

    // -- reading the sources --------------------------------------------------------------------

    /** The `showRecipients = …` argument of the shared row renderer, up to the comma that ends it
     *  (parentheses balanced), found by name rather than by position among the row's arguments. */
    private fun showRecipientsArgument(): String {
        val body = body(INBOX_SCREEN, "emailRow")
        assertTrue(
            "the shared row renderer must pass a showRecipients argument at all — without one the " +
                "list never shows who a message went to (#59). Body was:\n$body",
            body.indexOf(SHOW_RECIPIENTS) >= 0,
        )
        return namedArgument(body, SHOW_RECIPIENTS)
    }

    /** The `<name> …` argument of a call in [body], from [name] up to the comma that ends the
     *  argument (parentheses balanced) or the end of the text. */
    private fun namedArgument(body: String, name: String): String {
        val at = body.indexOf(name)
        check(at >= 0) { "no '$name' argument in:\n$body" }
        var i = at + name.length
        var depth = 0
        while (i < body.length) {
            when (body[i]) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) return body.substring(at, i)
            }
            i++
        }
        return body.substring(at)
    }

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

    /**
     * A call's arguments, one entry each, whitespace normalised: split on the commas at the call's
     */
    private fun arguments(call: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        call.forEach { c ->
            when (c) {
                '(', '{', '[' -> { depth++; current.append(c) }
                ')', '}', ']' -> { depth--; current.append(c) }
                ',' -> if (depth == 0) { out += current.toString(); current.clear() } else current.append(c)
                else -> current.append(c)
            }
        }
        out += current.toString()
        return out.map { it.replace(Regex("""\s+"""), " ").trim() }.filter { it.isNotEmpty() }
    }

    /** The lines of [file] that are code, with comments taken off — load-bearing here, since the
     *  comments beside both call sites name the very rules the assertions forbid. */
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

    /** [file]'s code as one string, so a call the formatter wraps over four lines reads the same. */
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

    companion object {
        private const val SHOW_RECIPIENTS = "showRecipients ="
        private const val SHOW_DRAFT_BADGE = "showDraftBadge ="

        private const val APP_SOURCES = "app/src/main/kotlin"
        private const val INBOX_SCREEN_PATH = "$APP_SOURCES/app/sterna/ui/inbox/InboxScreen.kt"
        private const val MESSAGE_VIEW_MODEL_PATH = "$APP_SOURCES/app/sterna/ui/message/MessageViewModel.kt"
        private const val FOLDER_ACTIONS_PATH = "$APP_SOURCES/app/sterna/ui/FolderActions.kt"
        private const val EMAIL_LIST_ITEM_PATH = "$APP_SOURCES/app/sterna/ui/components/EmailListItem.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_SCREEN: File by lazy { File(root, INBOX_SCREEN_PATH) }
        private val MESSAGE_VIEW_MODEL: File by lazy { File(root, MESSAGE_VIEW_MODEL_PATH) }
        private val FOLDER_ACTIONS: File by lazy { File(root, FOLDER_ACTIONS_PATH) }
        private val EMAIL_LIST_ITEM: File by lazy { File(root, EMAIL_LIST_ITEM_PATH) }
    }
}
