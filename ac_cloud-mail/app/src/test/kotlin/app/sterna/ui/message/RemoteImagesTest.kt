package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The two questions the "images are blocked" strip turns on, RUN rather than re-stated, plus the
 */
class RemoteImagesTest {

    // ── hasRemoteRefs, executed ────────────────────────────────────────────────────────────────

    @Test fun `an http image is remote`() {
        assertTrue(hasRemoteRefs("""<p>hello</p><img src="http://t.example.com/px.gif">"""))
    }

    @Test fun `an https image is remote`() {
        assertTrue(hasRemoteRefs("""<img src="https://t.example.com/px.gif" width="1">"""))
    }

    @Test fun `a protocol-relative image is remote`() {
        assertTrue(
            "'//h/x.gif' carries no scheme at all and is fetched over the network just the same. " +
                "Keying this on http/https is the exact defect shouldInterceptRequest was " +
                "corrected for; here it would leave the message with no strip at all, so the " +
                "reader is never told the pictures were held back",
            hasRemoteRefs("""<img src="//h/x.gif">"""),
        )
    }

    @Test fun `an exotic scheme is remote too`() {
        assertTrue(
            "the client blocks everything that is not data:, cid: or about: — ftp: included — so " +
                "the strip must appear for it too, or the reader sees a hole and no explanation",
            hasRemoteRefs("""<img src="ftp://files.example.com/x.gif">"""),
        )
    }

    @Test fun `an inline cid image is not remote`() {
        assertFalse(
            "a cid: part travels inside the message and is never fetched: announcing it would " +
                "offer a button that has nothing to unblock",
            hasRemoteRefs("""<img src="cid:logo@example.com">"""),
        )
    }

    @Test fun `a data image is not remote`() {
        assertFalse(hasRemoteRefs("""<img src="data:image/png;base64,iVBORw0KGgo=">"""))
    }

    @Test fun `a body that is not there announces nothing`() {
        assertFalse(hasRemoteRefs(null))
    }

    @Test fun `a body with no markup at all announces nothing`() {
        assertFalse(hasRemoteRefs("Hello, see you Tuesday."))
    }

    @Test fun `a link is not a resource`() {
        assertFalse(
            "an <a href> is not loaded by the page — nothing about it is blocked, and a strip " +
                "over a message whose only remote thing is a link is a strip about nothing",
            hasRemoteRefs("""<p><a href="http://example.com/offer">click</a></p>"""),
        )
    }

    @Test fun `src is read on any element, not only img`() {
        assertTrue(hasRemoteRefs("""<video src="https://v.example.com/a.mp4"></video>"""))
    }

    @Test fun `the attribute is matched whatever its case and spacing`() {
        assertTrue(hasRemoteRefs("""<IMG SRC = 'https://t.example.com/px.gif'>"""))
    }

    @Test fun `an unquoted source still counts`() {
        assertTrue(hasRemoteRefs("<img src=https://t.example.com/px.gif>"))
    }

    @Test fun `a lazy-loading placeholder is not a load`() {
        // Written after watching a mutation survive: dropping the whitespace the attribute regex
        // requires makes `data-src` match, and nothing else in this class could tell. The WebView
        // runs no script, so a data-src is never fetched — announcing it is announcing a block
        // that did not happen, and the button then unblocks nothing.
        assertFalse(hasRemoteRefs("""<img data-src="https://t.example.com/px.gif" src="cid:a@b">"""))
    }

    // ── imagesStripBody, executed ──────────────────────────────────────────────────────────────

    @Test fun `while the pictures are held back the strip is a button`() {
        val body = imagesStripBody(blocked = true)
        assertEquals(ImagesStripBody.ACTION, body)
        assertTrue("blocked is the one state with something left to do", body.acts)
    }

    @Test fun `once they are shown the strip has nothing left to press`() {
        val body = imagesStripBody(blocked = false)
        assertEquals(ImagesStripBody.SHOWN, body)
        assertFalse(
            "there is nothing left to unblock; a live button here would show images twice",
            body.acts,
        )
    }

    /**
     * The state → label correspondence, RUN. The strip now draws one single shape whose only
     */
    @Test fun `the held-back state wears the show-images label`() {
        assertEquals(
            "blocked pictures must offer R.string.message_show_images — the label the overflow " +
                "entry already uses, translated in all nine languages. Any other id here and the " +
                "one thing the strip exists to say is wrong",
            app.sterna.R.string.message_show_images,
            imagesStripBody(blocked = true).label,
        )
    }

    @Test fun `the shown state wears the images-shown label`() {
        assertEquals(
            "once the pictures are there the same button must read " +
                "R.string.message_images_shown. Swapped with the other id, the strip says " +
                "\"Images shown\" over held-back pictures and offers \"Show images\" once they " +
                "are already on screen — and the two states are otherwise identical, so nothing " +
                "else would notice",
            app.sterna.R.string.message_images_shown,
            imagesStripBody(blocked = false).label,
        )
    }

    // ── imagesStripPresent, executed ───────────────────────────────────────────────────────────
    //
    // The whole truth table, inputs and answers written out as literals. Nothing here recomputes
    // `!plainText && !senderAllowed && …`: with the rule copied, the shipped condition could be
    // inverted and this section would follow it down.

    @Test fun `in text mode a blocked newsletter gets no strip`() {
        assertFalse(
            "reading mode is TEXT, so the document holds no image to hold back and " +
                "showRemoteImages() stays false for as long as that mode holds. A strip drawn " +
                "here says \"Show images\" with a LIVE button that changes nothing when tapped, " +
                "again and again, for good — the reported defect",
            imagesStripPresent(
                plainText = true,
                senderAllowed = false,
                html = """<img src="https://t.example.com/px.gif">""",
            ),
        )
    }

    @Test fun `in text mode an allowed sender gets no strip either`() {
        assertFalse(
            "nothing is held back and nothing is rendered: two reasons for no strip",
            imagesStripPresent(
                plainText = true,
                senderAllowed = true,
                html = """<img src="https://t.example.com/px.gif">""",
            ),
        )
    }

    @Test fun `in text mode a body with nothing remote gets no strip`() {
        assertFalse(
            imagesStripPresent(plainText = true, senderAllowed = false, html = "<p>hello</p>"),
        )
    }

    @Test fun `in text mode a body that is not there gets no strip`() {
        assertFalse(imagesStripPresent(plainText = true, senderAllowed = false, html = null))
    }

    @Test fun `in HTML mode a blocked newsletter gets its strip`() {
        assertTrue(
            "this is the nominal path of #153: HTML mode, sender not on the allowlist, a remote " +
                "image in the document. No strip here and the reader sees a hole in her " +
                "newsletter and is told nothing about it",
            imagesStripPresent(
                plainText = false,
                senderAllowed = false,
                html = """<img src="https://t.example.com/px.gif">""",
            ),
        )
    }

    @Test fun `in HTML mode an allowed sender gets no strip`() {
        assertFalse(
            "this sender's pictures are fetched without asking, so a strip would offer to " +
                "unblock something that was never blocked",
            imagesStripPresent(
                plainText = false,
                senderAllowed = true,
                html = """<img src="https://t.example.com/px.gif">""",
            ),
        )
    }

    @Test fun `in HTML mode a body with nothing remote gets no strip`() {
        assertFalse(
            "nothing in this message is refused at load time, so the strip would be a row of " +
                "header about nothing — and the header's height keys the body's HTML document",
            imagesStripPresent(plainText = false, senderAllowed = false, html = "<p>hello</p>"),
        )
    }

    @Test fun `in HTML mode a body that is not there gets no strip`() {
        assertFalse(imagesStripPresent(plainText = false, senderAllowed = false, html = null))
    }

    /**
     * SOURCE LINT, NOT A MEASUREMENT — Compose is not run here, so these rules would still pass on
     */
    @Test fun `the strip is one shape, wearing one label or the other`() {
        val strip = stripLines()
        assertTrue(
            "the row's height contract is the line '.heightIn(min = ButtonDefaults.MinHeight)'. " +
                "HONEST ABOUT WHAT IT DOES: with a single child that is itself a TextButton, this " +
                "floor never bites — the button already stands above 40 dp, more so as the font " +
                "grows. It is kept as the contract for the day something shorter is put in this " +
                "row, and because removing it is a silent change of the strip's height on a " +
                "header whose measured height keys the remember() that builds the body's HTML. " +
                "What holds the two STATES level is the single shape, not this line. Strip " +
                "was:\n${strip.joinToString("\n")}",
            hasLine(strip, ".heightIn(min = ButtonDefaults.MinHeight)"),
        )
        assertTrue(
            "…and its horizontal inset must be the line '.padding(horizontal = 16.dp)'",
            hasLine(strip, ".padding(horizontal = 16.dp)"),
        )
        assertEquals(
            "no vertical padding of its own, anywhere in the strip: it is added to the height " +
                "floor rather than absorbed by it, so the strip becomes taller than the button " +
                "and the body under it is reloaded",
            emptyList<String>(),
            strip.filter { "vertical =" in it },
        )
        assertEquals(
            "BOTH states are ONE TextButton, and these are its lines, verbatim (one whole line " +
                "each): ${BUTTON_LINES.joinToString(" · ")}. `enabled = body.acts` is the whole " +
                "difference in behaviour; a second composable for the 'shown' state is what made " +
                "the header change height at the tap, because heightIn() is a FLOOR the button " +
                "overshoots and a bare Text does not. NOTE this goes further than the house " +
                "pattern: [UnsubscribeStrip] keeps its button only while an unsubscribe is IN " +
                "FLIGHT (SENDING) and drops to a bare Text once DONE — which is exactly the " +
                "divergence measured here, and is not to be copied back. Strip " +
                "was:\n${strip.joinToString("\n")}",
            emptyList<String>(),
            BUTTON_LINES.filterNot { hasLine(strip, it) },
        )
        assertEquals(
            "…and the label is the enum's own, on one line whatever the language, verbatim: " +
                "${LABEL_LINES.joinToString(" · ")}. `stringResource(body.label)` keeps the " +
                "state → string correspondence in ImagesStripBody where a JVM test can RUN it. " +
                "`maxLines = 1` + the ellipsis are the height rule, not typography: heightIn is a " +
                "FLOOR, so a label that WRAPS makes the strip a whole line taller — and the two " +
                "strings differ in length in every language (de 15 → 23 chars, nl 18 → 24, fr 19 " +
                "→ 16), so at font_scale 2.0 on a 360 dp screen one state wraps and the other " +
                "does not. That is the same defect this lot closes, an order of magnitude bigger: " +
                "the header changes by a LINE at the tap, the body's HTML is re-keyed, the load " +
                "in flight is cancelled. Truncating a German label is the cost that was chosen " +
                "over that. Strip was:\n${strip.joinToString("\n")}",
            emptyList<String>(),
            LABEL_LINES.filterNot { hasLine(strip, it) },
        )
        assertTrue(
            "the disabled state must name its own colour, verbatim '$DISABLED_COLOUR_LINE' " +
                "inside a ButtonDefaults.textButtonColors(. Without it a disabled TextButton " +
                "falls back to Material3's onSurface at 38 % alpha, and the ONE confirmation the " +
                "app gives that the pictures were fetched becomes the palest thing in the header " +
                "— fainter than the muted line it replaced (onSurfaceVariant). Strip " +
                "was:\n${strip.joinToString("\n")}",
            hasLine(strip, DISABLED_COLOUR_LINE) &&
                hasLine(strip, "colors = ButtonDefaults.textButtonColors("),
        )
        assertTrue(
            "the strip must ASK imagesStripBody(blocked) rather than re-decide from the boolean: " +
                "a strip that re-decides is a second copy of the rule, and the tests above then " +
                "measure nothing that ships",
            hasLine(strip, "val body = imagesStripBody(blocked)"),
        )
        assertEquals(
            "…and must not branch on `blocked` itself anywhere",
            emptyList<String>(),
            strip.filter { "if (blocked" in it || "blocked ->" in it || "!blocked" in it },
        )
    }

    /**
     * The rule that keeps the two states ONE shape — the defect that was measured, stated the only
     */
    @Test fun `the strip draws one thing and nothing beside it`() {
        val strip = stripLines()
        val drawn = strip
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { DRAW_CALL.containsMatchIn(it) }
        assertEquals(
            "the strip must open exactly these three composables, in this order, and nothing " +
                "else: ${ALLOWED_DRAWS.joinToString(" / ")} — their arguments are pinned as whole " +
                "lines by the rule above. Anything else here is a second shape, and " +
                "two shapes cannot be held to one height — heightIn() is a FLOOR, the TextButton " +
                "overshoots it (minimumInteractiveComponentSize, plus a vertical inset that grows " +
                "with the font scale) and anything smaller sits back down on it. The header then " +
                "changes height at the tap, which re-keys the body's HTML document: the reader " +
                "either waits behind the spinner for the failsafe (before the first reveal) or " +
                "watches the message reload and lose her scroll position (after it). Strip " +
                "was:\n${strip.joinToString("\n")}",
            ALLOWED_DRAWS,
            drawn.map { it.removeSuffix(",") },
        )
    }

    /**
     * The rules above protect the lines they ENUMERATE. They cannot protect the file from a line
     */
    @Test fun `no geometry is added to the strip, ever`() {
        val strip = stripLines()
        val offenders = strip
            // Prose carries no layout: the strip's own comments talk about padding and height.
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            // The two known lines, matched WHOLE (the height rule above pins their exact text).
            .filterNot { it.removeSuffix(",") in ALLOWED_GEOMETRY }
            .filter { line -> GEOMETRY_WORDS.any { it in line.lowercase() } }
        assertEquals(
            "the strip may carry NO geometry beyond ${ALLOWED_GEOMETRY.joinToString(" and ")}. " +
                "Each line below adds size to the row or to what it holds, so the strip stops " +
                "being the height the header was measured at — and a line added on ONE state's " +
                "side (were the two states ever split again) makes the header change size at the " +
                "moment of the tap, which re-keys the " +
                "body's HTML document — the reader either waits behind the spinner for the " +
                "failsafe (before the first reveal) or watches the message reload and lose her " +
                "scroll position (after it). That is the whole risk of this feature. Strip " +
                "was:\n${strip.joinToString("\n")}",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * The call site, which no other rule in this class reads: [stripLines] is the body of
     */
    @Test fun `the header is handed the live blocking state, not its opposite`() {
        val call = headerCallLines()
        assertTrue(
            "ConversationBody no longer calls MessageHeader( on a line of its own — was the call " +
                "reformatted or moved? This rule reads that call and nothing else.",
            call.isNotEmpty(),
        )
        assertEquals(
            "ConversationBody must hand MessageHeader these arguments VERBATIM, one whole line " +
                "each: `imagesBlocked` is the LIVE state and is passed straight through, never " +
                "negated and never swapped with the frozen `imagesStrip`. Negated, the strip " +
                "announces \"Images shown\" while the pictures are held back and then offers a " +
                "button that does nothing once they are shown — and every other rule in this " +
                "class stays green. Call site was:\n${call.joinToString("\n")}",
            emptyList<String>(),
            HEADER_WIRING.filterNot { hasLine(call, it) },
        )
        assertTrue(
            "…and MessageHeader must hand the strip its LIVE argument, verbatim " +
                "'ImagesStrip(imagesBlocked, onShowImages)'. Handed `imagesStrip` instead — the " +
                "frozen presence flag, which is true for every message that draws a strip — the " +
                "row would keep offering \"Show images\" after the pictures are on screen, and " +
                "the button would do nothing. MessageHeader's ImagesStrip lines were:\n" +
                headerSource().lines().map { it.trim() }
                    .filter { "ImagesStrip(" in it }.joinToString("\n"),
            hasLine(headerSource().lines().map { it.trim() }, "ImagesStrip(imagesBlocked, onShowImages)"),
        )
    }

    /**
     * The hop ABOVE the one pinned just here, which nothing else in this class or in
     */
    @Test fun `the body is handed the reading-mode answer and the blocking state, not their twins`() {
        val call = bodyCallLines()
        assertTrue(
            "MessageContent no longer opens the call on the line " +
                "'$BODY_CALL_OPENING' — was it reformatted or moved? This rule reads that call " +
                "and nothing else, so without this check it would pass on an empty list.",
            call.isNotEmpty(),
        )
        assertEquals(
            "MessageContent must hand ConversationBody these arguments VERBATIM, one whole line " +
                "each: ${BODY_WIRING.joinToString(" · ")}.\n" +
                "`blockRemote = !showRemote` is the app's privacy posture: NEGATED, the images of " +
                "a sender the reader never allowed are fetched — telling him she opened the " +
                "message and handing him her IP — while an allowed sender's are blocked.\n" +
                "`plainText = plainText` is the READING-mode answer; `imageMode` is the " +
                "image-side twin sitting on the line above it, and the two differ until DataStore " +
                "replies — handed that one, every message she opens draws a frame of plain text " +
                "before swapping to HTML, and the strip appears and disappears with it.\n" +
                "Call site was:\n${call.joinToString("\n")}",
            emptyList<String>(),
            BODY_WIRING.filterNot { hasLine(call, it) },
        )
    }

    /**
     * The THIRD door the same blocking answer has to go through, and the one nothing here was
     */
    @Test fun `the printer is handed the same blocking state as the screen`() {
        val lines = block("private fun MessageContent(").lines().map { it.trim() }
        assertEquals(
            "MessageContent must build the printed document with the blocking state the SCREEN " +
                "used, VERBATIM: `$PRINT_CALL_LINE`. Handed `blockRemote = false` (or no " +
                "argument at all), every print and every save-as-PDF goes to the network for " +
                "the sender's remote pictures — telling him she opened the message and handing " +
                "him her IP — for a message whose pictures she never allowed on screen. Handed " +
                "`showRemote`, the refused sender's pictures are fetched and the allowed one's " +
                "are held back. ⛔ EVERY line naming the call is compared, not the ones that " +
                "OPEN with it: a second print tucked after an `if` on the same line would send " +
                "its own document to the network while a `startsWith` rule stayed green. No " +
                "other rule in this class reads this line — bodyCallLines() starts at " +
                "'$BODY_CALL_OPENING', which is below it in the same composable. What was found:",
            listOf(PRINT_CALL_LINE),
            lines.filter { "printDocument(" in it },
        )
        assertEquals(
            "…and `printDocument(` may be named exactly TWICE in the whole file: this one call " +
                "and its own declaration. The rule above reads MessageContent only, so a second " +
                "print path opened anywhere else in this 4 900-line file — a \"print the whole " +
                "conversation\" action, say — could be handed `blockRemote = false` and fetch " +
                "the sender's pictures for a message whose pictures were held back, with that " +
                "rule still green.",
            2,
            SOURCE.readText().lines().count { "printDocument(" in it },
        )
    }

    /**
     * WHETHER the row is drawn at all — the condition no other rule in this class reads, and the
     */
    @Test fun `the row is drawn on presence alone, never on the live blocking state`() {
        val header = headerSource().lines().map { it.trim() }
        assertTrue(
            "MessageHeader must draw the images strip under the whole line 'if (imagesStrip) {' " +
                "— `imagesStrip` is the frozen presence flag, decided once per page in " +
                "ConversationBody. Anded with anything live (`&& imagesBlocked`, `&& blocked`, …) " +
                "the row and its HorizontalDivider() VANISH at the tap: the header loses about " +
                "49 dp at once, which re-keys the remember() that builds the body's HTML — the " +
                "reader waits behind the spinner for the failsafe (before the first reveal) or " +
                "watches the message reload and lose her scroll position (after it). That is the " +
                "same damage as a resize, at its maximum. MessageHeader's `imagesStrip` lines " +
                "were:\n" + header.filter { "imagesStrip" in it }.joinToString("\n"),
            hasLine(header, "if (imagesStrip) {"),
        )
    }

    /**
     * The presence lock, which is the half of the height rule that is easy to lose.
     */
    @Test fun `the strip's presence is frozen for the life of the page`() {
        assertTrue(
            "ConversationBody must decide the strip on `(full, plainText)` and on nothing else. " +
                "Every other key costs body reloads for no gesture: this strip's own height is an " +
                "input to the document's key (header height -> topSpacerCssPx), so anything that " +
                "flips the presence reloads the body a SECOND time, after the mode's own reload. " +
                "That is paid only for the reading-mode toggle, which replaces the document and " +
                "re-tops the message anyway. NOT `imageMode`, which flips with no gesture when " +
                "DataStore answers; NOT `senderAllowed`, which promises to move nothing. " +
                "Expected this line, verbatim:\n  $PRESENCE_LINE\nMessageScreen.kt has no line " +
                "equal to it.",
            hasLine(screenLines(), PRESENCE_LINE),
        )
    }

    /**
     * The lot's scope, held by a rule rather than by good intentions.
     */
    @Test fun `nothing else in the reader was moved`() {
        assertTrue(
            "the overflow entry must STAY: it is the only way to show the images when the " +
                "detection returns a false negative (CSS url(), srcset, background=, …), and a " +
                "false negative is the case where the reader has no strip to tap",
            hasLine(screenLines(), "text = { Text(stringResource(R.string.message_show_images)) },"),
        )
        val header = headerSource()
        val calls = STRIP_CALL.findAll(header).map { it.value }.toList()
        assertTrue(
            "MessageHeader draws no ImagesStrip at all — strips found: $calls",
            calls.any { it.startsWith("ImagesStrip(") },
        )
        assertEquals(
            "the unsubscribe strip must stay the LAST strip in the header — that placement is " +
                "deliberate and tested (it must never push a crypto verdict or a meeting " +
                "invitation below the fold), so the images strip goes BEFORE it",
            "UnsubscribeStrip(options, unsubscribeState, onUnsubscribe)",
            calls.last(),
        )
        assertTrue(
            "the images strip must come before the unsubscribe one — order was: $calls",
            calls.indexOfFirst { it.startsWith("ImagesStrip(") } < calls.lastIndex,
        )
    }

    // ── plumbing ───────────────────────────────────────────────────────────────────────────────

    /**
     * Whole-line match. The only slack is the trailing comma Kotlin puts on the last element of an
     */
    private fun hasLine(lines: List<String>, exact: String) =
        lines.any { it == exact || it == "$exact," }

    private fun screenLines(): List<String> = SOURCE.readText().lines().map { it.trim() }

    private fun stripLines(): List<String> = block("private fun ImagesStrip(").lines().map { it.trim() }

    private fun headerSource(): String = block("private fun MessageHeader(")

    /**
     * The `MessageHeader(...)` call inside `ConversationBody` — the arguments only, not the whole
     */
    private fun headerCallLines(): List<String> =
        block("private fun ConversationBody(").lines().map { it.trim() }
            .dropWhile { it != "MessageHeader(" }
            .takeWhile { it != ")" }

    /**
     * The `ConversationBody(...)` call inside `MessageContent` — the arguments only, on the same
     */
    private fun bodyCallLines(): List<String> =
        block("private fun MessageContent(").lines().map { it.trim() }
            .dropWhile { it != BODY_CALL_OPENING }
            .takeWhile { it != ")" }

    private fun block(signature: String): String {
        val text = SOURCE.readText()
        val at = text.indexOf(signature)
        check(at >= 0) { "MessageScreen.kt no longer declares `$signature` — was it renamed or moved out?" }
        val end = text.indexOf("\n/**", at)
        return text.substring(at, if (end < 0) text.length else end)
    }

    private companion object {
        /** The only two lines of the strip allowed to carry any geometry at all. */
        val ALLOWED_GEOMETRY = setOf(
            ".heightIn(min = ButtonDefaults.MinHeight)",
            ".padding(horizontal = 16.dp)",
        )

        /** Anything that can make a row taller (or add a second thing to it), matched lowercased. */
        val GEOMETRY_WORDS = listOf(
            "padding", "spacer", "height", "size", "offset", ".dp", "weight", "aspectratio",
        )

        /** The one button, drawn in both states; only `enabled` and the label change. */
        val BUTTON_LINES = listOf(
            "onClick = onShowImages",
            "enabled = body.acts",
        )

        /** Its label: the enum's string, and the guarantee that it stays on ONE line. */
        val LABEL_LINES = listOf(
            "stringResource(body.label)",
            "maxLines = 1",
            "overflow = TextOverflow.Ellipsis",
        )

        /** What a disabled TextButton must be told, or it goes to onSurface at 38 %. */
        const val DISABLED_COLOUR_LINE =
            "disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant"

        /**
         * Every composable the strip is allowed to draw, by its OPENING line, in order. A fourth
         */
        val ALLOWED_DRAWS = listOf("Row(", "TextButton(", "Text(")

        /** A composable call opening a line: `Foo(`. Modifier chains start with `.`, arguments
         *  with a name. */
        val DRAW_CALL = Regex("""^[A-Z]\w*\(""")

        /** The three arguments the images strip needs, as they must appear at the call site. */
        val HEADER_WIRING = listOf(
            "imagesStrip = imagesStrip",
            "imagesBlocked = blockRemote",
            "onShowImages = onShowImages",
        )

        /** Where `MessageContent` hands the page down; the arguments below are read from it. */
        const val BODY_CALL_OPENING = "is MessageState.Loaded -> ConversationBody("

        /**
         * The two answers handed down from `MessageContent`, as they must appear at that call
         * site. Each has a twin one token away (`showRemote`, `imageMode`) that compiles.
         */
        val BODY_WIRING = listOf(
            "blockRemote = !showRemote",
            "plainText = plainText",
        )

        /**
         * The print call, whole. `blockRemote` is the third and last place the reader's answer
         * about remote pictures has to be honoured, after the strip and the rendered page.
         */
        const val PRINT_CALL_LINE =
            "printDocument(activity, doc, printJobName(header.subject, appName), " +
                "blockRemote = !showRemote)"

        const val PRESENCE_LINE =
            "val imagesStrip = remember(full, plainText) { " +
                "imagesStripPresent(plainText, senderAllowed, full?.htmlContent()) }"

        val STRIP_CALL = Regex("""\b[A-Z]\w*Strip\([^)\n]*\)""")

        val SOURCE: File by lazy {
            val root = generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt").isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
            File(root, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt")
        }
    }
}
