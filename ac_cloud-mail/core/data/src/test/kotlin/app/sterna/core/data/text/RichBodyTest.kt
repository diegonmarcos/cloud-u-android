package app.sterna.core.data.text

import app.sterna.core.data.text.BlockKind.BULLET
import app.sterna.core.data.text.BlockKind.NUMBER
import app.sterna.core.data.text.Inline.BOLD
import app.sterna.core.data.text.Inline.ITALIC
import app.sterna.core.data.text.Inline.STRIKE
import app.sterna.core.data.text.Inline.UNDERLINE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RichBodyTest {

    private fun sp(s: Int, e: Int) = Span(s, e)
    private fun cursor(c: Int) = Span(c, c)
    private fun body(text: String, vararg ranges: Pair<Inline, List<Span>>) = RichBody.of(text, mapOf(*ranges))
    private fun link(text: String, vararg links: Link) = RichBody(text, emptyMap(), links = links.toList())
    private fun ranges(vararg ranges: Pair<Inline, List<Span>>) = mapOf(*ranges)
    private fun blk(kind: BlockKind, from: Int, to: Int) = Block(kind, from..to)
    private fun listBody(text: String, blocks: List<Block>, vararg ranges: Pair<Inline, List<Span>>) =
        RichBody.of(text, mapOf(*ranges), blocks)

    /** An edit with no selection hint beyond "the cursor sits after the edit", and no pending. */
    private fun edit(old: RichBody, newText: String, cursorAfter: Int, pending: Set<Inline>? = null) =
        remapAfterEdit(old, newText, cursor(0), cursor(cursorAfter), pending)

    // --- normalisation ---------------------------------------------------------------------------

    @Test fun normaliseSortsAndMergesAdjacentAndOverlapping() {
        val b = body("abcdefghij", BOLD to listOf(sp(5, 7), sp(0, 2), sp(2, 4), sp(6, 9)))
        assertEquals(ranges(BOLD to listOf(sp(0, 4), sp(5, 9))), b.ranges)
    }

    @Test fun normaliseDropsEmptyAndClampsToText() {
        val b = body("abcdefghij", BOLD to listOf(sp(3, 3), sp(-2, 1), sp(8, 20)))
        assertEquals(ranges(BOLD to listOf(sp(0, 1), sp(8, 10))), b.ranges)
    }

    @Test fun normaliseDropsAFamilyWithoutSpans() {
        val b = body("abc", BOLD to emptyList(), ITALIC to listOf(sp(2, 2)))
        assertTrue(b.ranges.isEmpty())
        assertTrue(b.isPlain)
        assertEquals(RichBody.plain("abc"), b)
    }

    @Test fun equalityIsStructuralAfterNormalisation() {
        val x = body("abcdef", BOLD to listOf(sp(0, 2), sp(2, 4)), ITALIC to listOf(sp(1, 3)))
        val y = body("abcdef", ITALIC to listOf(sp(1, 3)), BOLD to listOf(sp(0, 4)))
        assertEquals(x, y)
        assertEquals(x.hashCode(), y.hashCode())
        assertTrue(RichBody.plain("abc") == body("abc"))
        assertTrue(body("abc", BOLD to listOf(sp(0, 1))) != body("abc", BOLD to listOf(sp(0, 2))))
        assertTrue(body("abc") != body("abd"))
    }

    // --- remapAfterEdit ----------------------------------------------------------------------------

    private val helloBold = body("hello world", BOLD to listOf(sp(0, 5)))
    private val worldBold = body("hello world", BOLD to listOf(sp(6, 11)))

    @Test fun insertInsideARangeIsAbsorbed() {
        val r = edit(helloBold, "heXllo world", 3)
        assertEquals(body("heXllo world", BOLD to listOf(sp(0, 6))), r)
    }

    @Test fun insertAtRangeEndIsAbsorbedSticky() {
        val r = edit(helloBold, "helloX world", 6, pending = null)
        assertEquals(body("helloX world", BOLD to listOf(sp(0, 6))), r)
    }

    @Test fun insertAtRangeStartIsNotAbsorbedButShifted() {
        val r = edit(worldBold, "hello Xworld", 7)
        assertEquals(body("hello Xworld", BOLD to listOf(sp(7, 12))), r)
    }

    @Test fun insertBeforeARangeShiftsIt() {
        val r = edit(worldBold, "Xhello world", 1)
        assertEquals(body("Xhello world", BOLD to listOf(sp(7, 12))), r)
    }

    @Test fun insertAfterARangeLeavesIt() {
        val r = edit(helloBold, "hello worldX", 12)
        assertEquals(body("hello worldX", BOLD to listOf(sp(0, 5))), r)
    }

    @Test fun deleteTrimsAPartlyCoveredRange() {
        // remove [3,7) = "lo w"
        val r = edit(helloBold, "helorld", 3)
        assertEquals(body("helorld", BOLD to listOf(sp(0, 3))), r)
    }

    @Test fun deleteSwallowsARangeInsideIt() {
        val r = edit(worldBold, "hello", 5)
        assertEquals(RichBody.plain("hello"), r)
        assertTrue(r.isPlain)
    }

    @Test fun deleteShiftsARangeAfterIt() {
        val r = edit(worldBold, "hell world", 4)
        assertEquals(body("hell world", BOLD to listOf(sp(5, 10))), r)
    }

    @Test fun replacingAMixedSelectionTakesTheStyleThatPrecedesIt() {
        // select [3,8) = "lo wo", bold only on [0,5): no family covers the whole selection, so the
        // sticky rule decides and "XY" inherits the bold that ends where the selection began.
        val r = remapAfterEdit(helloBold, "helXYrld", sp(3, 8), cursor(5), null)
        assertEquals(body("helXYrld", BOLD to listOf(sp(0, 5))), r)
    }

    @Test fun replacingAFullyStyledSelectionKeepsItsStyle() {
        // select "world" (all bold), type "W": the letter is bold, as in Gmail.
        val r = remapAfterEdit(worldBold, "hello W", sp(6, 11), cursor(7), null)
        assertEquals(body("hello W", BOLD to listOf(sp(6, 7))), r)
        // The same with two families covering the selection: both are kept, and no other.
        val two = body("hello world", BOLD to listOf(sp(6, 11)), ITALIC to listOf(sp(0, 11)))
        val r2 = remapAfterEdit(two, "hello W", sp(6, 11), cursor(7), null)
        assertEquals(body("hello W", BOLD to listOf(sp(6, 7)), ITALIC to listOf(sp(0, 7))), r2)
        // REVERSED: pending used to take priority over the inherited style. It is now IGNORED on
        // a replacement — the inherited style wins — because an IME commits a word as one
        // replacement, and a style armed after the word was typed must not reach back over it.
        val r3 = remapAfterEdit(worldBold, "hello W", sp(6, 11), cursor(7), setOf(ITALIC))
        assertEquals(body("hello W", BOLD to listOf(sp(6, 7))), r3)
    }

    // --- pending applies to a pure insertion only ----------------------------------------------
    //
    // Gboard replaces `helo` + space by `lo ` in one batch: the edit is [3, 4) → "lo ". With Bold
    // armed AFTER `helo` was typed, the inserted "lo" must NOT become bold: those are letters typed
    // before the button. The caller keeps pending armed, so the NEXT pure keystroke receives it.

    @Test fun pendingIsIgnoredByAnImeReplacementAndReceivedByTheNextPureInsertion() {
        val helo = RichBody.plain("helo")
        val committed = remapAfterEdit(helo, "hello ", cursor(4), cursor(6), setOf(BOLD))
        assertEquals("an autocorrect commit must not bold letters typed before the button",
            RichBody.plain("hello "), committed)
        val next = remapAfterEdit(committed, "hello x", cursor(6), cursor(7), setOf(BOLD))
        assertEquals("the pure keystroke that follows receives the armed style",
            body("hello x", BOLD to listOf(sp(6, 7))), next)
    }

    @Test fun anImeReplacementInheritsTheReplacedStyleEvenWithEmptyPending() {
        val helo = body("helo", BOLD to listOf(sp(0, 4)))
        val committed = remapAfterEdit(helo, "hello ", cursor(4), cursor(6), emptySet())
        assertEquals("the commit replaces bold letters: what it inserts is bold, Bold-off or not",
            body("hello ", BOLD to listOf(sp(0, 6))), committed)
        val next = remapAfterEdit(committed, "hello x", cursor(6), cursor(7), emptySet())
        assertEquals("…and the pure keystroke after it is roman, as armed",
            body("hello x", BOLD to listOf(sp(0, 6))), next)
    }

    @Test fun replacingASelectionStraddlingAStyleGivesRomanAndShiftsTheRest() {
        // select [3,8) = "lo wo" over bold [6,11): "X" is roman, "rld" stays bold and shifts.
        val r = remapAfterEdit(worldBold, "helXrld", sp(3, 8), cursor(4), null)
        assertEquals(body("helXrld", BOLD to listOf(sp(4, 7))), r)
    }

    @Test fun pendingItalicAtEndOfBoldGivesItalicNotBold() {
        val r = edit(helloBold, "helloX world", 6, pending = setOf(ITALIC))
        assertEquals(body("helloX world", BOLD to listOf(sp(0, 5)), ITALIC to listOf(sp(5, 6))), r)
    }

    @Test fun noPendingAtEndOfBoldGivesBold() {
        val r = edit(helloBold, "helloXY world", 7, pending = null)
        assertEquals(ranges(BOLD to listOf(sp(0, 7))), r.ranges)
    }

    @Test fun emptyPendingAtEndOfBoldGivesRomanAndDoesNotStretchTheRange() {
        // Cursor at the end of a bold word, Bold switched off, then typing: an EMPTY set is an
        // instruction ("no style"), unlike null.
        val r = edit(helloBold, "helloXY world", 7, pending = emptySet())
        assertEquals(body("helloXY world", BOLD to listOf(sp(0, 5))), r)
        // REVERSED: it used to override what a replaced selection inherited. A replacement now
        // ignores pending, empty or not: the inserted letter keeps the bold it replaced.
        val r2 = remapAfterEdit(worldBold, "hello W", sp(6, 11), cursor(7), emptySet())
        assertEquals(body("hello W", BOLD to listOf(sp(6, 7))), r2)
    }

    @Test fun pendingBoldInsideAnItalicRangeSplitsTheItalic() {
        val it = body("hello", ITALIC to listOf(sp(0, 5)))
        val r = edit(it, "heXllo", 3, pending = setOf(BOLD))
        assertEquals(body("heXllo", BOLD to listOf(sp(2, 3)), ITALIC to listOf(sp(0, 2), sp(3, 6))), r)
    }

    @Test fun pendingCoversTheInsertedSegmentExactly() {
        val r = edit(RichBody.plain("ab"), "aXYb", 3, pending = setOf(BOLD, UNDERLINE))
        assertEquals(body("aXYb", BOLD to listOf(sp(1, 3)), UNDERLINE to listOf(sp(1, 3))), r)
    }

    @Test fun unchangedTextReturnsTheSameInstance() {
        val r = remapAfterEdit(helloBold, "hello world", cursor(2), cursor(3), setOf(ITALIC))
        assertSame(helloBold, r)
    }

    @Test fun insertAtEndOfAnEmptyBody() {
        assertEquals(RichBody.plain("a"), edit(RichBody.plain(""), "a", 1))
        assertEquals(body("a", BOLD to listOf(sp(0, 1))), edit(RichBody.plain(""), "a", 1, pending = setOf(BOLD)))
    }

    @Test fun ambiguousInsertIsCutAtTheCursor() {
        // "aaa" with bold on [0,2); typing "a" at position 0 leaves cursor at 1. The maximal common
        // prefix would put the insertion at 3 (keeping bold on [0,2)); the cursor says it was at 0.
        val b = body("aaa", BOLD to listOf(sp(0, 2)))
        val r = remapAfterEdit(b, "aaaa", cursor(0), cursor(1), null)
        assertEquals(body("aaaa", BOLD to listOf(sp(1, 3))), r)
    }

    @Test fun ambiguousDeleteIsCutAtTheCursor() {
        // "aa|a" bold on [0,2), backspace deletes index 1 and leaves the cursor at 1.
        val b = body("aaa", BOLD to listOf(sp(0, 2)))
        val r = remapAfterEdit(b, "aa", cursor(2), cursor(1), null)
        assertEquals(body("aa", BOLD to listOf(sp(0, 1))), r)
    }

    @Test fun anUnusableCursorHintFallsBackToTheMaximalPrefix() {
        // Insertion at the end of "hello", but the cursor says 0 (no cut ends there) or 2 (no cut
        // is consistent with it either): the diff must not crash and must keep the prefix cut.
        assertEquals(body("hellox", BOLD to listOf(sp(0, 6))), edit(body("hello", BOLD to listOf(sp(0, 5))), "hellox", 0))
        assertEquals(body("hellox", BOLD to listOf(sp(0, 6))), edit(body("hello", BOLD to listOf(sp(0, 5))), "hellox", 2))
        assertEquals(RichBody.plain("hellox"), remapAfterEdit(RichBody.plain("hello"), "hellox", cursor(0), cursor(0), null))
    }

    @Test fun oldSelectionSettlesTheDiffWhenTheCursorDoesNot() {
        // "aaa" bold on [0,2); the first "a" was selected and removed, but the cursor reported
        // at 2 fits no cut: the maximal prefix would delete index 2 (bold stays [0,2)), the old
        // selection says index 0 went (bold becomes [0,1)).
        val b = body("aaa", BOLD to listOf(sp(0, 2)))
        val r = remapAfterEdit(b, "aa", sp(0, 1), cursor(2), null)
        assertEquals(body("aa", BOLD to listOf(sp(0, 1))), r)
    }

    @Test fun remapResultIsNormalised() {
        val b = body("ab", BOLD to listOf(sp(0, 1)))
        val r = edit(b, "aXb", 2, pending = setOf(BOLD))
        assertEquals(listOf(sp(0, 2)), r.ranges[BOLD])
    }

    // --- toggle / clear ---------------------------------------------------------------------------

    @Test fun toggleCoversAMixedSelection() {
        val b = body("hello world", BOLD to listOf(sp(0, 3)))
        assertEquals(body("hello world", BOLD to listOf(sp(0, 5))), toggle(b, sp(0, 5), BOLD))
    }

    @Test fun toggleRemovesAFullyCoveredSelection() {
        val b = body("hello world", BOLD to listOf(sp(0, 11)))
        assertEquals(body("hello world", BOLD to listOf(sp(0, 3), sp(6, 11))), toggle(b, sp(3, 6), BOLD))
        assertEquals(RichBody.plain("hello world"), toggle(b, sp(0, 11), BOLD))
    }

    @Test fun toggleWithAnEmptySelectionChangesNothing() {
        assertEquals(helloBold, toggle(helloBold, cursor(2), ITALIC))
        assertEquals(helloBold, toggle(helloBold, cursor(2), BOLD))
    }

    @Test fun toggleLeavesTheOtherFamiliesAlone() {
        val b = body("hello", BOLD to listOf(sp(0, 5)), ITALIC to listOf(sp(0, 5)))
        assertEquals(body("hello", ITALIC to listOf(sp(0, 5))), toggle(b, sp(0, 5), BOLD))
        assertEquals(
            body("hello", BOLD to listOf(sp(0, 5)), ITALIC to listOf(sp(0, 5)), STRIKE to listOf(sp(1, 3))),
            toggle(b, sp(1, 3), STRIKE),
        )
    }

    @Test fun clearRemovesEveryFamilyOnTheSelection() {
        val b = body("abcdefgh", BOLD to listOf(sp(0, 5)), ITALIC to listOf(sp(2, 8)))
        assertEquals(
            body("abcdefgh", BOLD to listOf(sp(0, 3)), ITALIC to listOf(sp(2, 3), sp(6, 8))),
            clear(b, sp(3, 6)),
        )
        assertEquals(b, clear(b, cursor(4)))
    }

    // --- stylesAt ----------------------------------------------------------------------------------

    private val overlap = body("abcdefghij", BOLD to listOf(sp(0, 5)), ITALIC to listOf(sp(3, 8)))

    @Test fun stylesAtACoveredSelection() {
        assertEquals(setOf(BOLD, ITALIC), stylesAt(overlap, sp(3, 5)))
        assertEquals(setOf(BOLD), stylesAt(overlap, sp(0, 2)))
    }

    @Test fun stylesAtAPartlyCoveredSelectionIsEmpty() {
        assertEquals(emptySet<Inline>(), stylesAt(overlap, sp(2, 6)))
        assertEquals(setOf(ITALIC), stylesAt(overlap, sp(5, 8)))
    }

    @Test fun cursorAtRangeEndCountsIt() {
        assertEquals(setOf(BOLD, ITALIC), stylesAt(overlap, cursor(5)))
        assertEquals(setOf(ITALIC), stylesAt(overlap, cursor(8)))
    }

    @Test fun cursorAtRangeStartDoesNotCountIt() {
        assertEquals(setOf(BOLD), stylesAt(overlap, cursor(3)))
        assertEquals(emptySet<Inline>(), stylesAt(overlap, cursor(0)))
    }

    @Test fun cursorInsideARange() {
        assertEquals(setOf(BOLD), stylesAt(overlap, cursor(2)))
        assertEquals(setOf(BOLD, ITALIC), stylesAt(overlap, cursor(4)))
        assertEquals(emptySet<Inline>(), stylesAt(overlap, cursor(9)))
    }

    // --- toHtml ------------------------------------------------------------------------------------

    @Test fun plainBodyRendersExactlyLikeHtmlEscapeMultiline() {
        val t = "a < b & c > d\n\"quoted\" 'x' &amp;\n\nend"
        assertEquals(htmlEscapeMultiline(t), toHtml(RichBody.plain(t)))
        assertEquals("", toHtml(RichBody.plain("")))
    }

    @Test fun boldWord() {
        assertEquals("<b>fort</b>", toHtml(body("fort", BOLD to listOf(sp(0, 4)))))
        assertEquals("un mot <b>fort</b>", toHtml(body("un mot fort", BOLD to listOf(sp(7, 11)))))
    }

    @Test fun coincidingFamiliesNestInCanonicalOrder() {
        assertEquals("<b><i>x</i></b>", toHtml(body("x", ITALIC to listOf(sp(0, 1)), BOLD to listOf(sp(0, 1)))))
        val all = body(
            "x",
            STRIKE to listOf(sp(0, 1)), UNDERLINE to listOf(sp(0, 1)),
            ITALIC to listOf(sp(0, 1)), BOLD to listOf(sp(0, 1)),
        )
        assertEquals("<b><i><u><s>x</s></u></i></b>", toHtml(all))
    }

    @Test fun crossingFamiliesCloseAndReopenToKeepTheOrder() {
        val b = body("abcdefghi", BOLD to listOf(sp(0, 6)), ITALIC to listOf(sp(3, 9)))
        assertEquals("<b>abc<i>def</i></b><i>ghi</i>", toHtml(b))
        // The mirror: italic first, then bold — the inner one still closes before the outer opens.
        val c = body("abcdefghi", ITALIC to listOf(sp(0, 6)), BOLD to listOf(sp(3, 9)))
        assertEquals("<i>abc</i><b><i>def</i>ghi</b>", toHtml(c))
    }

    @Test fun aRangeCrossesANewline() {
        assertEquals("<b>a<br>b</b>", toHtml(body("a\nb", BOLD to listOf(sp(0, 3)))))
    }

    @Test fun textInsideARangeIsEscaped() {
        assertEquals("<b>a&lt;b&amp;</b>", toHtml(body("a<b&", BOLD to listOf(sp(0, 4)))))
    }

    @Test fun adjacentSpansOfOneFamilyRenderAsOneTag() {
        assertEquals("<b>abcd</b>", toHtml(body("abcd", BOLD to listOf(sp(0, 2), sp(2, 4)))))
    }

    @Test fun verbatimReplacesARangeWithoutEscaping() {
        val b = RichBody.plain("Hi\n-- \nSIG")
        assertEquals("Hi<br>-- <br><table>x</table>", toHtml(b, sp(7, 10) to "<table>x</table>"))
        val styled = body("Hi\nSIG", BOLD to listOf(sp(0, 2)))
        assertEquals("<b>Hi</b><br><p>s</p>", toHtml(styled, sp(3, 6) to "<p>s</p>"))
    }

    // --- fromHtml ----------------------------------------------------------------------------------

    @Test fun anythingOutsideTheSubsetIsNull() {
        val rejected = listOf(
            """<b style="x">t</b>""", "<p>t</p>", "<div>t</div>", """<a href="x" id="y">t</a>""",
            "<span>t</span>", "<!DOCTYPE html><b>t</b>", "<html>t</html>", "<!-- c -->t",
            "t&nbsp;u", "a < b", "t</b>", "<b>t", "<b><i>t</b></i>", "<b/>t", "a</br>b",
            "a & b", "&#0;", "&#xD800;", "a > b", "<b >t</b>", "<br x>",
            "&#x110000;", "&#1114112;", "a\nb", "a&#10;b", "<b>a\n</b>",
        )
        for (h in rejected) assertNull("should reject: $h", fromHtml(h))
    }

    @Test fun aliasesMapToTheirFamily() {
        assertEquals(
            body("abcd", BOLD to listOf(sp(0, 1)), ITALIC to listOf(sp(1, 2)), STRIKE to listOf(sp(2, 4))),
            fromHtml("<strong>a</strong><em>b</em><del>c</del><strike>d</strike>"),
        )
        assertEquals(
            body("ab", BOLD to listOf(sp(0, 1)), UNDERLINE to listOf(sp(1, 2))),
            fromHtml("<B>a</B><U>b</u>"),
        )
    }

    @Test fun brVariantsBecomeNewlines() {
        assertEquals(RichBody.plain("a\nb\nc\nd\ne"), fromHtml("a<br>b<br/>c<br />d<BR>e"))
    }

    @Test fun entitiesAreDecoded() {
        assertEquals(RichBody.plain("&<>\"'éé€"), fromHtml("&amp;&lt;&gt;&quot;&#39;&#233;&#xE9;&#x20AC;"))
    }

    @Test fun emptyTagsAreIgnored() {
        assertEquals(RichBody.plain("x"), fromHtml("<b></b>x<i></i>"))
        assertEquals(RichBody.plain(""), fromHtml(""))
    }

    @Test fun nestedAndOverlappingTagsGiveNormalisedRanges() {
        assertEquals(
            body("abc", BOLD to listOf(sp(0, 3)), ITALIC to listOf(sp(1, 2))),
            fromHtml("<b>a<i>b</i>c</b>"),
        )
        assertEquals(body("ab", BOLD to listOf(sp(0, 2))), fromHtml("<b>a</b><b>b</b>"))
        assertEquals(body("ab", BOLD to listOf(sp(0, 2))), fromHtml("<b><b>a</b>b</b>"))
    }

    @Test fun roundTripThroughHtmlIsTheIdentity() {
        val bodies = listOf(
            RichBody.plain(""),
            RichBody.plain("just text\nwith a & b < c > d \"q\" 'a'"),
            body("fort", BOLD to listOf(sp(0, 4))),
            body("abcdefghi", BOLD to listOf(sp(0, 6)), ITALIC to listOf(sp(3, 9))),
            body("abcdefghi", ITALIC to listOf(sp(0, 6)), BOLD to listOf(sp(3, 9))),
            body("a\nb\nc", BOLD to listOf(sp(0, 5)), UNDERLINE to listOf(sp(1, 4))),
            body("x < y & z", STRIKE to listOf(sp(0, 3)), ITALIC to listOf(sp(2, 9))),
            body("tout", BOLD to listOf(sp(0, 4)), ITALIC to listOf(sp(0, 4)), UNDERLINE to listOf(sp(0, 4)), STRIKE to listOf(sp(0, 4))),
            body("abcd", BOLD to listOf(sp(0, 2), sp(2, 4))),
            body("abcdef", BOLD to listOf(sp(0, 1), sp(3, 4)), ITALIC to listOf(sp(1, 2), sp(4, 5)), STRIKE to listOf(sp(2, 3), sp(5, 6))),
            body("aaaa\n\nbbbb", UNDERLINE to listOf(sp(2, 8)), BOLD to listOf(sp(4, 6))),
            body("é€😀x", BOLD to listOf(sp(0, 1)), ITALIC to listOf(sp(2, 4))),
            body("a\r\nb\tc", BOLD to listOf(sp(0, 4)), STRIKE to listOf(sp(3, 6))),
            RichBody.plain("\r\n\t"),
            // …and the same property with blocks: the list markup must survive the trip too.
            listBody("a\nb", listOf(blk(BULLET, 0, 1))),
            listBody("a\nb\nc", listOf(blk(NUMBER, 1, 1))),
            listBody("", listOf(blk(BULLET, 0, 0))),
            listBody("a\n\nb", listOf(blk(NUMBER, 0, 2))),
            listBody("x < y\n& z", listOf(blk(BULLET, 0, 1)), BOLD to listOf(sp(0, 3))),
            listBody(
                "a\nb\nc\nd",
                listOf(blk(BULLET, 0, 0), blk(NUMBER, 1, 2)),
                ITALIC to listOf(sp(4, 9)),
            ),
            // …and the links (#131), which nest OUTSIDE the four families.
            link("un lien", Link(sp(0, 7), "https://example.org")),
            RichBody("un lien fort", mapOf(BOLD to listOf(sp(3, 7))), links = listOf(Link(sp(3, 7), "https://x"))),
            RichBody("abcdefghij", mapOf(BOLD to listOf(sp(1, 9))), links = listOf(Link(sp(3, 6), "https://x"))),
            // Two touching links under ONE url: merged, they would come back as a single anchor.
            link("abcd", Link(sp(0, 2), "https://x"), Link(sp(2, 4), "https://x")),
            link("x", Link(sp(0, 1), "https://e.org/?a=1&b=2<3>4\"q\"'s' z")),
            link("é€😀x", Link(sp(0, 4), "mailto:iris@example.org")),
        )
        for (x in bodies) {
            val html = toHtml(x)
            val back = fromHtml(html)
            assertNotNull("fromHtml rejected its own output: $html", back)
            assertEquals("round trip of $html", x, back)
        }
    }

    @Test fun plainTextIsTheText() {
        assertEquals("hello world", toPlainText(helloBold))
    }

    // --- blocks: the model ---------------------------------------------------------------------

    @Test fun lineCountIsNewlinesPlusOne() {
        assertEquals(1, RichBody.plain("").lineCount)
        assertEquals(1, RichBody.plain("abc").lineCount)
        assertEquals(2, RichBody.plain("a\nb").lineCount)
        assertEquals(3, RichBody.plain("a\nb\n").lineCount)
    }

    @Test fun blocksAreClampedSortedMergedAndEmptiesDropped() {
        val b = listBody(
            "a\nb\nc\nd",
            listOf(
                blk(NUMBER, 7, 9),      // wholly past the end: gone, NOT clamped onto the last line
                blk(BULLET, 2, 2),
                blk(BULLET, 0, 0),
                blk(BULLET, -3, 1),     // clamped at the start
                Block(BULLET, 2..1),    // empty
            ),
        )
        assertEquals(listOf(Block(BULLET, 0..2)), b.blocks)
    }

    @Test fun theLastBlockWrittenTakesTheDisputedLine() {
        // Two kinds never overlap once normalised: the one written last owns the shared line.
        assertEquals(
            listOf(Block(BULLET, 0..0), Block(NUMBER, 1..2)),
            listBody("a\nb\nc", listOf(blk(BULLET, 0, 1), blk(NUMBER, 1, 2))).blocks,
        )
        assertEquals(
            listOf(Block(BULLET, 0..1), Block(NUMBER, 2..2)),
            listBody("a\nb\nc", listOf(blk(NUMBER, 1, 2), blk(BULLET, 0, 1))).blocks,
        )
    }

    @Test fun aBlockMakesTheBodyRichAndCountsInEquality() {
        val listed = listBody("a", listOf(blk(BULLET, 0, 0)))
        assertFalse("a body with a list is not plain — a draft with a list must store HTML", listed.isPlain)
        assertTrue(RichBody.plain("a").isPlain)
        assertTrue(listed != RichBody.plain("a"))
        assertTrue(listed != listBody("a", listOf(blk(NUMBER, 0, 0))))
        assertEquals(listed, listBody("a", listOf(blk(BULLET, 0, 0), blk(BULLET, 0, 0))))
        assertEquals(listed.hashCode(), listBody("a", listOf(blk(BULLET, 0, 0))).hashCode())
        // an out-of-range block is no block at all
        assertEquals(RichBody.plain("a"), listBody("a", listOf(blk(BULLET, 3, 4))))
        // hashCode must move with the blocks (0..0 and 0..1 hash differently, deterministically)
        assertTrue(
            listBody("a\nb", listOf(blk(BULLET, 0, 0))).hashCode() !=
                listBody("a\nb", listOf(blk(BULLET, 0, 1))).hashCode(),
        )
        assertTrue(listed.toString().contains("Block(kind=BULLET, lines=0..0)"))
    }

    @Test fun aStyleDoesNotCoverTheBreakBetweenTwoItems() {
        // `</li><li>` carries no character, so the newline it stands for carries no style either.
        val b = listBody("a\nb", listOf(blk(BULLET, 0, 1)), BOLD to listOf(sp(0, 3)))
        assertEquals(listOf(sp(0, 1), sp(2, 3)), b.ranges[BOLD])
        // outside any list the newline keeps its style, as before
        assertEquals(listOf(sp(0, 3)), body("a\nb", BOLD to listOf(sp(0, 3))).ranges[BOLD])
    }

    // --- toggleBlock ---------------------------------------------------------------------------

    @Test fun toggleBlockMarksEveryLineTheSelectionTouches() {
        val b = RichBody.plain("un\ndeux\ntrois")
        val r = toggleBlock(b, sp(1, 5), BULLET)   // "n\nde": lines 0 and 1, not 2
        assertEquals(listOf(Block(BULLET, 0..1)), r.blocks)
        assertEquals("un\ndeux\ntrois", r.text)
    }

    @Test fun aSelectionStoppingAtALineStartDoesNotReachIntoThatLine() {
        // "un\n" — the selection ends exactly where line 1 begins, and takes nothing of it. Read
        // with `end` instead of `end - 1`, a line the user never selected becomes a bullet.
        val b = RichBody.plain("un\ndeux\ntrois")
        assertEquals(listOf(Block(BULLET, 0..0)), toggleBlock(b, sp(0, 3), BULLET).blocks)
    }

    @Test fun toggleBlockOnAnEmptySelectionTakesTheCursorLine() {
        // Unlike `toggle` on an inline style, a bare cursor is enough here: Gmail's gesture.
        val b = RichBody.plain("un\ndeux")
        assertEquals(listOf(Block(NUMBER, 1..1)), toggleBlock(b, cursor(5), NUMBER).blocks)
        assertEquals(listOf(Block(NUMBER, 0..0)), toggleBlock(b, cursor(0), NUMBER).blocks)
    }

    @Test fun toggleBlockRemovesOnlyWhenEveryTouchedLineAlreadyCarriesTheKind() {
        val all = listBody("a\nb\nc", listOf(blk(BULLET, 0, 2)))
        assertEquals(emptyList<Block>(), toggleBlock(all, sp(0, 5), BULLET).blocks)
        assertTrue(toggleBlock(all, sp(0, 5), BULLET).isPlain)
        // one line short of the kind: the whole selection becomes that kind instead
        val mixed = listBody("a\nb\nc", listOf(blk(BULLET, 0, 0)))
        assertEquals(listOf(Block(BULLET, 0..2)), toggleBlock(mixed, sp(0, 5), BULLET).blocks)
        // and the other kind always applies
        assertEquals(listOf(Block(NUMBER, 0..2)), toggleBlock(all, sp(0, 5), NUMBER).blocks)
    }

    @Test fun toggleBlockOffInTheMiddleSplitsTheList() {
        val all = listBody("a\nb\nc", listOf(blk(BULLET, 0, 2)))
        assertEquals(
            listOf(Block(BULLET, 0..0), Block(BULLET, 2..2)),
            toggleBlock(all, cursor(2), BULLET).blocks,
        )
    }

    @Test fun togglingAnInlineStyleLeavesTheListsAlone() {
        // `toggle` and `clear` rebuild the body: dropping its blocks there makes one tap on Bold
        // — or on "clear formatting" — erase every list in the message, in silence.
        val listed = listBody("a\nb", listOf(blk(BULLET, 0, 1)))
        assertEquals(listed.blocks, toggle(listed, sp(0, 1), BOLD).blocks)
        assertEquals(listed.blocks, toggle(toggle(listed, sp(0, 1), BOLD), sp(0, 1), BOLD).blocks)
        assertEquals(listed.blocks, clear(listBody("a\nb", listOf(blk(BULLET, 0, 1)), BOLD to listOf(sp(0, 1))), sp(0, 1)).blocks)
    }

    @Test fun anInlineStyleIsStillRemovableAcrossTwoItemsOfAList() {
        // The break `</li><li>` stands for carries no character, so no span may cover it — which
        // means a selection running over two items is never inside ONE span. Read as "not covered",
        // the button goes dark on two bold items and the tap APPLIES bold instead of removing it:
        // bold becomes impossible to take off inside a list, with no way back but "clear
        // formatting".
        val two = listBody("a\nb", listOf(blk(BULLET, 0, 1)), BOLD to listOf(sp(0, 3)))
        assertEquals(listOf(sp(0, 1), sp(2, 3)), two.ranges[BOLD])
        assertEquals(setOf(BOLD), stylesAt(two, sp(0, 3)))
        assertTrue("the tap must REMOVE it", toggle(two, sp(0, 3), BOLD).ranges[BOLD] == null)
        // …and a selection where one of the two items is NOT styled still applies, as before.
        val half = listBody("a\nb", listOf(blk(BULLET, 0, 1)), BOLD to listOf(sp(0, 1)))
        assertEquals(emptySet<Inline>(), stylesAt(half, sp(0, 3)))
        assertEquals(listOf(sp(0, 1), sp(2, 3)), toggle(half, sp(0, 3), BOLD).ranges[BOLD])
    }

    // --- remapAfterEdit and the blocks -----------------------------------------------------------

    @Test fun enterInsideAnItemGivesTwoItemsOfTheSameList() {
        val b = listBody("ab", listOf(blk(BULLET, 0, 0)))
        val r = edit(b, "a\nb", 2)
        assertEquals(listOf(Block(BULLET, 0..1)), r.blocks)
    }

    @Test fun deletingTheNewlineBetweenTwoItemsMergesThem() {
        val b = listBody("a\nb", listOf(blk(BULLET, 0, 1)))
        val r = edit(b, "ab", 1)
        assertEquals(listOf(Block(BULLET, 0..0)), r.blocks)
        assertEquals("ab", r.text)
    }

    @Test fun aNewLineTakesTheKindOfTheLineItCameFrom() {
        val b = listBody("a\nb\nc", listOf(blk(BULLET, 0, 0), blk(NUMBER, 2, 2)))
        val r = edit(b, "a\nXb\nc", 3)
        assertEquals(listOf(Block(BULLET, 0..0), Block(NUMBER, 2..2)), r.blocks)
        assertEquals("a\nXb\nc", r.text)
    }

    @Test fun pastingOverASelectionKeepsTheKindOfTheLineItLandsOn() {
        // A REPLACEMENT, not an insertion: `removed > 0 && n > 0` is its own branch of the
        // remap, and it is the one a paste over a selection and an IME autocorrect both take.
        // Select "a\nb" in a body whose first line is a bullet and paste "X\nY" over it: both
        // pasted lines land where line 0 was, so both are bullets. Mapped back to the END of the
        // replaced segment instead of its start, the list disappears under the paste in silence.
        val b = listBody("a\nb\nc", listOf(blk(BULLET, 0, 0)))
        val r = remapAfterEdit(b, "X\nY\nc", sp(0, 3), cursor(3), null)
        assertEquals("X\nY\nc", r.text)
        assertEquals(listOf(Block(BULLET, 0..1)), r.blocks)
    }

    @Test fun enterOnAnEmptyItemLeavesTheList() {
        // Gmail's rule. Gmail also removes the blank line; remapAfterEdit may not change the
        // text, so ours stays — assumed debt, already arbitrated.
        val b = listBody("a\n", listOf(blk(BULLET, 0, 1)))
        val r = edit(b, "a\n\n", 3)
        assertEquals(listOf(Block(BULLET, 0..0)), r.blocks)
        assertEquals("a\n\n", r.text)
        // …only for a bare Enter: pasting "\nx" on the same empty item stays in the list
        assertEquals(listOf(Block(BULLET, 0..2)), edit(b, "a\n\nx", 4).blocks)
    }

    // --- toHtml with blocks ------------------------------------------------------------------------

    @Test fun aListWritesUlOrOlAndNoBrBetweenItsItems() {
        assertEquals("<ul><li>a</li><li>b</li></ul>", toHtml(listBody("a\nb", listOf(blk(BULLET, 0, 1)))))
        assertEquals("<ol><li>a</li><li>b</li></ol>", toHtml(listBody("a\nb", listOf(blk(NUMBER, 0, 1)))))
    }

    @Test fun theBrSitsAroundTheListNeverInsideIt() {
        assertEquals(
            "<ul><li>a</li><li>b</li></ul><br>c",
            toHtml(listBody("a\nb\nc", listOf(blk(BULLET, 0, 1)))),
        )
        assertEquals(
            "a<br><ol><li>b</li></ol><br>c",
            toHtml(listBody("a\nb\nc", listOf(blk(NUMBER, 1, 1)))),
        )
        assertEquals(
            "<ul><li>a</li></ul><br><ol><li>b</li></ol>",
            toHtml(listBody("a\nb", listOf(blk(BULLET, 0, 0), blk(NUMBER, 1, 1)))),
        )
    }

    @Test fun inlineStylesAreWrittenInsideTheItem() {
        assertEquals(
            "<ul><li><b>a</b>b</li><li>c</li></ul>",
            toHtml(listBody("ab\nc", listOf(blk(BULLET, 0, 1)), BOLD to listOf(sp(0, 1)))),
        )
        assertEquals(
            "<ul><li><b>a</b></li><li><b>b</b></li></ul>",
            toHtml(listBody("a\nb", listOf(blk(BULLET, 0, 1)), BOLD to listOf(sp(0, 3)))),
        )
    }

    // --- fromHtml with blocks ----------------------------------------------------------------------

    @Test fun listsAreParsedBackIntoBlocks() {
        assertEquals(listBody("a\nb", listOf(blk(BULLET, 0, 1))), fromHtml("<ul><li>a</li><li>b</li></ul>"))
        assertEquals(
            listBody("a\nb\nc", listOf(blk(NUMBER, 1, 1))),
            fromHtml("a<br><ol><li>b</li></ol><br>c"),
        )
        assertEquals(listBody("", listOf(blk(BULLET, 0, 0))), fromHtml("<ul><li></li></ul>"))
    }

    @Test fun anythingUnusualAboutAListIsNull() {
        val rejected = listOf(
            "<li>x</li>",                               // an item outside any list
            "<ul><li>a</li>",                           // a list left open
            "<ul></ul>",                                // an empty list
            "<ul><li>a</li><ul><li>b</li></ul></ul>",   // a nested list
            "<ul><li>a<ul><li>b</li></ul></li></ul>",   // …nested inside an item
            "<ul><li>a<br>b</li></ul>",                 // a <br> inside an item
            "<ul></li></ul>",                           // a </li> with no <li>
            "<ul><li>a</li></li></ul>",                 // …one too many
            "<ul><li><b>a</li></ul>",                   // an inline tag still open at </li>
            "<ul><li>a</li></ol>",                      // the wrong closing tag
            """<ul class="x"><li>a</li></ul>""",          // an attribute
            "<ul>a<li>b</li></ul>",                     // text outside an item
            "<ul><li>a</li>b</ul>",                     // …after one
            "<b><ul><li>a</li></ul></b>",               // a list inside an inline tag
            "<ul><li>a<li>b</li></ul>",                 // an item left open
            "</ul>",                                    // a list closed but never opened
            // A list stands on lines of its own. Read otherwise, these three glue two lines
            // into one AND come back as a body this editor claims to reproduce faithfully —
            // which is the licence that lets the next save destroy the original draft.
            "a<ul><li>b</li></ul>",                     // text running straight into a list
            "<ul><li>a</li></ul>x",                     // …and straight out of one
            "<ul><li>a</li></ul><ol><li>b</li></ol>",   // two lists with no line between them
        )
        for (h in rejected) assertNull("should reject: $h", fromHtml(h))
    }

    // --- toPlainText -------------------------------------------------------------------------------

    @Test fun plainTextMarksListLinesAndRenumbersEveryBlock() {
        val b = listBody("a\nb\nc\nd\ne", listOf(blk(BULLET, 0, 1), blk(NUMBER, 2, 3)))
        assertEquals("- a\n- b\n1. c\n2. d\ne", toPlainText(b))
        assertEquals("the marker is written by toPlainText and NOWHERE else", "a\nb\nc\nd\ne", b.text)
        // a second numbered block starts again at 1
        val two = listBody("a\nb\nc", listOf(blk(NUMBER, 0, 0), blk(NUMBER, 2, 2)))
        assertEquals("1. a\nb\n1. c", toPlainText(two))
    }
}
