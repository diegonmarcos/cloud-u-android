package app.sterna.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — said first, because what it guards is the one part of #141
 */
class EmailListItemBackgroundWiringTest {

    /** The call, whole, from its opening line to its closing parenthesis. Mutations 1, 2 and 3 —
     *  and, since #103, `current = current,` in its place: `current = false` there leaves the row
     *  the reading pane shows unmarked on every wide window, with [RowBackgroundTest] green. */
    private val expectedCall = listOf(
        "val rowColor = rowBackground(",
        "scheme = MaterialTheme.colorScheme,",
        "selected = selected,",
        "current = current,",
        "unread = unread,",
        "unreadTint = unreadTint,",
        "flash = highlight.value,",
        ")",
    )

    /** The setting reaches the row through this line and no other. Mutations 6 and 7 below. */
    private val expectedTintRead = "val unreadTint = LocalUnreadTint.current"

    /** Mutation 4: the result reaches the screen through this line and nowhere else. */
    private val expectedBackground = ".background(rowColor)"

    /** Mutation 5: what an unread row IS, for every caller that does not say. */
    private val expectedUnreadDefault = "unread: Boolean = !email.isSeen,"

    /**
     * Mutations 1, 2 and 3 at once: the arguments the composable actually hands the decision.
     */
    @Test fun `the composable hands rowBackground its own selected, unread and flash`() {
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
                "'unread = false' brings #141 back with the pure function still perfect; " +
                "'selected = unread, unread = selected' paints unread rows as selected and " +
                "selected rows as unread, on the only signal that says which rows the destructive " +
                "actions will hit; 'flash = 0f' removes the return emphasis everywhere. Nothing " +
                "else in this repo executes these six lines. Mismatches:\n" +
                mismatches.joinToString("\n"),
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
                "no background at all: not unread, not selected, not the flash. Made translucent " +
                "(a `.copy(alpha = …)`, which any substring check accepts), the swipe reveal drawn " +
                "UNDER the row shows through it for the whole gesture. A second one paints over " +
                "the first. The chain was:\n" + chain.joinToString("\n"),
            listOf(expectedBackground),
            chain.filter { it.startsWith(".background(") },
        )
    }

    /**
     * Mutation 5: what "unread" means to a caller that does not say — `SearchScreen.kt` is the
     */
    @Test fun `unread defaults to the message's own seen flag`() {
        assertEquals(
            "EmailListItem's 'unread' parameter must default to '$expectedUnreadDefault'. It is " +
                "read three times for the bold weight and once for the background, and the only " +
                "caller that omits it is the search results list — pinned at 'false' that list " +
                "shows no unread state at all, with every other rule in this repo green.",
            1,
            codeLines().count { it == expectedUnreadDefault },
        )
    }

    /**
     * Mutation 6: the row asks the CompositionLocal for the setting once, at the top, and by
     */
    @Test fun `the row reads the setting once, from the CompositionLocal`() {
        assertEquals(
            "EmailListItem.kt must read the setting on exactly one line, and it must be " +
                "'$expectedTintRead' — whole, not a prefix. Wrapped in a remember {} the row " +
                "freezes on its first value and the list ends up half tinted; read twice, the two " +
                "decisions can disagree on one row. Lines mentioning it:\n" +
                codeLines().filter { "LocalUnreadTint" in it }.joinToString("\n"),
            listOf(expectedTintRead),
            codeLines().filter { "LocalUnreadTint" in it },
        )
    }

    /**
     * Mutation 7: `val unread = unread && unreadTint` at the top of the composable.
     */
    @Test fun `the unread flag itself is never rewritten inside the composable`() {
        val rebound = codeLines().filter { Regex("""^val\s+unread\s*[:=]""").containsMatchIn(it) }
        assertEquals(
            "nothing in EmailListItem.kt may re-bind 'unread': it also drives the bold weight in " +
                "three places, and the setting is about the BACKGROUND. Narrowing it here turns " +
                "one switch into two effects, with the whole suite green. Found:\n" +
                rebound.joinToString("\n"),
            emptyList<String>(),
            rebound,
        )
    }

    /**
     * Mutation 8, and the reason the rule above is not enough: leave `unread` alone and narrow
     */
    @Test fun `the bold weight is decided by unread alone, on three lines and no others`() {
        val weights = codeLines().filter { it.startsWith("fontWeight = if (") }
        assertEquals(
            "the bold weight of a list row must read 'unread' and nothing else — the switch " +
                "governs the BACKGROUND. Any other condition here (unreadTint, a derived flag) " +
                "makes one box do two things and empties the list of every unread cue at once, " +
                "with the whole suite green. Found:\n" + weights.joinToString("\n"),
            List(3) { "fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal," },
            weights,
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
