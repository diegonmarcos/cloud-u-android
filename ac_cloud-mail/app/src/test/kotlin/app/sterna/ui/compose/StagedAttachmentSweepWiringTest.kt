package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads `ComposeScreen.kt` and `ComposeViewModel.kt` as text
 */
class StagedAttachmentSweepWiringTest {

    // -- the screen: when the sweep happens ------------------------------------------------------

    /**
     * ON_START, AND THE OBSERVER IS TAKEN BACK OFF. The bytes disappear while the composer is in
     */
    @Test
    fun `the sweep is armed as the composer comes back to the front`() {
        val block = observerBlock()
        assertEquals(
            "The composer must sweep its staged attachments on ON_START, through the ViewModel, and " +
                "must remove the observer in onDispose. Compared whole, because 'ON_START' is " +
                "contained in a line that also tests something else, and a missing onDispose is a " +
                "deletion no executed test can see.",
            EXPECTED_OBSERVER_BLOCK,
            block,
        )
    }

    /** The owner is the screen's own, not the activity's: one line, whole. */
    @Test
    fun `the observer is hung on the screen's own lifecycle owner`() {
        val lines = codeLines(COMPOSE_SCREEN).map { it.trim() }
        assertEquals(
            "ComposeScreen must take the lifecycle owner from the composition, exactly once.",
            listOf("val lifecycleOwner = LocalLifecycleOwner.current"),
            lines.filter { it.startsWith("val lifecycleOwner ") },
        )
    }

    /** And it is the ViewModel that is asked, from that one line and nowhere else. */
    @Test
    fun `the screen calls the sweep from exactly one place`() {
        val lines = codeLines(COMPOSE_SCREEN).map { it.trim() }
        assertEquals(
            "the screen must ask the ViewModel to sweep from the ON_START arm and from nowhere " +
                "else — a second call site is a second copy of the wiring.",
            listOf("if (event == Lifecycle.Event.ON_START) viewModel.dropVanishedAttachments()"),
            lines.filter { it.contains("dropVanishedAttachments") },
        )
    }

    // -- the ViewModel: what the sweep is given, and what it does with the answer ------------------

    /**
     * THE ARGUMENTS, NOT THE NAME. [sweepStagedAttachments] only knows what is staged because it
     */
    @Test
    fun `the ViewModel hands the sweep the staging root and the real filesystem`() {
        assertEquals(
            "ComposeViewModel.dropVanishedAttachments must read exactly these lines. It is the only " +
                "place that knows where staging lives (`File(cacheDir, \"outgoing\")`, the same " +
                "directory stageOutgoing writes into) and the only place that can hand over the real " +
                "`File::exists`.",
            EXPECTED_VIEW_MODEL_BODY,
            dropBodyLines(),
        )
    }

    /**
     * THE LINE THAT IS NOT AN OPTIMISATION — take it out and the app destroys mail.
     */
    @Test
    fun `the sweep stands down while this composer holds the only copy`() {
        assertEquals(
            "dropVanishedAttachments must return FIRST, before it touches anything, when this " +
                "composer is editing a server draft or a queued outbox item. This line is not an " +
                "optimisation: without it the sweep makes the faithfulness count agree, and the " +
                "save goes on to destroy the server original (#63) / the send goes on to destroy " +
                "the durable outbox copy (#70). Removing it makes the app destroy the only copy of " +
                "an attachment. Body was:\n${dropBodyLines().joinToString("\n")}",
            "if (editingDraftId != null || editingOutboxId != null) return",
            dropBodyLines().firstOrNull(),
        )
    }

    /**
     * THE EDIT FLAG, NAMED ON ITS OWN because the price of dropping it is invisible on screen.
     */
    @Test
    fun `a removal by sweep counts as an edit`() {
        assertTrue(
            "dropVanishedAttachments must arm _attachmentsTouched, exactly as removeAttachment " +
                "does. Body was:\n${dropBodyLines().joinToString("\n")}",
            "_attachmentsTouched.value = true" in dropBodyLines(),
        )
    }

    /**
     * AND IT DOES NOT BLAME THE NETWORK. `attach` routes its failure through `isOfflineFailure`
     */
    @Test
    fun `the sentence is not routed through the offline verdict`() {
        val body = dropBodyLines().joinToString("\n")
        assertTrue(
            "a file that is no longer on the phone is not a connectivity failure; " +
                "status_attach_offline would accuse the network of something it did not do. " +
                "Body was:\n$body",
            "status_attach_offline" !in body && "isOfflineFailure" !in body,
        )
    }

    /**
     * The sentence exists and is the app's, not a literal. Only the default set is checked here;
     * `TranslationParityTest` is what makes the other eight languages carry it too.
     */
    @Test
    fun `the sentence is a string resource and it is declared`() {
        val strings = File(root, "app/src/main/res/values/strings.xml").readText()
        assertTrue(
            "status_attach_vanished must exist in values/strings.xml — the ViewModel resolves it " +
                "by id and a missing one does not compile, but a RENAMED one that another screen " +
                "already uses would.",
            "<string name=\"status_attach_vanished\">" in strings,
        )
    }

    // -- reading the source ------------------------------------------------------------------------

    /**
     * The `DisposableEffect` in `ComposeScreen.kt` that mentions the sweep, from the call to the
     */
    private fun observerBlock(): String {
        val text = codeText(COMPOSE_SCREEN)
        val hits = Regex("""\bDisposableEffect\s*\(""").findAll(text)
            .map { balancedFrom(text, it.range.first) }
            .filter { it.contains("dropVanishedAttachments") }
            .toList()
        check(hits.size == 1) {
            "ComposeScreen.kt is expected to hold exactly one DisposableEffect that sweeps the " +
                "staged attachments. Found ${hits.size}: was it removed, renamed, or duplicated?"
        }
        return hits.single()
    }

    /** The body of `fun dropVanishedAttachments()` in the ViewModel, as trimmed code lines. */
    private fun dropBodyLines(): List<String> {
        val lines = codeLines(COMPOSE_VIEW_MODEL)
        val at = lines.indexOfFirst { it.trim() == "fun dropVanishedAttachments() {" }
        check(at >= 0) {
            "ComposeViewModel.kt declares no `fun dropVanishedAttachments() {` — the composer has " +
                "nothing to ask when it comes back to the front, and the stale chip is back."
        }
        var depth = 1
        val body = mutableListOf<String>()
        for (line in lines.drop(at + 1)) {
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth <= 0) return body
            body += line.trim()
        }
        error("the body of dropVanishedAttachments never closes")
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

    /** The lines of [file] that are code, with comments taken off. */
    private fun codeLines(file: File): List<String> = file.readLines().mapNotNull { line ->
        val code = line.trimStart()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }

    /** [file] as ONE line of code, so a call the formatter spread over eight lines reads as one. */
    private fun codeText(file: File): String =
        codeLines(file).joinToString(" ").replace(Regex("""\s+"""), " ").trim()

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
        /** The observer, whole — the shape InboxScreen.kt already uses for its own ON_START. */
        private const val EXPECTED_OBSERVER_BLOCK =
            "DisposableEffect(lifecycleOwner) { " +
                "val observer = LifecycleEventObserver { _, event -> " +
                "if (event == Lifecycle.Event.ON_START) viewModel.dropVanishedAttachments() " +
                "} " +
                "lifecycleOwner.lifecycle.addObserver(observer) " +
                "onDispose { lifecycleOwner.lifecycle.removeObserver(observer) } " +
                "}"

        /** The ViewModel method, whole. */
        private val EXPECTED_VIEW_MODEL_BODY = listOf(
            "if (editingDraftId != null || editingOutboxId != null) return",
            "val app = getApplication<Application>()",
            "val sweep = sweepStagedAttachments(_attachments.value, File(app.cacheDir, \"outgoing\"), File::exists)",
            "if (sweep.gone.isEmpty()) return",
            "_attachments.value = sweep.kept",
            "_attachmentsTouched.value = true",
            "_attachmentStatus.value = app.getString(R.string.status_attach_vanished)",
        )

        private const val SCREEN_PATH = "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"
        private const val VIEW_MODEL_PATH = "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        private val COMPOSE_SCREEN: File by lazy { File(root, SCREEN_PATH) }
        private val COMPOSE_VIEW_MODEL: File by lazy { File(root, VIEW_MODEL_PATH) }
    }
}
