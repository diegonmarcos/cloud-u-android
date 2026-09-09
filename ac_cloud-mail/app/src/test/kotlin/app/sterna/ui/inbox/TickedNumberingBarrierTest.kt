package app.sterna.ui.inbox

import app.sterna.core.data.mail.EmailKey
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE RACE BETWEEN THE TICK AND THE TAP (#99, #189).
 */
class TickedNumberingBarrierTest {

    private val a = EmailKey("acc", "imap:acc:INBOX:1")
    private val b = EmailKey("acc", "imap:acc:INBOX:2")
    private val c = EmailKey("other", "imap:other:INBOX:3")

    // ---- the decision, RUN ---------------------------------------------------------------------

    /**
     * Four inputs, four verdicts, stated as literals: this test does not recompute the rule it
     */
    @Test fun `a map covers a selection when every key of it has an entry`() {
        assertTrue(
            "every awaited key has an entry — including b, whose entry is null: that is an ANSWER " +
                "(the row carries no numbering), not a gap still being read",
            numberingStampsCover(setOf(a, b), mapOf(a to 7L, b to null)),
        )
        assertFalse(
            "⛔ THE RACE ITSELF: b has not been read back yet. Accepting this is accepting a " +
                "half-read selection, which is what the gesture used to do",
            numberingStampsCover(setOf(a, b), mapOf(a to 7L)),
        )
        assertTrue(
            "a key the selection no longer holds is not a reason to wait: the map may legitimately " +
                "carry more than was asked for",
            numberingStampsCover(setOf(a), mapOf(a to 7L, c to 9L)),
        )
        assertTrue("nothing awaited is covered by anything, empty included", numberingStampsCover(emptySet(), emptyMap()))
        assertFalse("and an empty map covers nothing that was asked for", numberingStampsCover(setOf(a), emptyMap()))
    }

    // ---- the wait, RUN ------------------------------------------------------------------------

    /**
     * THE WITNESS OF THE RACE, and the only test that tells a barrier apart from a `delay`: the
     */
    @Test fun `the gesture gets the map published after it asked, not the partial one that was there`() = runTest {
        val published = MutableStateFlow<Map<EmailKey, Long?>>(mapOf(a to 7L))

        val gesture = async { tickedNumberingWhenCovered(setOf(a, b), published) }
        runCurrent()
        assertTrue(
            "⛔ the gesture must be HELD: half the selection has been read back. Taking the map " +
                "here is what let a Move go out with no stamp for b — a silent loss before the " +
                "refusal volet, a visible failure since",
            gesture.isActive,
        )

        published.value = mapOf(a to 7L, b to null)

        assertEquals(
            "and once the tick's own read is published, the gesture gets THAT map — b's null " +
                "included, which is a read answer and not a gap",
            mapOf(a to 7L, b to null),
            gesture.await(),
        )
    }

    /**
     * THE INVERSE WITNESS, without which "always sleep for the budget" satisfies the one above
     */
    @Test fun `an already covered map is handed over without spending a single tick`() = runTest {
        val published = MutableStateFlow<Map<EmailKey, Long?>>(mapOf(a to 7L, b to 9L))
        val before = testScheduler.currentTime

        assertEquals(mapOf(a to 7L, b to 9L), tickedNumberingWhenCovered(setOf(a, b), published))
        assertEquals(
            "a barrier returns the instant it is satisfied; a delay would not. The ordinary " +
                "gesture — a few rows ticked one by one, read long before the tap — must cost zero",
            before, testScheduler.currentTime,
        )
    }

    /**
     * AND IT IS BOUNDED, because coverage can become UNREACHABLE — and the reason is the
     */
    @Test fun `a key that is never published gives the partial map back, on the budget`() = runTest {
        val published = MutableStateFlow<Map<EmailKey, Long?>>(mapOf(a to 7L))
        val before = testScheduler.currentTime

        assertEquals(
            "the wait must END and hand back what there is — a test that hangs says nothing, and " +
                "so does a gesture that hangs",
            mapOf(a to 7L),
            tickedNumberingWhenCovered(setOf(a, b), published, budgetMs = 1_000L),
        )
        assertEquals(
            "and it must end ON THE BUDGET it was given, not on some other clock",
            1_000L, testScheduler.currentTime - before,
        )
    }

    /**
     * THE REGRESSION THIS CLOSES, and the reachable path that produces it. While the wait runs,
     */
    @Test fun `a selection emptied during the wait still gets the map the gesture asked with`() = runTest {
        val published = MutableStateFlow<Map<EmailKey, Long?>>(mapOf(a to 7L))
        val before = testScheduler.currentTime

        val gesture = async { tickedNumberingWhenCovered(setOf(a, b), published, budgetMs = 1_000L) }
        runCurrent()
        // Back: the selection is cleared, the collector republishes over nothing.
        published.value = emptyMap()

        assertEquals(
            "⛔ the fallback must be the map FROZEN ON ENTRY, never published.value read again at " +
                "the timeout — that one is whatever the collector has emptied the field to since",
            mapOf(a to 7L),
            gesture.await(),
        )
        assertEquals("and it must still end on its budget", 1_000L, testScheduler.currentTime - before)
    }

    /**
     * THE SHIPPED BUDGET, EXECUTED. Without this the constant is pinned nowhere: the two tests
     */
    @Test fun `the default budget is the one shipped, and it is what gets spent`() = runTest {
        val published = MutableStateFlow<Map<EmailKey, Long?>>(mapOf(a to 7L))
        val before = testScheduler.currentTime

        assertEquals(mapOf(a to 7L), tickedNumberingWhenCovered(setOf(a, b), published))
        assertEquals(
            "the wait with no budget named must spend exactly the shipped one. A bigger number " +
                "here is a gesture that holds the screen that much longer for nothing",
            1_500L, testScheduler.currentTime - before,
        )
        assertEquals("and that is the constant the two gestures get", 1_500L, TICKED_NUMBERING_BUDGET_MS)
    }

    // ---- one wait, both gestures ---------------------------------------------------------------

    /**
     * Source lint, the last resort: `InboxViewModel` needs an `Application` and Room. What only
     * the text can say is that BOTH gestures go through the ONE wait — "a barrier posted on one
     * side leaves the other open", and the two sides destroy the same mail.
     */
    @Test fun `both selection gestures take their stamps through the one wait`() {
        val source = INBOX_VIEW_MODEL.readText()
        assertEquals(
            "deleteSelected and moveSelectedTo must BOTH take the stamps of the tick through " +
                "awaitTickedNumbering(keys), and nothing else in the file may read the field for a " +
                "gesture. Whole lines, arguments included: `awaitTickedNumbering(_selectedKeys" +
                ".value)` would await a selection captured a suspension later than the keys the " +
                "gesture actually works on.",
            listOf(
                "val ticked = awaitTickedNumbering(keys)",
                "val ticked = awaitTickedNumbering(keys)",
            ),
            codeLinesNaming(source, "val ticked ="),
        )
        assertEquals(
            "every mention of the wait in the file, whole and in order: ONE declaration and the " +
                "two gestures. A third call site is a gesture nobody decided the barrier of; a " +
                "second declaration is the drift this test exists to refuse.",
            listOf(
                "private suspend fun awaitTickedNumbering(keys: Set<EmailKey>): Map<EmailKey, Long?> =",
                "val ticked = awaitTickedNumbering(keys)",
                "val ticked = awaitTickedNumbering(keys)",
            ),
            codeLinesNaming(source, "awaitTickedNumbering"),
        )
        assertEquals(
            "⛔ and the FIELD ITSELF, whole: a flow the collector publishes to, and the property " +
                "every writer in the class still assigns to. Neither accessor is covered by the " +
                "`tickedUnder =` closed lists next door, and both mutations are silent — " +
                "`get() = emptyMap()` makes `known` always empty, so every key is RE-READ on every " +
                "emission (the one thing this whole lot forbids), and `set(value) {}` publishes " +
                "nothing ever, so every gesture spends the full budget and falls back.",
            listOf(
                "private val tickedUnderFlow = MutableStateFlow<Map<EmailKey, Long?>>(emptyMap())",
                "private var tickedUnder: Map<EmailKey, Long?>",
                "get() = tickedUnderFlow.value",
                "set(value) { tickedUnderFlow.value = value }",
            ),
            blockAt(source, "private val tickedUnderFlow = MutableStateFlow<Map<EmailKey, Long?>>(emptyMap())", 4),
        )
        assertEquals(
            "and that one declaration must DELEGATE to the shared decision, on the flow the " +
                "collector publishes to — whole line: `tickedNumberingWhenCovered(keys, " +
                "MutableStateFlow(tickedUnder))` would await a snapshot that never changes, and " +
                "spend the whole budget doing it.",
            listOf("tickedNumberingWhenCovered(keys, tickedUnderFlow)"),
            codeLinesNaming(source, "tickedNumberingWhenCovered("),
        )
    }

    private fun codeLines(source: String): List<String> =
        source.lines().map { it.replace(Regex("""\s+"""), " ").trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { it.isNotEmpty() }

    private fun codeLinesNaming(source: String, needle: String): List<String> =
        codeLines(source).filter { needle in it }

    /** [count] consecutive CODE lines from the one that reads exactly [first] — whole lines, so a
     *  mutation that lengthens any of them is seen. */
    private fun blockAt(source: String, first: String, count: Int): List<String> {
        val lines = codeLines(source)
        val at = lines.indexOf(first)
        check(at >= 0) { "InboxViewModel has no code line reading exactly <$first>" }
        return lines.subList(at, minOf(at + count, lines.size))
    }

    private companion object {
        private const val INBOX_VIEW_MODEL_PATH = "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        private val INBOX_VIEW_MODEL: File by lazy {
            val root = generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VIEW_MODEL_PATH).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
            File(root, INBOX_VIEW_MODEL_PATH)
        }
    }
}
