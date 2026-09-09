package app.sterna.ui.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads source files as text, same instrument and
 */
class ForwardAttachmentWiringTest {

    @Test fun `the ViewModel never compares the raw mode string again`() {
        val raw = lines(COMPOSE_VIEW_MODEL).filter { "\"forward\"" in it || "\"replyAll\"" in it }
        assertEquals(
            "⛔ no CODE line of $COMPOSE_VIEW_MODEL_PATH may carry the literal \"forward\" or " +
                "\"replyAll\": the mode is parsed ONCE by composeOpening, and a comparison on the " +
                "raw string is a fourth place that can disagree with the other three — the very " +
                "hole that opened a quoted reply with In-Reply-To for a mode it did not know. " +
                "Lines found:",
            emptyList<String>(), raw,
        )
    }

    @Test fun `the mode literals live in composeOpening and nowhere else in the composer`() {
        // The screen (focus, signature rule) and the title had their own `mode != "forward"`:
        // three more readers that would have called a forward-as-attachment a reply — "Reply" in
        // the top bar, the caret in the body instead of To. Every reader goes through the parser.
        val literals = listOf("\"forward\"", "\"replyAll\"", "\"forwardAttachment\"")
        val found = listOf(COMPOSE_TEXT, COMPOSE_SCREEN, COMPOSE_VIEW_MODEL)
            .flatMap { f -> lines(f).filter { l -> literals.any { it in l } }.map { "${f.name}: $it" } }
        assertEquals(
            "⛔ across ComposeText.kt, ComposeScreen.kt and ComposeViewModel.kt the mode literals " +
                "may appear ONLY on the three arms of composeOpening. Any other line is a reader " +
                "that decides on the raw string, and one the parser does not know opens a " +
                "quoted reply. Lines found:",
            listOf(
                "ComposeText.kt: \"forward\" -> ComposeOpening.FORWARD",
                "ComposeText.kt: \"forwardAttachment\" -> ComposeOpening.FORWARD_ATTACHMENT",
                "ComposeText.kt: \"replyAll\" -> ComposeOpening.REPLY_ALL",
            ),
            found,
        )
    }

    @Test fun `threading is gated on the opening, once`() {
        assertPinnedLine(
            COMPOSE_VIEW_MODEL,
            "if (opening.threads) {",
            "The cache-first path sets In-Reply-To / References under this guard. On the raw " +
                "string again, forwardAttachment threads the forwarded message into the " +
                "original's thread.",
        )
    }

    @Test fun `the attached-source branch exists, once`() {
        assertPinnedLine(
            COMPOSE_VIEW_MODEL,
            "if (opening.attachesSource) {",
            "Without this branch a forward-as-attachment falls through to the reply path: " +
                "quoted body, In-Reply-To, no attachment.",
        )
    }

    @Test fun `the staged source is capped on what came back, not only on the announcement`() {
        assertPinnedLine(
            COMPOSE_VIEW_MODEL,
            "DownloadLimits.enforce(bytes.size.toLong(), DownloadLimits.ATTACHMENT_MAX_BYTES)",
            "The ceiling restated on the bytes actually held. JMAP's download and IMAP's parser " +
                "each refuse before this line today; it is what still refuses if either stops.",
        )
    }

    @Test fun `the attached-source branch hands the part to the list, and says what it is doing`() {
        // The one line that puts the .eml on screen, pinned INSIDE the branch (attach() has the
        // same line, so the file-wide count would not tell them apart). Gone, the composer opens
        // "Fwd:", empty body, no attachment and no word — the symptom itself. The status lines
        // are what keeps the wait from being silent: the composer is sendable before the part is.
        val body = attachedSourceBranch()
        for (line in listOf(
            "_attachments.value = _attachments.value + part",
            "_attachmentStatus.value = getApplication<Application>().getString(R.string.status_attaching)",
            "_attachmentStatus.value = null",
            "if (t is ContentTooLargeException) {",
            "prefillFailed()",
        )) {
            assertEquals(
                "expected exactly one line of the attachesSource branch to be:\n    $line\nfound:",
                1, body.count { it == line },
            )
        }
    }

    @Test fun `the attached-source branch never arms the dirty flag`() {
        val offenders = attachedSourceBranch().filter { "_attachmentsTouched" in it }
        assertEquals(
            "⛔ between `if (opening.attachesSource) {` and its `return@launch` no line may " +
                "name `_attachmentsTouched`. The " +
                "prefill populates the attachments without touching it (as every prefill path " +
                "does); armed here, the composer opens DIRTY and a back tap without a key typed " +
                "asks whether to discard (#70). Lines found:",
            emptyList<String>(), offenders,
        )
    }

    @Test fun `the part is staged as a message-rfc822 attachment named after the subject`() {
        // What the recipient's client shows on the line: a `.eml` of type message/rfc822, as an
        // attachment. Staged as text/plain, the same bytes arrive as a text file nobody opens as
        // a message; inline, it is rendered into the body instead of listed.
        assertPinnedLine(
            COMPOSE_VIEW_MODEL,
            "credentials, bytes, type = \"message/rfc822\",",
            "The type is what the recipient's client keys on to show the part as a message.",
        )
        assertPinnedLine(
            COMPOSE_VIEW_MODEL,
            "name = safeFileName(original.subject, \"message\") + \".eml\",",
            "Same name rule as \"Save as .eml\" (MessageScreen): the subject, made safe, plus " +
                "the extension a reader can open.",
        )
        assertPinnedLine(
            COMPOSE_VIEW_MODEL,
            "disposition = \"attachment\", cid = null,",
            "An inline disposition would render the source into the body instead of listing it.",
        )
    }

    @Test fun `the reader's menu hands the one mode string the parser knows`() {
        assertPinnedLine(
            MESSAGE_SCREEN,
            "onClick = { menuOpen = false; onReply(\"forwardAttachment\", replyTargetId, accountId) },",
            "The menu entry is the only producer of this mode. Any other spelling falls into " +
                "composeOpening's `else` and opens a quoted reply — the exact defect this volet " +
                "closes. Twice, and two entries race for the same gesture.",
        )
    }

    // -- reading the sources --------------------------------------------------------------------

    /** The code lines from `if (opening.attachesSource) {` to the `return@launch` that closes it. */
    private fun attachedSourceBranch(): List<String> {
        val all = lines(COMPOSE_VIEW_MODEL)
        val start = all.indexOf("if (opening.attachesSource) {")
        assertTrue("the branch must exist before its body can be judged", start >= 0)
        val end = all.withIndex().drop(start).first { it.value == "return@launch" }.index
        return all.subList(start, end)
    }

    /** Exactly one line of [file] whose TRIMMED code text equals [pinned] — 0 or 2+ both fail. */
    private fun assertPinnedLine(file: File, pinned: String, why: String) {
        val hits = lines(file).count { it == pinned }
        assertEquals(
            "expected exactly one CODE line of ${file.name} whose trimmed text is:\n    $pinned\n" +
                "but found $hits. The line was rewritten, removed, split or duplicated — this " +
                "lint compares the WHOLE line, so any change to it (even one that only lengthens " +
                "it) lands here. $why",
            1, hits,
        )
    }

    /** [file]'s code as trimmed WHOLE lines, comments cut, so prose can never satisfy a rule. */
    private fun lines(file: File): List<String> = code(file).lines().map { it.trim() }

    private fun code(file: File): String = file.readLines().mapNotNull { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }.joinToString("\n")

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

    companion object {
        private const val COMPOSE_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"
        private const val COMPOSE_TEXT_PATH =
            "app/src/main/kotlin/app/sterna/ui/compose/ComposeText.kt"
        private const val COMPOSE_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"
        private const val MESSAGE_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, COMPOSE_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val COMPOSE_VIEW_MODEL: File by lazy { File(root, COMPOSE_VIEW_MODEL_PATH) }
        private val COMPOSE_TEXT: File by lazy { File(root, COMPOSE_TEXT_PATH) }
        private val COMPOSE_SCREEN: File by lazy { File(root, COMPOSE_SCREEN_PATH) }
        private val MESSAGE_SCREEN: File by lazy { File(root, MESSAGE_SCREEN_PATH) }
    }
}
