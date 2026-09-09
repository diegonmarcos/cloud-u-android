package app.sterna.ui.inbox

import app.sterna.core.data.mail.EmailKey
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * A message removed FROM SterNA must take its own notification banner with it.
 */
class LocalRemovalDropsItsBannerTest {

    // -- the decision, executed --------------------------------------------------------------

    /**
     * The id is the one CAPTURED AT THE GESTURE, i.e. `UndoEntry.emailId` — not the source folder,
     */
    @Test fun `the key carries the id the gesture was performed with`() {
        val entry = UndoEntry("imap:accA:INBOX:12", "accA", "imap:accA:INBOX", "imap:accA:Trash")
        assertEquals(listOf(EmailKey("accA", "imap:accA:INBOX:12")), undoBannerKeys(listOf(entry)))
    }

    /**
     * And the account of THAT entry. A thread swipe or a selection spans accounts; each entry's
     */
    @Test fun `each entry keeps its own account`() {
        val entries = listOf(
            UndoEntry("m1", "accA", "mbA", "trashA"),
            UndoEntry("m1", "accB", "mbB", "trashB"),
            UndoEntry("m2", "accB", "mbB", null),
        )
        assertEquals(
            listOf(
                EmailKey("accA", "m1"),
                EmailKey("accB", "m1"),
                EmailKey("accB", "m2"),
            ),
            undoBannerKeys(entries),
        )
    }

    /**
     * A null account is NOT an account to invent. `UndoEntry.accountId` is nullable and the
     */
    @Test fun `a null account stays null`() {
        assertEquals(listOf(EmailKey(null, "m1")), undoBannerKeys(listOf(UndoEntry("m1", null, "mbA"))))
    }

    @Test fun `no entries, no keys`() {
        assertEquals(emptyList<EmailKey>(), undoBannerKeys(emptyList()))
    }

    // -- where the deadline is posted, read as text -------------------------------------------

    /**
     * The three sites are exactly the three that offer an Undo, and the deadline is posted THERE,
     */
    @Test fun `the swipe posts its deadline in the success arm, right after the Undo it belongs to`() {
        val body = bodyAt(INBOX_VIEW_MODEL, "private fun swipeRemove(")
        assertEquals(
            "swipeRemove() must post exactly one banner deadline. Body was:\n$body",
            listOf(DEADLINE),
            codeLinesNaming(body, "scheduleUndoneDeparture("),
        )
        assertEquals(
            "the deadline must sit in the .onSuccess arm, immediately after the Undo it belongs " +
                "to — never before the runCatching, never in .onFailure: offline the op fails, the " +
                "row is restored, and the banner would be gone for a departure that never " +
                "happened. Body was:\n$body",
            listOf(
                "_undo.value = UndoAction(listOf(UndoEntry(email.id, email.accountId, source, dest)), label)",
                DEADLINE,
            ),
            deadlineWithItsNeighbour(body),
        )
    }

    @Test fun `the thread swipe posts its deadline right after the Undo it belongs to`() {
        val body = bodyAt(INBOX_VIEW_MODEL, "private fun threadSwipeRemove(")
        assertEquals(
            "threadSwipeRemove() must post exactly one banner deadline. Body was:\n$body",
            listOf(DEADLINE),
            codeLinesNaming(body, "scheduleUndoneDeparture("),
        )
        assertEquals(
            "the deadline must follow the Undo offer, which is built from the entries the server " +
                "ACKNOWLEDGED (the .onSuccess arm feeds them). Body was:\n$body",
            listOf(
                "_undo.value = UndoAction(entries, getApplication<Application>().getString(labelRes))",
                DEADLINE,
            ),
            deadlineWithItsNeighbour(body),
        )
    }

    @Test fun `the batched selection posts its deadline right after the Undo it belongs to`() {
        val body = bodyAt(INBOX_VIEW_MODEL, "private fun bulkBatched(")
        assertEquals(
            "bulkBatched() must post exactly one banner deadline. Body was:\n$body",
            listOf(DEADLINE),
            codeLinesNaming(body, "scheduleUndoneDeparture("),
        )
        assertEquals(
            "the deadline must follow the Undo offer, i.e. the messages the SERVER wrote. " +
                "Body was:\n$body",
            listOf("_undo.value = UndoAction(undoEntries, label)", DEADLINE),
            deadlineWithItsNeighbour(body),
        )
    }

    /**
     * The deadline is a `viewModelScope` coroutine, and its body is pinned as a SEQUENCE — every
     */
    @Test fun `the deadline waits, closes its own window, then dismisses — in that order`() {
        val body = bodyAt(INBOX_VIEW_MODEL, "private fun scheduleUndoneDeparture()")
        assertEquals(
            "the deadline's body is pinned WHOLE and IN ORDER: it must wait the named window, " +
                "close the very window it was posted for (identity, not equality), and only then " +
                "take the banners down. Any other order or any missing line reverses what the " +
                "branch promises. Body was:\n$body",
            listOf(
                "private fun scheduleUndoneDeparture() {",
                "val action = _undo.value ?: return",
                "val keys = undoBannerKeys(action.entries)",
                "undoDepartureJob = viewModelScope.launch {",
                "delay(UNDO_BANNER_DISMISS_MS)",
                "if (_undo.value === action) _undo.value = null",
                "dismissBanners(keys)",
                "}",
                "}",
            ),
            normalised(body),
        )
    }

    /**
     * And the window has a VALUE, not just a name. `UNDO_BANNER_DISMISS_MS = 0L` is one character
     */
    @Test fun `the window is a real value, not just a name`() {
        assertEquals(
            "the Undo window must be a named constant with an actual duration behind it: the " +
                "snackbar is Indefinite now, so this value alone decides how long the user has.",
            listOf("private const val UNDO_BANNER_DISMISS_MS = 6_000L"),
            fileLinesNaming(INBOX_VIEW_MODEL, "UNDO_BANNER_DISMISS_MS ="),
        )
    }

    /**
     * ONE clock, and it is the ViewModel's.
     */
    @Test fun `the Undo bar does not run a clock of its own`() {
        val body = bodyAt(INBOX_SCREEN, "LaunchedEffect(undo) {")
        assertEquals(
            "the Undo snackbar must not carry a duration of its own — the ViewModel's deadline is " +
                "the only clock, and it closes the window by clearing _undo. Body was:\n$body",
            listOf("duration = SnackbarDuration.Indefinite,"),
            codeLinesNaming(body, "duration ="),
        )
        assertEquals(
            "…and the two outcomes stay exactly what they were: the button undoes, anything else " +
                "(swipe, close button) leaves the deadline running. Body was:\n$body",
            listOf(
                "if (result == SnackbarResult.ActionPerformed) {",
                "viewModel.undo()",
                "revealTopSignal++",
                "} else viewModel.clearUndo()",
            ),
            normalised(body).dropLast(1).takeLast(4),
        )
    }

    /**
     * Undo cancels the deadline, and nothing else does. Without this, the user presses Undo, the
     */
    @Test fun `Undo cancels the deadline`() {
        val body = bodyAt(INBOX_VIEW_MODEL, "fun undo()")
        assertEquals(
            "undo() must cancel the pending banner deadline: the gesture was reversed, so the " +
                "banner must stay. Body was:\n$body",
            listOf("undoDepartureJob?.cancel()"),
            codeLinesNaming(body, "undoDepartureJob"),
        )
    }

    /**
     * And `clearUndo()` carries neither the deadline nor its cancellation. It is the screen's
     */
    @Test fun `clearUndo carries neither the deadline nor its cancellation`() {
        val body = bodyAt(INBOX_VIEW_MODEL, "fun clearUndo()")
        assertEquals(
            "clearUndo() must not post the deadline — it is the screen's branch, and it does not " +
                "run at all when the list leaves the composition. Body was:\n$body",
            emptyList<String>(),
            codeLinesNaming(body, "scheduleUndoneDeparture("),
        )
        assertEquals(
            "…nor cancel it: the bar timing out is precisely when the banner must go. " +
                "Body was:\n$body",
            emptyList<String>(),
            codeLinesNaming(body, "undoDepartureJob"),
        )
    }

    // -- reading the sources ------------------------------------------------------------------
    // Deliberately duplicated from the other lints rather than shared: a helper they all agree on
    // is a helper one edit can loosen for all of them at once.

    /** The deadline call, spelled once so the three sites cannot drift apart. */
    private val DEADLINE = "scheduleUndoneDeparture()"

    /** [DEADLINE]'s line and the one directly above it, whitespace-normalised, in order. */
    private fun deadlineWithItsNeighbour(body: String): List<String> {
        val lines = normalised(body)
        val at = lines.indexOfFirst { it == DEADLINE }
        if (at < 0) return listOf("<no $DEADLINE in this body>")
        return listOf(lines.getOrElse(at - 1) { "<nothing above>" }, lines[at])
    }

    private fun normalised(body: String): List<String> =
        body.lines().map { it.replace(Regex("""\s+"""), " ").trim() }
            .filterNot { it.isBlank() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    /** The code lines of [body] naming [needle], comments dropped, whitespace normalised. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        normalised(body).filter { needle in it }

    /** The code lines of the whole [file] naming [needle], same normalisation. */
    private fun fileLinesNaming(file: File, needle: String): List<String> =
        codeLines(file).map { it.replace(Regex("""\s+"""), " ").trim() }.filter { needle in it }

    /** The lines of [file] that are code, with comments taken off. */
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

    /**
     * The declaration [declaration] in [file] and its body, as text. Matched on the DECLARATION and
     */
    private fun bodyAt(file: File, declaration: String): String {
        val lines = codeLines(file)
        val start = lines.indexOfFirst { it.trimStart().startsWith(declaration) }
        check(start >= 0) { "${file.name} declares no '$declaration' — did it get renamed?" }
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
        private const val INBOX_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        /** Repo root, walked up from the module's working directory. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private const val INBOX_SCREEN_PATH = "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt"

        private val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
        private val INBOX_SCREEN: File by lazy { File(root, INBOX_SCREEN_PATH) }
    }
}
