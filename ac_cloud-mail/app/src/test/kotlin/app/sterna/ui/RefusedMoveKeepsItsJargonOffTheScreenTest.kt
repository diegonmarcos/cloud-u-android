package app.sterna.ui

import app.sterna.R
import app.sterna.core.data.mail.ImapNumberingUnconfirmed
import app.sterna.core.imap.ImapUidValidityChanged
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A move the server refused says so in the user's language, and does not take the reader down
 */
class RefusedMoveKeepsItsJargonOffTheScreenTest {

    // -- the decision, executed ----------------------------------------------------------------

    @Test fun `a refused move in the reader shows the generic sentence too`() {
        val text = readerActionFailureText(REFUSAL, ::named)
        assertEquals(
            "report spam / not spam must say the same thing the list says",
            named(R.string.status_action_failed),
            text,
        )
        assertTrue("the exception's own text reached the reader: <$text>", JARGON !in text)
    }

    // -- the witnesses: the TEXT of every other failure is untouched ----------------------------

    @Test fun `every other failure keeps the text it already showed`() {
        // The witness without which "report everything as status_action_failed" would pass: a
        // dead network is what a bug report is made of, and it is not touched — on either surface.
        // The reader now shows this transiently instead of replacing the message with it. The
        // WORDS are what this witness holds; the surface moved on purpose, and only there.
        val offline = IOException("""Unable to resolve host "imap.example.test"""")
        assertEquals("""Unable to resolve host "imap.example.test"""", readerActionFailureText(offline, ::named))
    }

    @Test fun `a move the server answered NO to keeps its echoed command`() {
        // The case this branch made frequent (07d2abd7): a failed UID MOVE is no longer answered
        // with a destructive copy, it comes up here — carrying the command ImapClient echoes back.
        // It is NOT a numbering refusal, so its text must survive whole; only the reader's surface
        // changed, and that is what kept "Could not load message: UID MOVE …" off the screen.
        val no = IOException("""UID MOVE 1234 "Junk" failed: NO [OVERQUOTA] Quota exceeded""")
        assertEquals("""UID MOVE 1234 "Junk" failed: NO [OVERQUOTA] Quota exceeded""", readerActionFailureText(no, ::named))
    }

    @Test fun `a failure carrying no text keeps the fallback it already had`() {
        // Naming the class is what THIS site always did, and it is kept for that reason alone —
        // not to mirror the list, whose own answer is a resource for every failure and never a
        // fallback at all (`actionFailureMessage`). The two are no longer a pair.
        assertEquals("IOException", readerActionFailureText(IOException(), ::named))
    }

    @Test fun `a renumbered folder is another refusal and is not caught at the pass`() {
        // ImapUidValidityChanged means "the folder WAS renumbered", carries another message and
        // another consequence. Catching it here too would be a second fix nobody asked for.
        val renumbered = ImapUidValidityChanged("INBOX", expected = 42L, observed = 43L)
        assertEquals("UIDVALIDITY of INBOX changed from 42 to 43", readerActionFailureText(renumbered, ::named))
    }

    // -- the wiring, which nothing executable can reach -----------------------------------------

    /**
     * SOURCE RULE — the reader's site, where the leak was worse: `MessageState.Error` REPLACES the
     */
    @Test fun `the reader's actions never replace the message being read`() {
        val body = body(MESSAGE_VIEW_MODEL, "act")
        assertEquals(
            "act()'s catch must publish the decision's own words on the transient channel, and " +
                "that one line is the whole report. Body was:\n$body",
            listOf(
                "_actionStatus.value = readerActionFailureText(t) { res -> " +
                    "getApplication<Application>().getString(res) }",
            ),
            codeLinesNaming(body, "_actionStatus.value"),
        )
        assertEquals(
            "act() writes the reader's state again: a spam report that failed would destroy the " +
                "message on screen. `MessageState.Error` belongs to load(), which is the only " +
                "thing it ever described truthfully. Body was:\n$body",
            emptyList<String>(),
            codeLinesNaming(body, "_state.value"),
        )
        assertEquals(
            "same rule, said by the type: no error screen is built here. Body was:\n$body",
            emptyList<String>(),
            codeLinesNaming(body, "MessageState.Error"),
        )
        assertEquals(
            "and act() must not read the throwable's own text for itself — the decision does it, " +
                "and it is the only place that knows which text is publishable. Body was:\n$body",
            emptyList<String>(),
            codeLinesNaming(body, ".message"),
        )
    }

    /**
     * SOURCE RULE — the clear, whose BODY nothing else watches.
     */
    @Test fun `the transient word is really cleared, not merely cleared on paper`() {
        assertEquals(
            "clearActionStatus() no longer empties the channel: a second identical refusal on " +
                "the same message would then be conflated and never shown at all",
            listOf("fun clearActionStatus() { _actionStatus.value = null }"),
            body(MESSAGE_VIEW_MODEL, "clearActionStatus").lines().map { it.trim() },
        )
    }

    /**
     * SOURCE RULE — the channel is drawn, AND WHERE. A transient flow nobody collects is a refusal
     */
    @Test fun `the reader draws the transient word where it survives the tap`() {
        val lines = codeLines(MESSAGE_SCREEN)
        val declaration = "    val actionStatus by viewModel.actionStatus.collectAsStateWithLifecycle()"
        val start = lines.indexOf(declaration)
        assertTrue(
            "MessageScreen no longer collects actionStatus at the top level of the composable " +
                "(exactly `$declaration`): either the refusal is published and never shown, or " +
                "the effect has been nested inside something that is disposed when it fires",
            start > 1,
        )
        assertEquals(
            "the actionStatus effect no longer reads as \"toast it once, then clear it\", at the " +
                "composable's own indentation",
            listOf(
                "    val actionStatus by viewModel.actionStatus.collectAsStateWithLifecycle()",
                "    LaunchedEffect(actionStatus) {",
                "        val status = actionStatus ?: return@LaunchedEffect",
                "        Toast.makeText(context, status, Toast.LENGTH_SHORT).show()",
                "        viewModel.clearActionStatus()",
                "    }",
            ),
            lines.subList(start, start + 6),
        )
        assertEquals(
            "and it must sit right after the sender-rule effect, which is the proof it is in the " +
                "composable's body and not in a menu, a dialog or a lazy item that goes away with " +
                "the gesture",
            listOf("        viewModel.clearSenderRuleStatus()", "    }"),
            lines.subList(start - 2, start),
        )
        assertEquals(
            "exactly one effect watches this flow — a second one, elsewhere, is the mutation " +
                "this rule exists to catch",
            1,
            lines.count { it.trim() == "LaunchedEffect(actionStatus) {" },
        )
    }

    // -- reading the sources -------------------------------------------------------------------
    // Copied from BulkSelectionWiringTest rather than shared, for its stated reason: a helper
    // these lints agree on is a helper one edit can loosen for all of them at once.

    /** The code lines of [body] naming [needle], comments dropped, whitespace normalised. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.replace(Regex("""\s+"""), " ").trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

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

    /** The declaration of `fun`/`val` [name] in [file] and its body, as text. Fails loudly when
     *  the declaration is not found — a rename must break these rules, not satisfy them silently
     *  against an empty string. */
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

    /** A string resource, as this test names them: the id itself, so an assertion pins WHICH
     *  string was picked and not merely that one was. */
    private fun named(res: Int): String = "string:$res"

    private companion object {
        /** The refusal exactly as `ImapMailService` raises it for a swiped archive of the INBOX
         *  on a server that stated no numbering: message
         *  "The server stated no UIDVALIDITY for INBOX; a destroy frozen under null was refused". */
        val REFUSAL = ImapNumberingUnconfirmed("INBOX", null)
        const val JARGON = "The server stated no UIDVALIDITY for INBOX; a destroy frozen under null was refused"

        val repoRoot: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
                ?: error("repository root not found from ${File("").absolutePath}")
        }
        val MESSAGE_VIEW_MODEL = File(repoRoot, "app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt")
        val MESSAGE_SCREEN = File(repoRoot, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt")
    }
}
