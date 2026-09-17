package app.sterna.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — said first, because what it guards is the one part of task
 * #464 that no pure-function test can: that the composable really hands its row the new text-carried
 * read state and the tint-free background, and does so on exactly the lines this test reads.
 */
class EmailListItemBackgroundWiringTest {

    /** The call, whole, from its opening line to its closing parenthesis. Mutations 1, 2 and 3 —
     *  and, since #103, `current = current,` in its place: `current = false` there leaves the row
     *  the reading pane shows unmarked on every wide window, with [RowBackgroundTest] green. And,
     *  since #472, `card = mailPalette.card,` in its place: a literal black there is a second,
     *  untested declaration of the card colour. */
    private val expectedCall = listOf(
        "val rowColor = rowBackground(",
        "scheme = MaterialTheme.colorScheme,",
        "selected = selected,",
        "current = current,",
        "flash = highlight.value,",
        "card = mailPalette.card,",
        ")",
    )

    /** Mutation 4: the result reaches the screen through this line and nowhere else. */
    private val expectedBackground = ".background(rowColor)"

    /** The card look (#472): the row lifts itself off the pane with the declared gutters, then
     *  clips itself to the declared corner before painting. Remove any one of these lines and the
     *  list goes back to one flat sheet — the exact defect #464 shipped. */
    private val expectedGutters = ".padding(horizontal = MailListDimens.gutterH, vertical = MailListDimens.gutterV)"
    private val expectedClip = ".clip(MailListDimens.shape)"

    /** Mutation 5: what an unread row IS, for every caller that does not say. */
    private val expectedUnreadDefault = "unread: Boolean = !email.isSeen,"

    /** The read/unread text treatment of the three text lines, whole. Task #478 moved the bold
     *  weight INTO the one ink decision: the row reads colour AND weight from [mailListTextInk],
     *  so a second 'if (unread)' beside it would be exactly the parallel declaration the task
     *  exists to remove. */
    private val expectedWeight = "fontWeight = listTextInk.weight,"

    /**
     * Mutations 1, 2 and 3 at once: the arguments the composable actually hands the decision.
     */
    @Test fun `the composable hands rowBackground its own selected, current and flash`() {
        val lines = codeLines()
        val at = lines.indexOfFirst { it == expectedCall.first() }
        assertEquals(
            "EmailListItem.kt must still open the call with '${expectedCall.first()}' — this lint " +
                "reads the argument block from that line and has nothing to read without it. " +
                "Either the call moved, or it was reshaped, in which case this rule must be " +
                "taught the new shape rather than left green over a call it never saw.",
            1,
            lines.count { it == expectedCall.first() },
        )
        val found = lines.subList(at, minOf(at + expectedCall.size, lines.size))
        val mismatches = expectedCall.indices.mapNotNull { i ->
            val actual = found.getOrNull(i)
            if (actual == expectedCall[i]) null
            else "argument line ${i + 1} of the call: expected '${expectedCall[i]}' but found '$actual'"
        }
        assertEquals(
            "each argument of the rowBackground call is pinned WHOLE and in order. " +
                "'selected = unread, unread = selected' paints unread rows as selected and " +
                "selected rows as unread, on the only signal that says which rows the destructive " +
                "actions will hit; 'flash = 0f' removes the return emphasis everywhere. There is " +
                "no unread argument at all: since task #464 the row no longer signs read state by " +
                "its background, so an 'unread = …' argument here is a leftover that must go. " +
                "Mismatches:\n" + mismatches.joinToString("\n"),
            emptyList<String>(),
            mismatches,
        )
    }

    /**
     * The rule that keeps the one above honest about WHERE the decision is taken: one declaration,
     */
    @Test fun `rowBackground is declared once and called from exactly one place`() {
        assertEquals(
            "EmailListItem.kt must mention rowBackground on exactly two lines: its declaration and " +
                "its single call site. A third would be a second row background this lint does " +
                "not read; a missing one is the wiring gone.",
            listOf("internal fun rowBackground(", "val rowColor = rowBackground("),
            codeLines().filter { "rowBackground(" in it },
        )
    }

    /**
     * Mutation 4: the colour reaches the screen, and by one line only.
     */
    @Test fun `the row paints exactly that colour, on one line of its own modifier chain`() {
        val chain = rowModifierChain()
        assertEquals(
            "the row's modifier chain must carry exactly one background modifier, and it must be " +
                "'$expectedBackground' — the whole line, not a prefix of it. Dropped, the row has " +
                "no background at all: not selected, not the flash. Made translucent " +
                "(a `.copy(alpha = …)`, which any substring check accepts), the swipe reveal drawn " +
                "UNDER the row shows through it for the whole gesture. A second one paints over " +
                "the first. The chain was:\n" + chain.joinToString("\n"),
            listOf(expectedBackground),
            chain.filter { it.startsWith(".background(") },
        )
    }

    /**
     * Mutation 9 (#472): the card's separation. Without the gutters the cards touch and the list
     * is one sheet again; without the clip the corners are square. Both lines must be on the chain,
     * in this order, reading the ONE dimension declaration.
     */
    @Test fun `the row is lifted off the pane by the declared gutters and corner`() {
        val chain = rowModifierChain()
        val expected = listOf(expectedGutters, expectedClip)
        assertEquals(
            "the row's modifier chain must carry the gutter padding and the corner clip, in this " +
                "order, exactly as declared:\n" + expected.joinToString("\n") +
                "\nA card that reads the dimensions itself, or skips one, loses the separation " +
                "that #472 exists to give. The chain was:\n" + chain.joinToString("\n"),
            expected,
            chain.filter { it in expected },
        )
    }

    /**
     * Mutation 5: what "unread" means to a caller that does not say — `SearchScreen.kt` is the
     */
    @Test fun `unread defaults to the message's own seen flag`() {
        assertEquals(
            "EmailListItem's 'unread' parameter must default to '$expectedUnreadDefault'. It is " +
                "read for colour AND bold weight through the row's one ink decision, and the only " +
                "caller that omits it is the search results list — pinned at 'false' that list " +
                "shows no unread state at all, with every other rule in this repo green.",
            1,
            codeLines().count { it == expectedUnreadDefault },
        )
    }

    /**
     * Mutation 6: the unread-tint setting is gone. Until task #464 the row read LocalUnreadTint for
     * the background; the setting no longer has a job (the row's ink signs read state), so no file
     * may refer to it any more — a resurrected toggle would be a dead switch over a text cue.
     */
    @Test fun `the dead unread-tint setting is gone with its readers`() {
        assertEquals(
            "EmailListItem.kt must not mention LocalUnreadTint anywhere — the full-row unread tint " +
                "was removed in task #464 and left no job for a toggle, so any reference here is a " +
                "resurrected setting that does nothing. Lines mentioning it:\n" +
                codeLines().filter { "LocalUnreadTint" in it }.joinToString("\n"),
            emptyList<String>(),
            codeLines().filter { "LocalUnreadTint" in it },
        )
    }

    /**
     * Mutation 7: `val unread = unread && …` at the top of the composable.
     */
    @Test fun `the unread flag itself is never rewritten inside the composable`() {
        val rebound = codeLines().filter { Regex("""^val\s+unread\s*[:=]""").containsMatchIn(it) }
        assertEquals(
            "nothing in EmailListItem.kt may re-bind 'unread': it feeds the row's one ink decision " +
                "(colour AND bold weight), and narrowing it here would empty the list of its " +
                "unread cue. Found:\n" + rebound.joinToString("\n"),
            emptyList<String>(),
            rebound,
        )
    }

    /**
     * The bold weight comes from the ONE ink decision — never from a second read/unread
     * predicate. Three text lines (sender, subject, time) read [expectedWeight]; any inline
     * 'if (unread)' weight branch is a parallel declaration (#478) and imports the defect this
     * task exists to delete. Weight stays off the preview line by design.
     */
    @Test fun `the bold weight flows from the one ink decision, on three lines and no others`() {
        val weights = codeLines().filter { it == expectedWeight }
        val inlineWeights = codeLines().filter { "fontWeight = if (unread)" in it }
        assertEquals(
            "the bold weight must come from the row's ONE ink decision ([mailListTextInk], #478) " +
                "on exactly the three text lines (sender, subject, time) — any inline " +
                "'fontWeight = if (unread)' branch is a second declaration beside the colour's. " +
                "Found weight lines:\n" + weights.joinToString("\n"),
            3,
            weights.size,
        )
        assertEquals(
            "no line may branch fontWeight on 'unread' itself: the weight rides the same " +
                "declaration as the colour, so a row can never mix a bold white sender with a " +
                "regular white subject. Inline branches found:\n" + inlineWeights.joinToString("\n"),
            emptyList<String>(),
            inlineWeights,
        )
    }

    /**
     * The new text treatment (#472): an unread row is WHITE, a read one LIGHT GREY, decided once by
     * [mailListTextInk] through the palette — never per-line literals. The same decision must
     * drive all four text lines (sender, subject, time, preview) or a row would mix a white sender
     * with a grey subject and read unread as a gradient.
     */
    @Test fun `the ink colour of every message text line is the one palette decision`() {
        val decision = "val listTextInk = mailListTextInk(unread, mailPalette)"
        assertEquals(
            "the row must decide its ink ONCE through the palette, on exactly this line — a " +
                "literal here is a second declaration the palette tests cannot see (task #472)",
            1,
            codeLines().count { it == decision },
        )
        val colourRules = codeLines().filter { it == "color = listTextInk.color," }
        assertEquals(
            "every message text line (sender, subject, time, preview) must wear the palette ink — " +
                "found " + colourRules.size + " of the 4 expected:\n" + colourRules.joinToString("\n"),
            4,
            colourRules.size,
        )
    }

    // -- reading the source --------------------------------------------------------------------

    /**
     * The modifier chain of the `Row` drawn immediately after the `rowBackground` call: every line
     */
    private fun rowModifierChain(): List<String> {
        val lines = codeLines()
        val at = lines.indexOfFirst { it == expectedCall.first() }
        check(at >= 0) { "no '${expectedCall.first()}' in EmailListItem.kt" }
        val after = lines.drop(at + expectedCall.size)
        check(after.getOrNull(0) == "Row(" && after.getOrNull(1) == "modifier = modifier") {
            "the rowBackground call must be immediately followed by the row it paints — " +
                "'Row(' then 'modifier = modifier'. Found instead:\n" +
                after.take(2).joinToString("\n")
        }
        return after.drop(2).takeWhile { it.startsWith(".") }
    }

    /**
     * The lines of `EmailListItem.kt`, trimmed, with comment-only lines dropped so that no rule
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
