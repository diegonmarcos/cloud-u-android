package app.sterna.ui.inbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — read this before trusting it.
 */
class ReadingPaneWiringLintTest {

    @Test fun `the reader is built under the session key, and the list source right inside it`() {
        assertEquals(
            "the two lines that make the reading pane a reading must sit together, in this order: " +
                "`key(state.session) {` and, first inside it, the `listSource` line. ⚠ An empty " +
                "`but was []` is this rule's way of saying that the line `key(state.session) {` is " +
                "no longer in ReadingPane.kt verbatim — replaced by a plain `run {`, renamed, or " +
                "keyed on something else, written twice, or simply followed by an appended " +
                "`//` comment — so the pair cannot be looked up at all. That is the " +
                "damage the user reports as a stuck pane: `key(session)` is what makes a tap a NEW " +
                "reading, recreating the reader, its per-page stores, its frozen snapshots and its " +
                "pager on its initial page. Without it, on a 1 200 dp window, the FIRST message " +
                "opened stays in the pane forever — every later tap only repaints the current row " +
                "in the list and changes nothing on the right. And should `listSource` stop being " +
                "the first line inside the key, the reader is handed its five arguments from " +
                "outside the session that owns them.",
            listOf(KEY_LINE, LIST_SOURCE_LINE),
            adjacentPair(codeLines(READING_PANE), KEY_LINE),
        )
    }

    @Test fun `only a list tap gets the live paged flow of the inbox`() {
        assertEquals(
            "`listSource` must be the inbox's live paged flow for a LIST tap and nothing else, " +
                "compared whole because every weaker guard is contained in this line. Widened — " +
                "`anchor.src != null` is the mutation that compiles — a search hit or an unfolded " +
                "conversation is handed the inbox's pager instead of its own snapshot: the pane " +
                "opens a different message from the one the finger touched and, the pager having " +
                "settled on that page, MARKS IT READ. Nobody chose to open it, which is exactly " +
                "what this file's KDoc and SECURITY.md forbid.",
            listOf(LIST_SOURCE_LINE),
            codeLines(READING_PANE).filter { it.startsWith("val listSource") },
        )
    }

    @Test fun `the only key in the file is the session key, whole`() {
        assertEquals(
            "every line of ReadingPane.kt that names `key(` must be the session key, WHOLE and " +
                "alone. None at all: a tap is no longer a new reading and the pane keeps the " +
                "first message opened forever. A second one: some other key answers for this " +
                "one — a nested `key(...)` tears down part of the reader when the session did " +
                "NOT move, reloading page bodies under a finger that only swiped. And the line " +
                "is compared rather than counted because a count is blind to a LENGTHENED key: " +
                "`key(state.session, anchor.emailId) {` recreates the reader on every swipe " +
                "inside the pane, which is the pager thrown away under the finger.",
            listOf(KEY_LINE),
            codeLines(READING_PANE).filter { "key(" in it },
        )
    }

    private companion object {
        const val KEY_LINE = "key(state.session) {"

        const val LIST_SOURCE_LINE =
            "val listSource = if (anchor.src == \"list\") inboxViewModel.pagedEmails else null"

        /**
         * The single line equal to [needle] and the one after it. Empty when [needle] is not
         */
        fun adjacentPair(lines: List<String>, needle: String): List<String> {
            val at = lines.indices.filter { lines[it] == needle }.singleOrNull() ?: return emptyList()
            return if (at + 2 > lines.size) emptyList() else lines.subList(at, at + 2)
        }

        /** [file]'s lines, trimmed, with comment-only lines dropped so no rule is met by prose. */
        fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
            it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
        }

        const val READING_PANE_PATH = "app/src/main/kotlin/app/sterna/ui/inbox/ReadingPane.kt"

        val READING_PANE: File by lazy {
            val root = generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, READING_PANE_PATH).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
            File(root, READING_PANE_PATH)
        }
    }
}
