package app.sterna.core.data.text

import app.sterna.core.data.text.BlockKind.BULLET
import app.sterna.core.data.text.BlockKind.NUMBER
import app.sterna.core.data.text.Inline.BOLD
import app.sterna.core.data.text.Inline.ITALIC
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The link in a rich body (#131): the URL guard, the four branches of [setLink], the non-sticky
 */
class RichBodyLinkTest {

    private fun sp(s: Int, e: Int) = Span(s, e)
    private fun cursor(c: Int) = Span(c, c)
    private fun body(text: String, vararg ranges: Pair<Inline, List<Span>>) = RichBody.of(text, mapOf(*ranges))
    private fun linked(text: String, vararg links: Link) = RichBody(text, emptyMap(), links = links.toList())
    private fun link(s: Int, e: Int, url: String) = Link(sp(s, e), url)

    // --- normalizeLinkUrl --------------------------------------------------------------------

    @Test fun `the three schemes are accepted, whatever their case, and lowercased`() {
        assertEquals("http://example.org/a", normalizeLinkUrl("http://example.org/a"))
        assertEquals("https://example.org/a", normalizeLinkUrl("https://example.org/a"))
        assertEquals("mailto:iris@example.org", normalizeLinkUrl("mailto:iris@example.org"))
        assertEquals("http://example.org", normalizeLinkUrl("HTTP://example.org"))
        assertEquals("https://example.org", normalizeLinkUrl("HttPS://example.org"))
        assertEquals("mailto:Iris@Example.org", normalizeLinkUrl("MAILTO:Iris@Example.org"))
    }

    @Test fun `every other scheme is refused`() {
        for (raw in listOf(
            "javascript:alert(1)", "JavaScript:alert(1)", "data:text/html,<b>x</b>", "ftp://example.org",
            "file:///etc/passwd", "vbscript:msgbox", "content://x", "intent://x", "tel:+33123",
        )) {
            assertNull("$raw must not be linkable", normalizeLinkUrl(raw))
        }
    }

    @Test fun `nothing at all is refused, and the surrounding spaces are cut`() {
        assertNull(normalizeLinkUrl(""))
        assertNull(normalizeLinkUrl("   "))
        assertNull(normalizeLinkUrl("\n\t "))
        assertEquals("https://example.org", normalizeLinkUrl("  example.org  "))
        assertEquals("http://example.org", normalizeLinkUrl("\thttp://example.org\n"))
    }

    @Test fun `a URL with no scheme becomes https`() {
        assertEquals("https://example.org", normalizeLinkUrl("example.org"))
        assertEquals("https://example.org/a/b?c=d#e", normalizeLinkUrl("example.org/a/b?c=d#e"))
        assertEquals("https://www.example.org", normalizeLinkUrl("www.example.org"))
        // Stated debt: a bare host:port reads as a scheme, and the scheme is not one of ours.
        assertNull("the host:port debt is deliberate, not an accident", normalizeLinkUrl("example.org:8080"))
    }

    @Test fun `normalising twice changes nothing — the html round trip rests on it`() {
        for (raw in listOf(
            "example.org", "HTTP://Example.ORG/A", "mailto:iris@example.org", "  example.org/x  ",
            "https://example.org/a?q=a&b=c", "example.org/#x",
        )) {
            val once = normalizeLinkUrl(raw)!!
            assertEquals("normalizeLinkUrl is not idempotent on `$raw`", once, normalizeLinkUrl(once))
        }
    }

    // --- normalisation of the list -------------------------------------------------------------

    @Test fun `links are clamped, sorted, and the empty ones dropped`() {
        val b = RichBody(
            "abcdefghij", emptyMap(),
            links = listOf(link(6, 9, "https://b"), link(3, 3, "https://empty"), link(-2, 2, "https://a"), link(9, 20, "https://c")),
        )
        assertEquals(
            listOf(link(0, 2, "https://a"), link(6, 9, "https://b"), link(9, 10, "https://c")),
            b.links,
        )
    }

    @Test fun `two contiguous links of the same URL stay two links`() {
        val b = linked("abcd", link(0, 2, "https://x"), link(2, 4, "https://x"))
        assertEquals(
            "adjacent links must NEVER merge: they are two anchors and the html says so",
            listOf(link(0, 2, "https://x"), link(2, 4, "https://x")),
            b.links,
        )
    }

    @Test fun `an overlapping link is dropped, the first one wins`() {
        val b = linked("abcdefghij", link(0, 5, "https://a"), link(3, 8, "https://b"), link(8, 10, "https://c"))
        assertEquals(listOf(link(0, 5, "https://a"), link(8, 10, "https://c")), b.links)
    }

    @Test fun `a body carrying only a link is not plain`() {
        val b = linked("abcd", link(0, 4, "https://x"))
        assertFalse("a link-only body must store an html part on save", b.isPlain)
        assertTrue(RichBody.plain("abcd").isPlain)
        assertEquals("""<a href="https://x">abcd</a>""", draftHtmlToSave(b))
    }

    @Test fun `equality and hashCode take the links in`() {
        val a = linked("abcd", link(0, 2, "https://x"))
        val b = linked("abcd", link(0, 2, "https://x"))
        val c = linked("abcd", link(0, 2, "https://y"))
        val d = linked("abcd", link(0, 3, "https://x"))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue("a different URL is a different body", a != c)
        assertTrue("a different span is a different body", a != d)
        assertTrue("a link is not nothing", a != RichBody.plain("abcd"))
        assertTrue("the links must show in toString", a.toString().contains("https://x"))
    }

    // --- linkAt ----------------------------------------------------------------------------------

    private val oneLink = linked("hello world", link(6, 11, "https://x"))

    @Test fun `a cursor is in a link strictly between its bounds`() {
        assertNull("at the start the cursor is OUTSIDE — typing there writes plain text", linkAt(oneLink, cursor(6)))
        assertNull("at the end too — the link is not sticky", linkAt(oneLink, cursor(11)))
        assertEquals(link(6, 11, "https://x"), linkAt(oneLink, cursor(7)))
        assertEquals(link(6, 11, "https://x"), linkAt(oneLink, cursor(10)))
        assertNull(linkAt(oneLink, cursor(0)))
    }

    @Test fun `a selection is in a link only when the link covers all of it`() {
        assertEquals(link(6, 11, "https://x"), linkAt(oneLink, sp(6, 11)))
        assertEquals(link(6, 11, "https://x"), linkAt(oneLink, sp(7, 9)))
        assertNull("a selection sticking out is not inside", linkAt(oneLink, sp(5, 9)))
        assertNull("a selection sticking out at the start is not inside", linkAt(oneLink, sp(5, 11)))
        assertNull(linkAt(oneLink, sp(0, 5)))
    }

    // --- setLink ---------------------------------------------------------------------------------

    @Test fun `a selection takes the link, and any link it overlaps goes`() {
        val b = body("hello world", BOLD to listOf(sp(0, 5)))
        val out = setLink(b, sp(6, 11), "https://x", text = "IGNORED")
        assertEquals(
            RichBody("hello world", mapOf(BOLD to listOf(sp(0, 5))), links = listOf(link(6, 11, "https://x"))),
            out,
        )
        val over = linked("hello world", link(4, 8, "https://old"))
        assertEquals(
            "a link the new selection overlaps is replaced, not left half over it",
            linked("hello world", link(6, 11, "https://x")),
            setLink(over, sp(6, 11), "https://x"),
        )
    }

    @Test fun `a selection INSIDE a link takes the link over just those characters`() {
        // The order of the branches, and the only witness of it: with the cursor branch first,
        // this selection would merely re-address the whole link instead of narrowing it.
        assertEquals(
            linked("hello world", link(7, 9, "https://y")),
            setLink(oneLink, sp(7, 9), "https://y"),
        )
    }

    @Test fun `a cursor inside a link only changes its URL`() {
        assertEquals(
            linked("hello world", link(6, 11, "https://new")),
            setLink(oneLink, cursor(8), "https://new", text = "IGNORED"),
        )
    }

    @Test fun `a cursor outside every link inserts the text and links it`() {
        val b = body("hello ", BOLD to listOf(sp(0, 5)))
        val out = setLink(b, cursor(6), "https://x", text = "site")
        assertEquals(
            RichBody("hello site", mapOf(BOLD to listOf(sp(0, 5))), links = listOf(link(6, 10, "https://x"))),
            out,
        )
        assertEquals("hello site", out.text)
    }

    @Test fun `an insertion in the middle of a style does not take that style with it`() {
        val b = body("abcd", BOLD to listOf(sp(0, 4)))
        val out = setLink(b, cursor(2), "https://x", text = "XY")
        assertEquals(
            "the inserted link text is roman: the caret's styles are not armed for it",
            RichBody("abXYcd", mapOf(BOLD to listOf(sp(0, 2), sp(4, 6))), links = listOf(link(2, 4, "https://x"))),
            out,
        )
    }

    @Test fun `a cursor with no text to insert changes nothing`() {
        val b = body("hello", BOLD to listOf(sp(0, 5)))
        assertEquals(b, setLink(b, cursor(2), "https://x", text = null))
        assertEquals(b, setLink(b, cursor(2), "https://x", text = ""))
        assertEquals(b, setLink(b, cursor(0), "https://x"))
    }

    // --- removeLink ------------------------------------------------------------------------------

    @Test fun `removing takes the link the cursor is in, and only it`() {
        val two = linked("hello world", link(0, 5, "https://a"), link(6, 11, "https://b"))
        assertEquals(linked("hello world", link(0, 5, "https://a")), removeLink(two, cursor(8)))
        assertEquals("a cursor in no link removes nothing", two, removeLink(two, cursor(5)))
        assertEquals(two, removeLink(two, cursor(6)))
    }

    @Test fun `removing over a selection takes every link it touches, and the text stays`() {
        val two = linked("hello world", link(0, 5, "https://a"), link(6, 11, "https://b"))
        val out = removeLink(two, sp(4, 7))
        assertEquals(RichBody.plain("hello world"), out)
        assertEquals("hello world", out.text)
        assertEquals(
            "a selection that touches one link leaves the other",
            linked("hello world", link(6, 11, "https://b")),
            removeLink(two, sp(0, 2)),
        )
    }

    @Test fun `removing a link leaves the styling alone`() {
        val b = RichBody("hello world", mapOf(BOLD to listOf(sp(0, 11))), links = listOf(link(6, 11, "https://b")))
        assertEquals(body("hello world", BOLD to listOf(sp(0, 11))), removeLink(b, sp(6, 11)))
    }

    // --- remapAfterEdit: a link is NOT sticky -----------------------------------------------------

    private fun edit(old: RichBody, newText: String, cursorBefore: Int, cursorAfter: Int) =
        remapAfterEdit(old, newText, cursor(cursorBefore), cursor(cursorAfter), null)

    @Test fun `typing just after a link stays outside it`() {
        // "hello world" with the link on "world" [6,11): typing X at 11.
        assertEquals(
            linked("hello worldX", link(6, 11, "https://x")),
            edit(oneLink, "hello worldX", 11, 12),
        )
    }

    @Test fun `typing just before a link stays outside it, and the link shifts`() {
        assertEquals(
            linked("hello Xworld", link(7, 12, "https://x")),
            edit(oneLink, "hello Xworld", 6, 7),
        )
    }

    @Test fun `typing inside a link extends it`() {
        assertEquals(
            linked("hello woXrld", link(6, 12, "https://x")),
            edit(oneLink, "hello woXrld", 8, 9),
        )
    }

    @Test fun `a partial delete trims the link, a full delete removes it`() {
        assertEquals(
            "the tail of the link went with the letters",
            linked("hello wor", link(6, 9, "https://x")),
            edit(oneLink, "hello wor", 11, 9),
        )
        assertEquals(
            "the head of the link went, and the rest shifted",
            linked("hello rld", link(6, 9, "https://x")),
            edit(oneLink, "hello rld", 9, 6),
        )
        val gone = edit(oneLink, "hello ", 11, 6)
        assertEquals(RichBody.plain("hello "), gone)
        assertTrue("a link with no character left must disappear", gone.links.isEmpty())
    }

    @Test fun `a link before an edit is left where it is`() {
        assertEquals(
            linked("hello worldXY", link(6, 11, "https://x")),
            edit(oneLink, "hello worldXY", 11, 13),
        )
        val early = linked("hello world", link(0, 5, "https://a"))
        assertEquals(linked("hello worldZ", link(0, 5, "https://a")), edit(early, "hello worldZ", 11, 12))
    }

    @Test fun `the style armed at the caret never arms a link`() {
        val out = remapAfterEdit(oneLink, "hello worldX", cursor(11), cursor(12), setOf(BOLD))
        assertEquals(
            RichBody("hello worldX", mapOf(BOLD to listOf(sp(11, 12))), links = listOf(link(6, 11, "https://x"))),
            out,
        )
    }

    // --- html ------------------------------------------------------------------------------------

    @Test fun `the anchor is the outermost tag`() {
        val b = RichBody("un mot fort", mapOf(BOLD to listOf(sp(7, 11))), links = listOf(link(7, 11, "https://x")))
        assertEquals("""un mot <a href="https://x"><b>fort</b></a>""", toHtml(b))
        val wider = RichBody("abcdef", mapOf(BOLD to listOf(sp(0, 6))), links = listOf(link(2, 4, "https://x")))
        assertEquals("""<b>ab</b><a href="https://x"><b>cd</b></a><b>ef</b>""", toHtml(wider))
    }

    @Test fun `two contiguous links of one URL are two anchors`() {
        val b = linked("abcd", link(0, 2, "https://x"), link(2, 4, "https://x"))
        assertEquals("""<a href="https://x">ab</a><a href="https://x">cd</a>""", toHtml(b))
    }

    @Test fun `the URL is escaped in the attribute`() {
        val b = linked("x", link(0, 1, "https://e.org/?a=1&b=2<3>4\"q\"'s' z"))
        assertEquals(
            """<a href="https://e.org/?a=1&amp;b=2&lt;3&gt;4&quot;q&quot;'s' z">x</a>""",
            toHtml(b),
        )
    }

    @Test fun `a line break in the URL leaves as an entity, never raw in the attribute`() {
        // THE OUTPUT, not the round trip: OUR parser reads a raw newline inside `href="…"`
        // without blinking, so the round trip cannot see this at all — it is the RECIPIENT's client
        // that is handed an attribute broken across two lines, and what it makes of it is its own
        // business. The escaping is therefore pinned on the text that leaves.
        val b = linked("saut", link(0, 4, "https://e.org/a\nb\rc"))
        val html = toHtml(b)
        assertEquals("""<a href="https://e.org/a&#10;b&#13;c">saut</a>""", html)
        assertFalse("a raw newline may not reach the attribute: $html", html.contains('\n'))
        assertFalse("nor a raw carriage return: $html", html.contains('\r'))
    }

    @Test fun `an anchor is read back, with its entities decoded and its URL normalised`() {
        assertEquals(
            linked("fort", link(0, 4, "https://x/?a=1&b=2")),
            fromHtml("""<a href="https://x/?a=1&amp;b=2">fort</a>"""),
        )
        assertEquals(
            "single quotes around the value are read too",
            linked("fort", link(0, 4, "https://x")),
            fromHtml("<a href='https://x'>fort</a>"),
        )
        assertEquals(
            "the guard runs on the way in as well, and it LOWERCASES the scheme it accepts",
            linked("fort", link(0, 4, "https://example.org")),
            fromHtml("""<a href="HTTPS://example.org">fort</a>"""),
        )
        assertNull(
            "⛔ …but it never PREFIXES one: `example.org` in an href is not ours to complete, and " +
                "a relative href completed is a link to somewhere else. See the refusal test below.",
            fromHtml("""<a href="example.org">fort</a>"""),
        )
        assertEquals(
            "an anchor over nothing is normalised away",
            RichBody.plain("ab"),
            fromHtml("""a<a href="https://x"></a>b"""),
        )
    }

    @Test fun `an anchor this editor cannot own refuses the whole body`() {
        for (html in listOf(
            "<a>t</a>",
            "<a href>t</a>",
            """<a href="">t</a>""",
            """<a href="javascript:alert(1)">t</a>""",
            """<a href="data:text/html,x">t</a>""",
            """<a href="x" onclick="y">t</a>""",
            """<a class="c" href="x">t</a>""",
            """<a href="x" target="_blank">t</a>""",
            """<a  href="x">t</a>""",
            // ABSOLUTE urls, on purpose. With a RELATIVE href the URL guard refuses first
            // and the guard each of these lines names is never reached: three of them sat here
            // proving nothing, and the mutation below went unmeasured.
            """<a href="https://x"><a href="https://y">t</a></a>""",
            // The measured one: without `open.any { it.url != null }` this PARSES, the second
            // link is dropped by normalisation, `draftHtmlIsLossy` answers false, and the server
            // original is destroyed for a copy that has lost an address.
            """<a href="https://x">a<a href="https://y">b</a>c</a>""",
            "t</a>",
            """<a href="https://x">t""",
            """<a href="https://x">t<b>u</a></b>""",
            // …and the same three with a relative href, which the URL guard refuses on its own.
            """<a href="x"><a href="y">t</a></a>""",
            """<a href="x">t""",
            """<a href="x">t<b>u</a></b>""",
            """<a href='x">t</a>""",
        )) {
            assertNull("should reject: $html", fromHtml(html))
        }
    }

    @Test fun `a relative href is refused — the parser never invents a scheme`() {
        // THE DESTROY LICENCE. `draftHtmlIsLossy` is what lets a re-save EXPUNGE the server
        // original. Before the links existed every `<a>` was refused, so a foreign draft carrying
        for (html in listOf(
            """<a href="x">t</a>""",
            """<a href="#top">t</a>""",
            """<a href="/prix">t</a>""",
            """<a href="//exemple.org">t</a>""",
            """<a href="exemple.org/prix">t</a>""",
            """<a href="?page=2">t</a>""",
        )) {
            assertNull("should reject: $html", fromHtml(html))
            assertTrue(
                "…and the draft must stay LOSSY, or its original is expunged for a copy whose " +
                    "link is not the same address: $html",
                draftHtmlIsLossy(html),
            )
        }
    }

    @Test fun `the incoming border takes the three schemes and prefixes nothing`() {
        assertEquals("https://example.org/a", linkUrlFromHref("https://example.org/a"))
        assertEquals("http://example.org", linkUrlFromHref("HTTP://example.org"))
        assertEquals("mailto:iris@example.org", linkUrlFromHref("MAILTO:iris@example.org"))
        for (raw in listOf(
            "", "   ", "x", "#top", "/prix", "//example.org", "example.org", "?page=2",
            "javascript:alert(1)", "data:text/html,x", "ftp://example.org",
        )) {
            assertNull("`$raw` must never become a link on the way IN", linkUrlFromHref(raw))
        }
        // …while the DIALOG's border still does what the user means by typing a bare host.
        assertEquals(
            "the two borders are NOT the same function: only the dialog may add a scheme",
            "https://example.org", normalizeLinkUrl("example.org"),
        )
    }

    @Test fun `the html round trip carries the links`() {
        val bodies = listOf(
            linked("un lien", link(0, 7, "https://example.org/a")),
            RichBody("un lien fort", mapOf(BOLD to listOf(sp(3, 7))), links = listOf(link(3, 7, "https://x"))),
            RichBody("abcdefghij", mapOf(BOLD to listOf(sp(1, 9))), links = listOf(link(3, 6, "https://x"))),
            linked("abcd", link(0, 2, "https://x"), link(2, 4, "https://x")),
            linked("x", link(0, 1, "https://e.org/?a=1&b=2<3>4\"q\"'s' z")),
            // The two line breaks: unescaped in the attribute they break the tag open, and the
            // body comes back as markup — or not at all. Kept INSIDE the URL, since the guard
            // trims its ends.
            linked("saut", link(0, 4, "https://e.org/a\nb\rc")),
            linked("é€😀x", link(0, 4, "https://e.org/é")),
            RichBody(
                "a\nb\nc",
                mapOf(ITALIC to listOf(sp(0, 5))),
                links = listOf(link(0, 3, "mailto:iris@example.org")),
            ),
        )
        for (x in bodies) {
            val html = toHtml(x)
            val back = fromHtml(html)
            assertNotNull(
                "⛔ the STRICT incoming border must accept everything `toHtml` writes: it only " +
                    "ever writes URLs that already carry a scheme. Refused: $html",
                back,
            )
            assertEquals("round trip of $html", x, back)
        }
    }

    // --- plain text --------------------------------------------------------------------------------

    @Test fun `text, URL, and the two links of one body`() {
        assertEquals("no link, no change", "hello world", toPlainText(body("hello world", BOLD to listOf(sp(0, 5)))))
        assertEquals(
            "site <https://example.org>",
            toPlainText(linked("site", link(0, 4, "https://example.org"))),
        )
        assertEquals(
            "a link whose text IS its URL is written once",
            "https://example.org",
            toPlainText(linked("https://example.org", link(0, 19, "https://example.org"))),
        )
        assertEquals(
            "a <https://a.example> and b <https://b.example> end",
            toPlainText(linked("a and b end", link(0, 1, "https://a.example"), link(6, 7, "https://b.example"))),
        )
    }

    // --- where the links and the lists cross (#131) ------------------------------------------
    //
    // Neither suite could see any of this: the lists landed in `main` and the links on a branch
    // beside it, each growing the model by the same end. Everything below is what only the two
    // together can produce, and every expected value is written out by hand.

    private fun blk(kind: BlockKind, from: Int, to: Int) = Block(kind, from..to)
    private fun listed(text: String, blocks: List<Block>, vararg links: Link) =
        RichBody(text, emptyMap(), blocks, links.toList())

    @Test fun `a link inside a bullet is written inside its item`() {
        val one = listed("le site", listOf(blk(BULLET, 0, 0)), link(3, 7, "https://x"))
        assertEquals("""<ul><li>le <a href="https://x">site</a></li></ul>""", toHtml(one))
        assertEquals("the anchor is inside the item, never around it", one, fromHtml(toHtml(one)))

        val two = listed("un\ndeux", listOf(blk(BULLET, 0, 1)), link(3, 7, "https://x"))
        assertEquals("""<ul><li>un</li><li><a href="https://x">deux</a></li></ul>""", toHtml(two))
        assertEquals(two, fromHtml(toHtml(two)))
    }

    @Test fun `a link straddling two items is cut at the break, and the body still writes back`() {
        val across = listed("un\ndeux", listOf(blk(BULLET, 0, 1)), link(1, 5, "https://x"))
        assertEquals(
            "⛔ `</li><li>` carries no character, so an anchor may not stay open across it: the " +
                "link normalises into TWO links, one per item",
            listOf(link(1, 2, "https://x"), link(3, 5, "https://x")),
            across.links,
        )
        assertEquals(
            """<ul><li>u<a href="https://x">n</a></li><li><a href="https://x">de</a>ux</li></ul>""",
            toHtml(across),
        )
        assertEquals("…and what comes back is what went out", across, fromHtml(toHtml(across)))
    }

    @Test fun `a link and a bold on the same characters nest li, then a, then b`() {
        val b = RichBody(
            "fort",
            mapOf(BOLD to listOf(sp(0, 4))),
            listOf(blk(BULLET, 0, 0)),
            listOf(link(0, 4, "https://x")),
        )
        assertEquals("""<ul><li><a href="https://x"><b>fort</b></a></li></ul>""", toHtml(b))
        assertEquals(b, fromHtml(toHtml(b)))
    }

    @Test fun `the plain text carries the marker and the URL, each where it belongs`() {
        assertEquals(
            "- le site <https://x>\n- autre",
            toPlainText(listed("le site\nautre", listOf(blk(BULLET, 0, 1)), link(3, 7, "https://x"))),
        )
        assertEquals(
            "⛔ the address goes where the LINK ends, not at the end of the line",
            "1. le site <https://x> et la suite",
            toPlainText(listed("le site et la suite", listOf(blk(NUMBER, 0, 0)), link(3, 7, "https://x"))),
        )
    }

    @Test fun `typing inside a linked item moves the link and leaves the list alone`() {
        val two = listed("un\ndeux", listOf(blk(BULLET, 0, 1)), link(3, 7, "https://x"))
        assertEquals(
            listed("un\ndeXux", listOf(blk(BULLET, 0, 1)), link(3, 8, "https://x")),
            remapAfterEdit(two, "un\ndeXux", cursor(5), cursor(6), null),
        )
    }

    @Test fun `an Enter inside a linked item splits the item AND the link`() {
        val one = listed("le site", listOf(blk(BULLET, 0, 0)), link(3, 7, "https://x"))
        assertEquals(
            "the item became two, so the anchor had to become two as well",
            listed("le si\nte", listOf(blk(BULLET, 0, 1)), link(3, 5, "https://x"), link(6, 8, "https://x")),
            remapAfterEdit(one, "le si\nte", cursor(5), cursor(6), null),
        )
    }

    @Test fun `inserting a linked label inside a list carries the blocks the remap answered`() {
        val two = listed("un\ndeux", listOf(blk(BULLET, 0, 1)))
        // A pasted label with a break in it makes a THIRD item, and only [remapAfterEdit] knows
        // that: the body's OWN blocks still say two, and handing those back drops an item.
        assertEquals(
            RichBody(
                "unX\nY\ndeux",
                emptyMap(),
                listOf(blk(BULLET, 0, 2)),
                listOf(link(2, 3, "https://x"), link(4, 5, "https://x")),
            ),
            setLink(two, cursor(2), "https://x", text = "X\nY"),
        )
    }

    @Test fun `a selection over two items answers the link both of them carry`() {
        val across = listed("un\ndeux", listOf(blk(BULLET, 0, 1)), link(0, 7, "https://x"))
        assertEquals(
            "the link is STORED in two pieces, one per item",
            listOf(link(0, 2, "https://x"), link(3, 7, "https://x")),
            across.links,
        )
        // …and asked about the whole selection the model must still say "this IS a link".
        // Answering null reopens the dialog in CREATE mode with an empty address and takes
        // "remove link" away, while `removeLink` (which overlaps) would have done it.
        assertEquals(link(0, 2, "https://x"), linkAt(across, sp(0, 7)))
        assertEquals(
            "the same answer for a selection stopping inside the second item",
            link(0, 2, "https://x"),
            linkAt(across, sp(1, 5)),
        )
    }

    @Test fun `two items under two addresses are not one link to edit`() {
        val two = listed(
            "un\ndeux",
            listOf(blk(BULLET, 0, 1)),
            link(0, 2, "https://x"),
            link(3, 7, "https://y"),
        )
        assertNull("two addresses selected together are not `a link` to re-address", linkAt(two, sp(0, 7)))
        assertEquals("each on its own is still itself", link(0, 2, "https://x"), linkAt(two, sp(0, 2)))
        assertEquals(link(3, 7, "https://y"), linkAt(two, sp(3, 7)))
        val holed = listed("un\ndeux", listOf(blk(BULLET, 0, 1)), link(0, 2, "https://x"))
        assertNull("a character carrying no link at all breaks the run", linkAt(holed, sp(0, 7)))
    }

    @Test fun `the parser refuses what neither a list nor an anchor can hold`() {
        for (html in listOf(
            // an `<a>` outside an `<li>`: inside a list every character and every tag is in an item
            """<ul><a href="https://x">t</a><li>u</li></ul>""",
            // a list opened inside an anchor — `open.isNotEmpty()`, which now sees anchors too
            """a<br><a href="https://x"><ul><li>b</li></ul></a>""",
            // an anchor still open at `</li>`
            """<ul><li><a href="https://x">t</li></ul>""",
            // …and one straddling two items, the same refusal seen from the other side
            """<ul><li><a href="https://x">a</li><li>b</a></li></ul>""",
        )) {
            assertNull("should reject: $html", fromHtml(html))
            assertTrue(
                "…and the draft must stay LOSSY, or its original is expunged for a copy: $html",
                draftHtmlIsLossy(html),
            )
        }
    }
}
