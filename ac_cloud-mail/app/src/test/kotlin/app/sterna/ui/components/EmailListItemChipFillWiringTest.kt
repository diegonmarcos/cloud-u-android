package app.sterna.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — said first, because what it guards is the one part of the
 */
class EmailListItemChipFillWiringTest {

    /** The call, whole, from its opening line to its closing parenthesis. Mutations 2 and 3. */
    private val expectedCall = listOf(
        "val chipBackground = chipFill(",
        "scheme = MaterialTheme.colorScheme,",
        "selected = selected,",
        "unread = unread,",
        "unreadTint = unreadTint,",
        ")",
    )

    /** Site 1 of 3: the origin chip — the account in the unified inbox, the folder in the unread
     *  view (#169) — whose accent branch must stay untouched. The accent only ever comes from an
     *  ACCOUNT; a folder has none and takes the fallback fill this rule guards. */
    private val expectedAccountChip = listOf(
        ".background(",
        "if (originColor != null) originColor.copy(alpha = 0.16f)",
        "else chipBackground,",
        ")",
    )

    /** Site 2 of 3: the collapsed-thread pill. */
    private val expectedThreadPillCall = listOf(
        "ThreadPill(",
        "count = threadCount,",
        "expanded = expanded,",
        "onToggleExpand = onToggleExpand,",
        "fill = chipBackground,",
        ")",
    )

    /** Site 3 of 3: the "(Draft)" chip. */
    private val expectedDraftLabelCall = "DraftLabel(fill = chipBackground)"

    /**
     * Every background painted in this file, in source order: the row itself, the account chip's
     */
    private val expectedBackgrounds = listOf(
        ".background(rowColor)",
        ".background(",
        ".background(fill)",
        ".background(fill)",
        ".background(fill)",
    )

    /**
     * Mutations 2 and 3: the arguments the composable actually hands the decision.
     */
    @Test fun `the composable hands chipFill its own selected and unread`() {
        val lines = codeLines()
        assertEquals(
            "EmailListItem.kt must still open the call with '${expectedCall.first()}' — this lint " +
                "reads the argument block from that line and has nothing to read without it. " +
                "Either the call moved, or it was reshaped, in which case this rule must be " +
                "taught the new shape rather than left green over a call it never saw.",
            1,
            lines.count { it == expectedCall.first() },
        )
        assertBlock(expectedCall, "the chipFill call")
    }

    /**
     * The rule that keeps the one above honest about WHERE the decision is taken: one declaration,
     */
    @Test fun `chipFill is declared once and called from exactly one place`() {
        assertEquals(
            "EmailListItem.kt must mention chipFill on exactly two lines: its declaration and its " +
                "single call site. A third would be a chip taking the decision again on its own; " +
                "a missing one is the wiring gone.",
            listOf("internal fun chipFill(", "val chipBackground = chipFill("),
            codeLines().filter { "chipFill(" in it },
        )
    }

    /**
     * Site 1 of 3, and mutation 4 with it: the account chip falls back to the row-aware fill,
     */
    @Test fun `the account chip falls back to the row-aware fill`() {
        assertBlock(expectedAccountChip, "the account chip's background")
    }

    /** Site 2 of 3: the thread pill is handed the fill instead of reading the theme itself. */
    @Test fun `the thread pill is given the row's fill`() {
        assertBlock(expectedThreadPillCall, "the ThreadPill call")
        assertEquals(
            "ThreadPill must take the fill as a parameter ('fill: Color,') rather than read the " +
                "theme itself: a pill that reads surfaceVariant on its own cannot know it is " +
                "sitting on an unread row, which is the whole defect.",
            1,
            codeLines().count { it == "fill: Color," },
        )
    }

    /** Site 3 of 3: the draft chip, the one a lint of the other two would leave erased. */
    @Test fun `the draft label is given the row's fill`() {
        assertEquals(
            "EmailListItem.kt must call the draft chip as '$expectedDraftLabelCall' — the whole " +
                "line. Left as 'DraftLabel()' reading surfaceVariant itself, '(Draft)' keeps " +
                "floating with no chip behind it on unread rows while the other two chips are " +
                "fixed, and nothing else in this repo goes red.",
            1,
            codeLines().count { it == expectedDraftLabelCall },
        )
        assertEquals(
            "DraftLabel must declare 'private fun DraftLabel(fill: Color) {'.",
            1,
            codeLines().count { it == "private fun DraftLabel(fill: Color) {" },
        )
    }

    /** Site 4, added with #95: the "not uploaded" chip, which sits right next to site 3. */
    @Test fun `the not-uploaded label is given the row's fill too`() {
        assertEquals(
            "EmailListItem.kt must call the chip as 'NotUploadedLabel(fill = chipBackground)' — the " +
                "whole line, for the same reason as the draft chip beside it.",
            1,
            codeLines().count { it == "NotUploadedLabel(fill = chipBackground)" },
        )
        assertEquals(
            "NotUploadedLabel must declare 'private fun NotUploadedLabel(fill: Color) {'.",
            1,
            codeLines().count { it == "private fun NotUploadedLabel(fill: Color) {" },
        )
    }

    /**
     * Mutation 5, plus mutation 1 for both pills at once: every background modifier in the file,
     */
    @Test fun `the file paints exactly these backgrounds and no others`() {
        assertEquals(
            "the backgrounds painted in EmailListItem.kt must be exactly these four lines, whole " +
                "and in this order: the row, the account chip's two-branch block, then the thread " +
                "pill and the draft chip painting the fill they were handed. A pill still on " +
                "'MaterialTheme.colorScheme.surfaceVariant' shows up here as a mismatch, and so " +
                "does any '.copy(alpha = …)' a substring check would have accepted.",
            expectedBackgrounds,
            codeLines().filter { it.startsWith(".background(") },
        )
    }

    /**
     * The negative screen — a `contains`, deliberately, because it is looking for something that
     */
    @Test fun `no composable in the file reads surfaceVariant off the theme`() {
        assertEquals(
            "every chip's fill now comes from chipFill, so no line of EmailListItem.kt may read " +
                "'MaterialTheme.colorScheme.surfaceVariant' directly — a chip that reads the role " +
                "itself is a chip that does not know which row it is on.",
            emptyList<String>(),
            codeLines().filter { "colorScheme.surfaceVariant" in it },
        )
    }

    // -- reading the source --------------------------------------------------------------------

    /**
     * Locates [expected]'s first line — which must appear exactly once — and compares the block
     */
    private fun assertBlock(expected: List<String>, what: String) {
        val lines = codeLines()
        val at = lines.indexOfFirst { it == expected.first() }
        check(at >= 0) { "no '${expected.first()}' in EmailListItem.kt — $what is gone" }
        val found = lines.subList(at, minOf(at + expected.size, lines.size))
        val mismatches = expected.indices.mapNotNull { i ->
            val actual = found.getOrNull(i)
            if (actual == expected[i]) null
            else "line ${i + 1} of $what: expected '${expected[i]}' but found '$actual'"
        }
        assertEquals(
            "$what is pinned WHOLE, line by line and in order. Nothing in this repo executes these " +
                "lines. Mismatches:\n" + mismatches.joinToString("\n"),
            emptyList<String>(),
            mismatches,
        )
    }

    /**
     * The lines of `EmailListItem.kt`, trimmed, with comment-only lines dropped so that no rule
     * here can be satisfied — or defeated — by prose.
     */
    private fun codeLines(): List<String> = SOURCE.readLines().map { it.trim() }.filterNot { line ->
        line.isEmpty() || line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")
    }

    private companion object {
        val SOURCE: File by lazy {
            val relative = "app/src/main/kotlin/app/sterna/ui/components/EmailListItem.kt"
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, relative) }
                .firstOrNull { it.isFile }
                ?: error(
                    "cannot locate $relative from ${File("").absolutePath} — this lint reads the " +
                        "composable as text and needs a working directory inside the checkout",
                )
        }
    }
}
