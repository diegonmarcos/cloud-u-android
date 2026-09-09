package app.sterna.ui.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads source files as text, same instrument and
 */
class ReplyQuoteConsumedWiringTest {

    @Test fun `the ViewModel retires the quote, in exactly one place`() {
        assertPinnedLine(
            COMPOSE_VIEW_MODEL,
            "_replyQuote.value = null",
            "This line IS consumeReplyQuote's body. Gone, the quote stays in the StateFlow for " +
                "the life of the ViewModel and every activity recreation replays the effect on " +
                "it. Twice, and something else is nulling the quote behind the screen's back — " +
                "a reply could then open with no quote and no word said.",
        )
        assertPinnedLine(
            COMPOSE_VIEW_MODEL,
            "fun consumeReplyQuote() {",
            "The screen has to have something to call. Renamed or made private, the composer " +
                "cannot retire the quote at all.",
        )
    }

    @Test fun `nothing else in the ViewModel may so much as NAME the quote flow`() {
        // Every line NAMING the backing flow, not only `.value =`: an `update { }`, a `tryEmit()`
        // or an alias would be writes this rule must see too. Same instrument as
        // DialogFlagsSurviveRotationTest, which caught a reset hidden in prepare().
        val writers = lines(COMPOSE_VIEW_MODEL).filter { "_replyQuote" in it }
        assertEquals(
            "⛔ exactly FOUR lines of $COMPOSE_VIEW_MODEL_PATH may so much as NAME `_replyQuote` " +
                "— its declaration, its public flow, the one emission that posts the quote, and " +
                "the one line that retires it. A fifth is a second writer the screen never sees: " +
                "posted again behind its back, the caret jumps to the top of a reply being " +
                "written; retired again, the quote never lands and the B6 toast never says so. " +
                "Lines found:",
            listOf(
                "private val _replyQuote = MutableStateFlow<String?>(null)",
                "val replyQuote: StateFlow<String?> = _replyQuote.asStateFlow()",
                "_replyQuote.value = null",
                "_replyQuote.value = replyBody(quote(original, resolveOutgoingDateZone(settings.quotedDatesUtc)))",
            ).sorted(),
            writers.sorted(),
        )
    }

    @Test fun `the effect is keyed on the quote, and turns back on the null the consume writes`() {
        assertPinnedLine(
            COMPOSE_SCREEN,
            "LaunchedEffect(replyQuote) {",
            "The KEY. Keyed on anything else — `Unit` above all — the effect runs once at the " +
                "first composition, when the quote is still null, and the quote NEVER lands on " +
                "the cache-first path: the reply goes out with no quote, and the B6 toast never " +
                "says so either. Every count and every ordering rule in this file stays green " +
                "with that one word changed, which is why the key is pinned on its own.",
        )
        assertPinnedLine(
            COMPOSE_SCREEN,
            "val quote = replyQuote ?: return@LaunchedEffect",
            "⛔ The re-entry guard, load-bearing since the consume exists: retiring the quote " +
                "writes `null`, a NEW value, so this effect runs a second time with a null " +
                "quote. Widen this line to a default (`replyQuote.orEmpty()`) and that second " +
                "pass reaches the branch below with `applied` true and the body still equal to " +
                "the baseline just re-seated — body AND baseline are emptied together, so the " +
                "reply loses its quote and its signature, and nothing is left dirty to warn " +
                "about it on the way out.",
        )
    }

    @Test fun `the screen consumes the quote once per branch that handled it`() {
        val calls = lines(COMPOSE_SCREEN).count { it == "viewModel.consumeReplyQuote()" }
        assertEquals(
            "expected exactly TWO lines of $COMPOSE_SCREEN_PATH whose trimmed text is " +
                "'viewModel.consumeReplyQuote()' — one in the branch that drops the quote into " +
                "the body, one in the branch that says it could not — but found $calls. With " +
                "one missing, that branch replays on every rotation: the body branch throws the " +
                "caret back to the top of the quote over the user's restored selection, the " +
                "toast branch re-posts 'Message cité non ajouté' once per rotation. With three, " +
                "one of them sits on the path where the quote arrived BEFORE the prefill landed " +
                "— consuming there throws the quote away with nothing having posted it and " +
                "nothing having announced it.",
            2, calls,
        )
    }

    @Test fun `the two lines the consumes retire are each unique`() {
        assertPinnedLine(
            COMPOSE_SCREEN,
            "body = TextFieldValue(quote, TextRange(0))",
            "This is the write the first consume retires, and the reason the replay is visible: " +
                "it re-seats the caret at offset 0. Split or rewritten, the ordering rule below " +
                "can no longer find it and stops guarding anything.",
        )
        assertPinnedLine(
            COMPOSE_SCREEN,
            "initialBody = quote",
            "The re-baseline that goes with the write, so the dropped quote is not counted as an " +
                "unsaved edit. Pinned because the consume must sit immediately after it.",
        )
        assertPinnedLine(
            COMPOSE_SCREEN,
            "viewModel.noticeQuoteNotAdded()",
            "The B6 toast. Its existence is not the defect — its REPETITION is; it is what the " +
                "second consume retires.",
        )
    }

    @Test fun `each consume comes after the work it retires, never before`() {
        val all = lines(COMPOSE_SCREEN)
        val consumes = all.withIndex().filter { it.value == "viewModel.consumeReplyQuote()" }.map { it.index }
        assertEquals("two consume calls are needed before their order can be judged", 2, consumes.size)

        val write = all.indexOf("body = TextFieldValue(quote, TextRange(0))")
        assertTrue(
            "in $COMPOSE_SCREEN_PATH the write 'body = TextFieldValue(quote, TextRange(0))' " +
                "(line index $write) must come BEFORE the first 'viewModel.consumeReplyQuote()' " +
                "(line index ${consumes[0]}). ⚠ Today this order guards nothing on its own: no " +
                "line between the two suspends, so cancellation cannot land there — do not cite " +
                "it as a safety argument. It is pinned because it is the repo's order " +
                "(ConnectScreen.kt:318-325) and because ONE suspending call added between them " +
                "later would empty the flow before the quote had landed, sending the reply with " +
                "no quote and no word said.",
            write in 0 until consumes[0],
        )

        val notice = all.indexOf("viewModel.noticeQuoteNotAdded()")
        assertTrue(
            "in $COMPOSE_SCREEN_PATH 'viewModel.noticeQuoteNotAdded()' (line index $notice) must " +
                "come BEFORE the second 'viewModel.consumeReplyQuote()' (line index " +
                "${consumes[1]}). Same order, same reason as above: nothing suspends between the " +
                "two lines today, and the pin is what keeps it that way if one ever does.",
            notice in 0 until consumes[1],
        )

        // Adjacency, so a single consume parked at the end of the effect cannot satisfy both
        // "after" rules at once while one branch is left replaying.
        assertEquals(
            "in $COMPOSE_SCREEN_PATH the line right after 'initialBody = quote' must BE " +
                "'viewModel.consumeReplyQuote()'. Anything wedged between them leaves the applying " +
                "branch able to run without retiring the quote — the caret jumps back to the top " +
                "on the next rotation.",
            "viewModel.consumeReplyQuote()",
            all.getOrNull(all.indexOf("initialBody = quote") + 1),
        )
        assertEquals(
            "in $COMPOSE_SCREEN_PATH the line right after 'viewModel.noticeQuoteNotAdded()' must " +
                "BE 'viewModel.consumeReplyQuote()'. Otherwise the toast branch is the one left " +
                "replaying, and the toast stacks up once per rotation.",
            "viewModel.consumeReplyQuote()",
            all.getOrNull(notice + 1),
        )
    }

    // -- reading the sources --------------------------------------------------------------------

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

    /** [file]'s code as trimmed WHOLE lines, comments cut. Rules compare with equality, never
     *  contains: a mutation that lengthens a line must change the line. Comments MUST be cut here:
     *  the KDoc this fix adds names `consumeReplyQuote` and `_replyQuote.value = null` in prose,
     *  and prose must never be able to satisfy a rule. */
    private fun lines(file: File): List<String> = code(file).lines().map { it.trim() }

    /** [file]'s code as one string, comments cut — same reader as [OutgoingDateWiringTest]. */
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
        private const val COMPOSE_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"

        /** Repo root, walked up from the module's working directory — as the other source lints do. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, COMPOSE_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val COMPOSE_VIEW_MODEL: File by lazy { File(root, COMPOSE_VIEW_MODEL_PATH) }
        private val COMPOSE_SCREEN: File by lazy { File(root, COMPOSE_SCREEN_PATH) }
    }
}
