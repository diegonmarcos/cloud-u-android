package app.sterna.ui.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST - the instrument the other `*WiringTest`s in this folder use, for
 * the one thing the owner asked three times: the composer's body is HTML, end to end. The behaviour
 * itself is executed elsewhere ([HtmlEditorStateTest], [ComposeHtmlOpeningTest], the core `ComposeHtml`
 * tests); this pins the JOINTS in the screen that a refactor could cut without a test noticing.
 */
class ComposeBodyWiringTest {

    /** (a) One editor, the HTML one. The old text field and its span bookkeeping are gone. */
    @Test fun `the body is one HtmlBodyPane`() {
        assertEquals(
            "$SCREEN must compose the body editor exactly once, as the HTML pane: a second body " +
                "field would be a message with two models.",
            1,
            lines().count { it == "HtmlBodyPane(" },
        )
        val old = listOf("bodyTransformation(", "remapAfterEdit(", "FormattingBar(", "toggleFormatting(", "visualTransformation = ")
        val offenders = lines().filter { line -> old.any { it in line } }
        assertEquals(
            "the span-model editor (a plain-text field with styling laid over it) must stay gone " +
                "from $SCREEN: it is the 'plain TEXT mode' the owner reported three times.",
            emptyList<String>(),
            offenders,
        )
    }

    /** (b) Every gesture that commits the body asks the page for its markup first. */
    @Test fun `send, schedule and save take the page's markup, not a stale copy`() {
        val commits = lines().filter { "viewModel.send(" in it || "viewModel.scheduleSend(" in it || "viewModel.saveDraft(" in it }
        assertEquals("five commit sites: send, two schedules, two saves", 5, commits.size)
        val stale = commits.filterNot { "freshRich()" in it }
        assertEquals(
            "every commit must hand the view model `freshRich()` - the page reports edits after a " +
                "short debounce, so the copy held by the screen can be one keystroke behind.",
            emptyList<String>(),
            stale,
        )
    }

    /** (c) The prefill, and the reply's quote, land as HTML and are their own baseline. */
    @Test fun `the prefill and the quote are adopted as html and as the baseline`() {
        assertPinned(
            "adoptHtml(it.bodyHtml ?: plainTextToHtml(it.body), asBaseline = true)",
            "the prefill's own markup (a reopened draft, a reply's quote and signature) is the " +
                "body, its text wrapped only when it has none - and opening it is not an edit.",
        )
        assertPinned(
            "adoptHtml(quote, asBaseline = true)",
            "a reply's quote arrives as the WHOLE opening (first line, signature, quote block) and " +
                "replaces the plain prefill as the new baseline.",
        )
    }

    /** (d) Formatting alone is an edit: the leave guard hears it. */
    @Test fun `the leave guard is given the markup`() {
        assertPinned(
            "htmlEdited = htmlIsDirty(bodyHtml, initialHtml),",
            "making a word bold does not change the text, so the guard that compares text alone " +
                "would let the screen go without a word.",
        )
    }

    /** (e) The signature is a block of the body, swapped by the page. */
    @Test fun `a signature change is a block swap in the page`() {
        assertPinned(
            "BLOCK_SIGNATURE,",
            "the signature picker and the From picker both end in `replaceBlock(BLOCK_SIGNATURE, ...)`.",
        )
    }

    // -- reading the source ---------------------------------------------------------------------

    private fun assertPinned(pinned: String, why: String, times: Int = 1) {
        val hits = lines().count { it == pinned }
        assertEquals(
            "$why\nExpected exactly $times line(s) of $SCREEN whose trimmed text is:\n    $pinned" +
                "\nbut found $hits — this lint compares the WHOLE line, so a change that only " +
                "lengthens it lands here too.",
            times, hits,
        )
    }

    /** The screen's code as trimmed whole lines, comments cut: prose must never satisfy a rule. */
    private fun lines(): List<String> = File(root, SCREEN).readLines().mapNotNull { line ->
        val code = line.trim()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(code).trim().takeIf { it.isNotBlank() }
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
                !inString && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private companion object {
        const val SCREEN = "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SCREEN).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }
    }
}
