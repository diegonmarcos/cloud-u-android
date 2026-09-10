package app.sterna.ui.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads two source files as text, same instrument and same
 */
class QuoteOnScreenSilencesPrepareWiringTest {

    @Test fun `the ViewModel names the flag exactly three times - the parameter and both gates`() {
        // Comments are cut by `lines`, so the KDoc that documents the parameter cannot satisfy this.
        val naming = lines(COMPOSE_VIEW_MODEL).filter { "quoteAlreadyOnScreen" in it }
        assertEquals(
            "⛔ exactly THREE code lines of $COMPOSE_VIEW_MODEL_PATH may name " +
                "`quoteAlreadyOnScreen`, and each one is a lie the screen would otherwise tell " +
                "after a process death:\n" +
                "  1. the `prepare()` parameter — without it the screen cannot say the quote is " +
                "already in the body, and BOTH lies come back;\n" +
                "  2. the gate on `prefillFailed()` — without it, reopening a killed composer " +
                "offline draws \"Couldn't load the original message.\" ON TOP of the quote the " +
                "screen just restored (G6);\n" +
                "  3. the gate on the `_replyQuote` emission — without it the quote is handed to " +
                "the screen a second time, the body is no longer equal to its baseline (the user " +
                "had typed), and the B6 toast says the quote was not added while it is right " +
                "there (G5).\n" +
                "A FOURTH line is a gate this rule has never read: it may be silencing something " +
                "else entirely. Lines found:",
            listOf(
                "quoteAlreadyOnScreen: Boolean = false,",
                "if (!quoteAlreadyOnScreen) prefillFailed()",
                "} else if (!quoteAlreadyOnScreen) {",
            ).sorted(),
            naming.sorted(),
        )
    }

    @Test fun `the G6 gate is on prefillFailed alone, and the bail-out stays unconditional`() {
        assertPinnedLine(
            COMPOSE_VIEW_MODEL,
            "if (!quoteAlreadyOnScreen) prefillFailed()",
            "The G6 gate. Drop the `!` and it fires only when the quote IS on screen, which is " +
                "exactly backwards. Widen it to swallow the `return@launch` on the next line and " +
                "a genuinely failed fetch would fall through into the reply/forward code below " +
                "with `original` null.",
        )
        val all = lines(COMPOSE_VIEW_MODEL)
        val gate = all.indexOf("if (!quoteAlreadyOnScreen) prefillFailed()")
        assertEquals(
            "in $COMPOSE_VIEW_MODEL_PATH the line right after " +
                "'if (!quoteAlreadyOnScreen) prefillFailed()' must BE 'return@launch': the " +
                "silence is on the SENTENCE only. Pulled inside the gate, a restored composer " +
                "whose fetch really failed would carry on into the reply path with no original " +
                "at all.",
            "return@launch",
            all.getOrNull(gate + 1),
        )
    }

    @Test fun `the G5 gate stands between the else branch and the emission`() {
        assertPinnedLine(
            COMPOSE_VIEW_MODEL,
            "} else if (!quoteAlreadyOnScreen) {",
            "The G5 gate, written as the `else if` of the cache-first branch so the emission line " +
                "underneath stays the single line ReplyQuoteConsumedWiringTest pins. Turned back " +
                "into a plain `} else {` and the quote is posted again to a screen that already " +
                "has it: the body no longer matches its baseline, so the toast branch runs and " +
                "announces a quote-not-added over the quote.",
        )
        val all = lines(COMPOSE_VIEW_MODEL)
        val gate = all.indexOf("} else if (!quoteAlreadyOnScreen) {")
        assertEquals(
            "in $COMPOSE_VIEW_MODEL_PATH the line right after '} else if (!quoteAlreadyOnScreen) " +
                "{' must BE the emission it guards, " +
                "'_replyQuote.value = replyBody(quote(original, " +
                "resolveOutgoingDateZone(settings.quotedDatesUtc)))'. Anything else and the gate " +
                "has been moved onto some other statement while the emission runs on unguarded.",
            "_replyQuote.value = replyBody(quote(original, resolveOutgoingDateZone(settings.quotedDatesUtc)))",
            all.getOrNull(gate + 1),
        )
    }

    @Test fun `the screen feeds the gates its own saved flag, not a constant`() {
        assertPinnedLine(
            COMPOSE_SCREEN,
            "quoteAlreadyOnScreen = quoteLanded,",
            "⛔ THE line that makes both gates real. A correct decision fed a constant decides " +
                "nothing: `quoteAlreadyOnScreen = false` leaves G5 and G6 exactly as reported, " +
                "and `= true` is worse — a reply whose original genuinely failed to load would " +
                "then go out with no quote and no word said, on every path, forever. The whole " +
                "line is pinned because that is a change of two characters.",
        )
    }

    @Test fun `the two posts of the flag are boxed into the branches that put the quote there`() {
        val all = lines(COMPOSE_SCREEN)
        val posts = all.filter { "quoteLanded = true" in it }
        assertEquals(
            "\u26d4 exactly TWO lines of $COMPOSE_SCREEN_PATH may raise `quoteLanded`, one per way a " +
                "quote reaches the body on screen:\n" +
                "  1. `if (it.quoted) quoteLanded = true` \u2014 the prefill was built WITH the quote " +
                "inside its body, which is what happens when nothing was cached (a reply to a " +
                "server search hit, whose row `cachedEmail` never wrote): there is no " +
                "out-of-band hand-over on that path, so nothing else can raise the flag and the " +
                "banner comes back over the restored quote (G6);\n" +
                "  2. `quoteLanded = true` \u2014 the out-of-band drop in `LaunchedEffect(replyQuote)`, " +
                "the cache-first path.\n" +
                "Whole lines, because a condition tacked onto either one only lengthens it. One " +
                "post too many \u2014 in the toast branch above all \u2014 and the flag stops meaning \"the " +
                "body carries the quote\" and starts meaning \"a quote arrived and was REFUSED\": " +
                "after a B6 toast and a process death `prepare()` is gagged on a composer that " +
                "has NO quote, and the reply goes out without the original and without a word. " +
                "Lines found:",
            listOf(
                "if (it.quoted) quoteLanded = true",
                "quoteLanded = true",
            ).sorted(),
            posts.sorted(),
        )

        val naming = all.filter { "quoteLanded" in it }
        assertEquals(
            "\u26d4 exactly FOUR lines of $COMPOSE_SCREEN_PATH may so much as NAME `quoteLanded` \u2014 " +
                "its saved declaration, the argument handed to `prepare()`, and the two posts. A " +
                "fifth is a writer this file has never read (a reset to false, a post under some " +
                "other condition), and it decides what the composer is allowed to say about the " +
                "quote. Lines found:",
            listOf(
                "var quoteLanded by rememberSaveable { mutableStateOf(false) }",
                "quoteAlreadyOnScreen = quoteLanded,",
                "if (it.quoted) quoteLanded = true",
                "quoteLanded = true",
            ).sorted(),
            naming.sorted(),
        )
    }

    @Test fun `the out-of-band post sits between the re-baseline and the toast branch, never inside it`() {
        val all = lines(COMPOSE_SCREEN)
        val landed = all.indexOf("quoteLanded = true")
        val baseline = all.indexOf("initialBody = quote")
        // The BRANCH OPENER, not the toast call inside it. Bounding by
        // `viewModel.noticeQuoteNotAdded()` left a hole a mutation walked straight through on
        // 2026-08-25: posted on the FIRST line of the toast branch, the flag still sat above the
        // toast call, both bounds held, and the whole suite stayed green.
        val toastBranch = all.indexOf("} else if (applied) {")
        assertTrue(
            "in $COMPOSE_SCREEN_PATH 'quoteLanded = true' (line index $landed) must sit STRICTLY " +
                "between 'initialBody = quote' (index $baseline) and the OPENER of the toast " +
                "branch, '} else if (applied) {' (index $toastBranch) \u2014 i.e. inside the branch that " +
                "really drops the quote into the body, below the write and its re-baseline, and " +
                "ABOVE the toast branch. Both bounds are load-bearing:\n" +
                "  - above the write, it claims a quote is on screen before one is;\n" +
                "  - anywhere at or below that opener \u2014 the first line of the toast branch " +
                "included, which is exactly where a lower bound and a bound on the toast CALL " +
                "both still pass \u2014 it is posted on the path where the quote was REFUSED " +
                "because the user " +
                "had already typed. The next `prepare()`, after a process death, would then be " +
                "silenced on a composer carrying no quote at all: the reply is sent without the " +
                "original and without a word, which is the very thing the B6 toast exists to " +
                "prevent. Neither a lower bound alone nor an upper bound on the toast call " +
                "can see that move; only the branch opener can.",
            baseline >= 0 && toastBranch >= 0 && landed > baseline && landed < toastBranch,
        )
    }

    @Test fun `the prefill post sits inside the not-applied block, under the baseline it belongs to`() {
        val all = lines(COMPOSE_SCREEN)
        val post = all.indexOf("if (it.quoted) quoteLanded = true")
        val baseline = all.indexOf("initialBody = it.body")
        val applied = all.indexOf("applied = true")
        assertTrue(
            "in $COMPOSE_SCREEN_PATH 'if (it.quoted) quoteLanded = true' (line index $post) must " +
                "sit STRICTLY between 'initialBody = it.body' (index $baseline) and " +
                "'applied = true' (index $applied) \u2014 that is, inside the `if (!applied)` block " +
                "of the prefill effect, right under the body and baseline it describes. Above " +
                "the baseline it would announce a body not yet written; below `applied = true`, " +
                "or outside the block, it would run on a re-emitted prefill the screen " +
                "DELIBERATELY did not apply (the user is typing), and the flag would then claim " +
                "a quote the body never took \u2014 gagging the next `prepare()` and sending the reply " +
                "without the original and without a word.",
            baseline >= 0 && applied >= 0 && post > baseline && post < applied,
        )
    }

    @Test fun `only the two reply prefills declare they carry the quote, never the forward`() {
        assertPinnedLine(
            COMPOSE_VIEW_MODEL,
            "val quoted: Boolean = false,",
            "\u26d4 The field the screen reads, and its `false` DEFAULT is what leaves every other " +
                "opening alone \u2014 a reopened draft, a mailto: link, an undone send, a queued item " +
                "taken back out of the Outbox. Removed, the screen cannot tell a prefill that " +
                "carries the quote from one that does not and G6 comes back on the uncached " +
                "path. Defaulted to `true`, the flag rises on EVERY opening: `prepare()` is " +
                "gagged everywhere and a reply whose original genuinely failed to load goes out " +
                "with no quote and no word said.",
        )

        val all = lines(COMPOSE_VIEW_MODEL)
        val declarations = all.filter { "quoted = quoteBody" in it }
        assertEquals(
            "\u26d4 exactly TWO lines of $COMPOSE_VIEW_MODEL_PATH may pass `quoted = quoteBody,` \u2014 " +
                "the \"replyAll\" branch of buildPrefill and the reply `else` branch, the two " +
                "that put the quoted original inside the body they hand over. Missing, the " +
                "uncached path builds a body carrying the quote while telling the screen it does " +
                "not, and the banner is drawn over that quote after a process death. Passed as a " +
                "constant `true` instead of `quoteBody`, it lies in the one case `quoteBody` " +
                "exists for (the full body could not be loaded, so the quote was skipped): the " +
                "screen would silence the next prepare() over a reply that has no quote. Lines " +
                "found:",
            listOf("quoted = quoteBody,", "quoted = quoteBody,"),
            declarations,
        )

        // Each one is welded to the body line it describes, so neither can drift onto another
        // DraftFields call while the count above stays at two.
        val bodyLine = """body = replyBody(if (quoteBody) quote(original, zone) else ""),"""
        val bodies = all.withIndex().filter { it.value == bodyLine }.map { it.index }
        assertEquals("the two reply bodies of buildPrefill are the anchor of this rule", 2, bodies.size)
        bodies.forEach { at ->
            // Within the SAME DraftFields call and not strictly the next line: reply-all now
            // sets `showAllRecipients = true` between the two (#271). The block ends at its
            // own closing `)`, so the claim still cannot drift onto another DraftFields --
            // a reopened draft, a mailto: link -- and raise the screen's flag over a body
            // that carries no quote.
            val block = all.drop(at + 1).takeWhile { it != ")" }
            assertEquals(
                "in $COMPOSE_VIEW_MODEL_PATH the DraftFields opened by the reply body\n    $bodyLine\n" +
                    "(line index $at) must carry 'quoted = quoteBody,' before it closes: the " +
                    "claim travels with the body it is about. Lines of that call were:\n" +
                    block.joinToString("\n"),
                1,
                block.count { it == "quoted = quoteBody," },
            )
        }

        // …and the forward branch keeps the default. Its editable body is empty (just the user's
        // note and the signature); the original travels separately to send time.
        val forward = all.indexOf("ComposeOpening.FORWARD, ComposeOpening.FORWARD_ATTACHMENT -> DraftFields(")
        val replyAll = all.indexOf("ComposeOpening.REPLY_ALL -> DraftFields(")
        assertTrue(
            "buildPrefill's three branches must still be readable in order in " +
                "$COMPOSE_VIEW_MODEL_PATH: 'ComposeOpening.FORWARD, ComposeOpening.FORWARD_ATTACHMENT " +
                "-> DraftFields(' (index $forward) then 'ComposeOpening.REPLY_ALL -> DraftFields(' " +
                "(index $replyAll). Rewritten, the rule below stops looking at the forward branch " +
                "and silently guards nothing.",
            forward in 0 until replyAll,
        )
        assertEquals(
            "\u26d4 no line of the \"forward\" branch of buildPrefill may name `quoted`. A forward's " +
                "editable body carries NO quote \u2014 the original is carried separately to send " +
                "time \u2014 so claiming otherwise raises the screen's flag on a composer that shows " +
                "nothing of the original, and the next prepare() after a process death drops the " +
                "\"Couldn't load the original message.\" banner that is the only sign the forward " +
                "lost its content. Offending lines:",
            emptyList<String>(),
            all.subList(forward, replyAll).filter { "quoted" in it },
        )
    }

    @Test fun `the flag is saved, because a process death is the case it closes`() {
        assertPinnedLine(
            COMPOSE_SCREEN,
            "var quoteLanded by rememberSaveable { mutableStateOf(false) }",
            "⛔ `rememberSaveable`, not `remember`, and not a ViewModel field — same reason as " +
                "`requestReceipt` and `applied` beside it. The ONLY case this flag exists for is " +
                "the app being killed with the composer open; a plain `remember` and a ViewModel " +
                "field both come back false in exactly that case, so the gates would be handed " +
                "false and close nothing at all, while every rule above stayed green.",
        )
        val all = lines(COMPOSE_SCREEN)
        assertTrue(
            "in $COMPOSE_SCREEN_PATH the declaration of `quoteLanded` must come BEFORE the " +
                "`viewModel.prepare(` call that reads it — Kotlin does not read ahead, so this " +
                "is also what makes the file compile. If they ever swap, the argument above is " +
                "being fed something else.",
            all.indexOf("var quoteLanded by rememberSaveable { mutableStateOf(false) }") in
                0 until all.indexOf("viewModel.prepare("),
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
     *  contains: a mutation that lengthens a line must change the line. Comments MUST be cut: the
     *  KDoc of `prepare` documents `quoteAlreadyOnScreen` in prose, and prose must never be able to
     *  satisfy a rule. */
    private fun lines(file: File): List<String> = code(file).lines().map { it.trim() }

    /** [file]'s code as one string, comments cut — same reader as [ReplyQuoteConsumedWiringTest]. */
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
