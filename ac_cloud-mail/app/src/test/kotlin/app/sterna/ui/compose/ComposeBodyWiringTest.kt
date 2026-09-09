package app.sterna.ui.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and disclaimer as
 */
class ComposeBodyWiringTest {

    /** (a) The remap, its LIVE source and its last argument: `pending`, the armed style. */
    @Test fun `every keystroke remaps the styling from the live body, with the armed style`() {
        assertPinned(
            REMAP,
            "the body field's `onValueChange` must remap the styling with `remapAfterEdit`, from " +
                "`current` (built inside the callback — `rich` is captured at composition, and two " +
                "callbacks before a recomposition would remap from a stale body) and with `pending` " +
                "as its last argument: `null` there and a style armed at the caret never reaches the " +
                "text, the bar lights up and nothing typed is bold.",
        )
        assertPinned(
            "val current = RichBody(body.text, ranges, blocks, links)",
            "`onValueChange`, `onToggle`, `onToggleList` and `onClear` each rebuild the body from " +
                "the live `body.text`, `ranges`, `blocks` AND `links` at the top of the callback, " +
                "and nothing else does. ⛔ `RichBody(body.text, ranges)` there compiles on the " +
                "defaults: every keystroke would then remap a body that has no list and no link, " +
                "and `onToggleList` would toggle one onto an empty slate — the list on screen is " +
                "erased by the very next character typed, and so is every address.",
            times = 4,
        )
    }

    /**
     * (a bis) ONE remap, and BOTH halves of its answer taken.
     */
    @Test fun `the one remap hands back the styling, the blocks and the links`() {
        assertPinned(
            "ranges = remapped.ranges",
            "the remap's spans must be taken off the SHARED answer.",
        )
        assertPinned(
            "blocks = remapped.blocks",
            "…and its blocks, off that same answer — dropped, a list survives exactly until the " +
                "next character is typed.",
        )
        assertPinned(
            "links = remapped.links",
            "…and its LINKS, off that same answer again. ⛔ Dropped, every link of the body dies " +
                "on the very next keystroke while the styling survives: `remapAfterEdit` is " +
                "executed by `RichBodyLinkTest` and still answers the right links, so the suite " +
                "stays green, the address is gone from the message, and nothing on screen says so.",
        )
        assertEquals(
            "exactly ONE call of `remapAfterEdit(` in $SCREEN: the edit is diffed once.",
            1,
            lines().count { "remapAfterEdit(" in it },
        )
    }

    /** (b) The armed style falls on a caret move WITHOUT a keystroke, and only there. */
    @Test fun `moving the caret without typing disarms the pending style`() {
        val lines = lines()
        val at = lines.indexOf("pending = null")
        assertTrue("$SCREEN must lower `pending` on exactly one line", at >= 0 && lines.count { it == "pending = null" } == 1)
        assertEquals(
            "`pending = null` must be the body of the branch where the text is unchanged and the " +
                "selection moved — lowered on a keystroke instead, Bold armed at the caret would " +
                "style one character and then fall; never lowered, a style armed at one place " +
                "would land wherever the caret is tapped next.",
            "} else if (next.selection != body.selection) {",
            lines.getOrNull(at - 1),
        )
    }

    /** (c) One bar, shown for the focused body and nothing else. */
    @Test fun `the formatting bar is composed once, guarded by the body's focus`() {
        val lines = lines()
        val calls = lines.withIndex().filter { it.value == "FormattingBar(" }.map { it.index }
        assertEquals("expected exactly ONE call of `FormattingBar(` in $SCREEN — one bar, under the body", 1, calls.size)
        assertEquals(
            "the bar must be guarded by `if (bodyFocused) {` and nothing else: unguarded it sits " +
                "under the recipients and the subject too, where its buttons style a body nobody " +
                "is looking at.",
            "if (bodyFocused) {",
            lines.getOrNull(calls.single() - 1),
        )
        assertPinned(
            ".onFocusChanged { bodyFocused = it.isFocused },",
            "`bodyFocused` must be fed by the body field's own `onFocusChanged`, or the guard above " +
                "is a constant.",
        )
    }

    /**
     * (d) The last link of every reopen (#131): the prefill's styling reaching the editor.
     */
    @Test fun `the prefill's styling lands on the editor and on its baseline`() {
        assertPinned(
            "ranges = it.bodyRanges",
            "the prefill's styling must be applied to the editor's live `ranges`, from the " +
                "prefill and nowhere else.",
        )
        assertPinned(
            "baselineRanges = it.bodyRanges",
            "…and the baseline must be the styling the prefill LANDED with, so opening a styled " +
                "draft is not an edit.",
        )
        assertPinned(
            "blocks = it.bodyBlocks",
            "the prefill's LISTS must land the same way: `emptyList()` here reopens a draft that " +
                "held a list without it, and the next save stores that removal.",
        )
        assertPinned(
            "baselineBlocks = it.bodyBlocks",
            "…and on the baseline, or a reopened list counts as an edit the moment it opens and " +
                "the leave guard asks about changes nobody made.",
        )
        assertPinned(
            "links = it.bodyLinks",
            "the prefill's LINKS must land the same way — and this is the sharpest of the three. " +
                "Since the parser accepts an anchor, a draft that carries one is judged " +
                "REPRODUCIBLE (`draftHtmlIsLossy`), so the next save expunges the server " +
                "original. `emptyList()` here does not merely show a message with its addresses " +
                "removed: it destroys the only copy that still had them.",
        )
        assertPinned(
            "baselineLinks = it.bodyLinks",
            "…and on the baseline, or a draft that merely carries a link counts as edited the " +
                "moment it opens.",
        )
    }

    /**
     * (d bis) The bar's link button, and the dialog's answer coming back.
     */
    @Test fun `the link button is lit by linkAt, and opens the dialog`() {
        assertPinned(
            "linkActive = linkAt(rich, span(body.selection)) != null,",
            "the button's lit state must be `linkAt`'s answer on the LIVE body at the caret — the " +
                "very answer the dialog itself opens on (`linkDialogFields`). Anything else and " +
                "the button says one thing while the dialog offers another.",
        )
        assertPinned(
            "onLink = { linkDialog = true },",
            "…and the button must do nothing but open the dialog: a link carries an address, so " +
                "it cannot be toggled on the spot like a family.",
        )
    }

    /** (d ter) The dialog is composed on the LIVE selection — one mutation, one test. */
    @Test fun `the dialog is composed on the live selection`() {
        assertPinned(
            "selection = span(body.selection),",
            "⛔ the dialog is handed the LIVE selection. `span(TextRange(0))` there compiles and " +
                "reads tidy: selecting a word and putting a link on it would then insert the " +
                "label at offset 0 instead of linking what is selected, and the dialog would " +
                "open on whatever link happens to sit at the top of the body.",
        )
    }

    /** (d quater) The caret moves by exactly what was inserted — its DEFINITION, on its own line. */
    @Test fun `the caret moves by exactly what the dialog inserted`() {
        assertPinned(
            "val inserted = applied.text.length - rich.text.length",
            "⛔ and this is the DEFINITION of the caret's shift, pinned apart from its use on the " +
                "next line. `val inserted = 0` leaves that next line LITERALLY UNCHANGED, so a " +
                "rule that only pinned the expression stays green while the caret falls back to " +
                "the start of the label just inserted and the next letter typed lands before it.",
        )
        assertPinned(
            "body = TextFieldValue(applied.text, TextRange(body.selection.max + inserted))",
            "…and the use of it: the new text and a caret moved past what was inserted must both " +
                "land, out of the body OK gave back.",
        )
    }

    /** (d quinquies) …and the body OK gave back lands whole: styling, blocks and links. */
    @Test fun `the dialog's answer lands on the styling, the blocks and the links`() {
        assertPinned(
            "ranges = applied.ranges",
            "⛔ and the styling with it. Dropped, the text grows by N characters while the spans " +
                "stay where they were: normalisation CLAMPS them instead of shifting them, so " +
                "every bold and italic after the insertion point slides N characters to the left " +
                "— on screen and into the next save.",
        )
        assertPinned(
            "blocks = applied.blocks",
            "…and the blocks: `setLink` inserts its label through `remapAfterEdit`, which splits " +
                "and renumbers the items, and dropping its answer leaves the list describing the " +
                "lines as they were BEFORE the insertion.",
        )
        assertPinned(
            "links = applied.links",
            "…and the links, TWICE — once for OK and once for Remove, and neither callback does " +
                "anything else about them. Either one dropped, that button closes the dialog and " +
                "changes nothing at all.",
            times = 2,
        )
    }

    /**
     * (e) The leave guard compares the WHOLE body, blocks included. `RichBody.equals` is what
     */
    @Test fun `the leave guard is given the blocks on both sides`() {
        assertPinned(
            "body = RichBody(body.text, ranges, blocks, links), initialBody = RichBody(initialBody, baselineRanges, baselineBlocks, baselineLinks),",
            "both bodies handed to `ComposeDirty.isDirty` must carry their blocks AND their " +
                "links. ⛔ Either argument dropped compiles on the defaults, and putting a link " +
                "on a word — or taking one off — would then be no edit at all: the back gesture " +
                "leaves without a word and the address is gone.",
        )
    }

    /**
     * (f) The markers are drawn in the TEXT LAYER, by the transformation, and from the live
     */
    @Test fun `the list markers are drawn by the field's visual transformation`() {
        assertPinned(
            "visualTransformation = bodyTransformation(rich, MaterialTheme.colorScheme.primary),",
            "⛔ ONE transformation, and it carries BOTH halves: the markers AND the inline " +
                "styling (`bodyTransformation`, executed in [RichBodyMarkersTest]). A field has " +
                "exactly one `visualTransformation`, so the two cannot be split across two of " +
                "them; and the styling has nowhere else to go, because it may no longer be put " +
                "in the field's VALUE (see the next test). Fed the live `rich` — text, blocks, " +
                "spans and links in one object, so the drawn text and the offsets that go with " +
                "it are computed from the same body. ⛔ `VisualTransformation.None` or a " +
                "markers-only transformation there: a list is invisible while it is written, or " +
                "every bold, italic, underline, strike and link is. And the link colour is handed " +
                "in from the THEME here — a hard-coded one is unreadable in one of the two " +
                "themes, and is also what would let `styledBody` stop being pure.",
        )
        assertPinned(
            "val rich = RichBody(body.text, ranges, blocks, links)",
            "…and the body the field DRAWS carries the blocks AND the links too: it is what the " +
                "transformation is fed, so a bullet is drawn and an anchor is coloured, and it is " +
                "what lights the right button of the bar (`listKindAt(rich, …)`, `linkAt(rich, …)`).",
        )
        assertPinned(
            "value = body,",
            "⛔ the value handed to the field is FLAT and is `body` ITSELF, whole line: plain " +
                "text, `body`'s own selection, `body`'s own composition. Nothing may be laid on " +
                "it on the way in — see the next test for what a span in there costs.",
        )
        assertPinned(
            "body = TextFieldValue(next.text, next.selection, next.composition)",
            "⛔ …and the value written BACK carries `next.composition` — the OTHER half, and the " +
                "necessary one. The flat value above is what stops Compose from throwing the " +
                "composing region away; this argument is what hands it back. Dropped, the field " +
                "answers `null` composition on every keystroke and the symptom returns for EVERY " +
                "body, styled or not: suggestion strip blinking, autocorrect dead, CJK / voice / " +
                "glide typing broken. ⚠ The two-argument `TextFieldValue(text, selection)` " +
                "compiles on the default, silently.",
        )
        assertPinned(
            "activeList = listKindAt(rich, span(body.selection)),",
            "the two list buttons are lit by `listKindAt` — executed in [RichBodyMarkersTest] — " +
                "read off the live selection, never off a remembered value.",
        )
        assertPinned(
            "blocks = toggleBlock(current, span(body.selection), kind).blocks",
            "…and the tap itself is `toggleBlock` (executed in `RichBodyTest`) on the LIVE body " +
                "and the LIVE selection. ⚠ No `pending` and no empty-selection guard here, unlike " +
                "the inline families: the caret's own line is the target.",
        )
    }

    /**
     * (f bis) THE rule this whole branch exists for: NOT ONE SPAN IN THE FIELD'S VALUE.
     */
    @Test fun `no span is ever laid on the value the body field is handed`() {
        // CASE-INSENSITIVE, and that is the whole reach of this rule: `annotatedString =`,
        // `toAnnotatedString(`, `AnnotatedString(` and `buildAnnotatedString {` are four spellings
        val offenders = lines().filter { "annotatedstring" in it.lowercase() }
        assertEquals(
            "no line of CODE in $SCREEN may name an ANNOTATED STRING, in any spelling: a span " +
                "in the body field's value destroys the IME's composing region on EVERY " +
                "keystroke — the suggestion strip blinks, autocorrect stops applying, and a " +
                "composing IME (CJK, voice input, glide typing) is broken outright. The styling " +
                "belongs one layer down, in `bodyTransformation`, which the IME never sees.\n" +
                "Offending line(s):",
            emptyList<String>(),
            offenders,
        )
        assertTrue(
            "…and this rule is only honest because `lines()` cuts comments out: the KDoc above " +
                "the field NAMES `toAnnotatedString` to explain why it is gone. If that stripping " +
                "ever stops working, the prose alone would fail the rule above — so the presence " +
                "of that comment is pinned here, and prose neither satisfies nor violates a rule.",
            File(root, SCREEN).readLines().any { it.trim().startsWith("//") && "toAnnotatedString" in it },
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
        const val REMAP =
            "val remapped = remapAfterEdit(current, next.text, span(body.selection), span(next.selection), pending)"

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
