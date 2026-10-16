package app.sterna.core.data.text

import app.sterna.core.jmap.model.EmailBodyPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The composer's body is HTML: what it becomes on the wire, what it opens on, how its blocks are laid out. */
class ComposeHtmlTest {

    // --- htmlForWire -----------------------------------------------------------------------------

    @Test fun `a picked picture goes out as its cid, the editor's attributes stay home`() {
        val wire = htmlForWire("""<div><img src="data:image/png;base64,AAAA" data-cid="img1@x" alt="a"></div>""")
        assertTrue(wire, "cid:img1@x" in wire)
        assertFalse(wire, "data:image" in wire)
        assertFalse(wire, "data-cid" in wire)
    }

    @Test fun `script and handlers never reach the wire`() {
        val wire = htmlForWire("""<p onclick="x()">hi</p><script>alert(1)</script><a href="javascript:x()">y</a>""")
        assertFalse(wire, "script" in wire.lowercase())
        assertFalse(wire, "onclick" in wire)
        assertFalse(wire, "javascript:" in wire)
        assertTrue(wire, "hi" in wire)
    }

    @Test fun `the editor's block markers are not sent`() {
        val wire = htmlForWire("""<div data-sterna="sig"><b>Alex</b></div>""")
        assertFalse(wire, "data-sterna" in wire)
        assertTrue(wire, "<b>Alex</b>" in wire)
    }

    @Test fun `formatting survives the trip`() {
        val wire = htmlForWire("""<h1>T</h1><ul><li>one</li></ul><blockquote>q</blockquote><span style="color:#d93025">r</span>""")
        for (want in listOf("<h1>T</h1>", "<li>one</li>", "<blockquote>q</blockquote>", "color:#d93025")) {
            assertTrue("$want in $wire", want in wire)
        }
    }

    @Test fun `the content ids a wire html references`() {
        assertEquals(
            setOf("a@b", "c@d"),
            cidsReferencedBy("""<img src="cid:a@b"><img src='cid:c@d'><img src="https://x/y.png">"""),
        )
    }

    @Test fun `an inline part nobody references is dropped, files and used pictures stay`() {
        val used = EmailBodyPart(partId = "1", name = "a.png", disposition = "inline", cid = "used@x")
        val orphan = EmailBodyPart(partId = "2", name = "b.png", disposition = "inline", cid = "orphan@x")
        val file = EmailBodyPart(partId = "3", name = "c.pdf", disposition = "attachment")
        val kept = withoutUnusedInlineParts(listOf(used, orphan, file), """<img src="cid:used@x">""")
        assertEquals(listOf(used, file), kept)
    }

    // --- the opening -----------------------------------------------------------------------------

    private val sig = editorBlock(BLOCK_SIGNATURE, "<br>-- <br>Alex")
    private val quote = editorBlock(BLOCK_QUOTE, "<div>On Mon, Ana wrote:</div><blockquote type=\"cite\">hi</blockquote>")

    @Test fun `a reply opens on an empty line, then the signature, then the quote`() {
        val html = composeOpeningHtml("", sig, quote, signatureBelowQuote = false)
        assertTrue(html, html.startsWith("<div><br></div>"))
        assertTrue(html, html.indexOf("data-sterna=\"sig\"") in 0 until html.indexOf("data-sterna=\"quote\""))
    }

    @Test fun `the setting puts the signature under the quote`() {
        val html = composeOpeningHtml("", sig, quote, signatureBelowQuote = true)
        assertTrue(html, html.indexOf("data-sterna=\"quote\"") in 0 until html.indexOf("data-sterna=\"sig\""))
    }

    @Test fun `typed text opens as an escaped first line`() {
        val html = composeOpeningHtml("a < b\nsecond", sig, "", false)
        assertTrue(html, html.startsWith("<div>a &lt; b<br>second</div>"))
    }

    @Test fun `a signature is a block, html as written or text with its line breaks`() {
        val fromHtml = signatureEditorBlock("<b>Alex</b>", "ignored", delimiter = true)
        assertTrue(fromHtml, fromHtml.startsWith("<div data-sterna=\"sig\">"))
        assertTrue(fromHtml, "-- " in fromHtml && "<b>Alex</b>" in fromHtml)
        val fromText = signatureEditorBlock("", "A\nB", delimiter = false)
        assertTrue(fromText, "A<br>B" in fromText)
        assertFalse(fromText, "-- " in fromText)
        assertEquals("", signatureEditorBlock("", "  ", delimiter = true))
    }

    @Test fun `a block is found with its nested divs`() {
        val html = "<div><br></div><div data-sterna=\"forward\"><div>From: A</div><div>body</div></div><div data-sterna=\"sig\">s</div>"
        assertEquals(
            "<div data-sterna=\"forward\"><div>From: A</div><div>body</div></div>",
            extractEditorBlock(html, BLOCK_FORWARD),
        )
        assertNull(extractEditorBlock(html, BLOCK_QUOTE))
    }

    // --- the body model --------------------------------------------------------------------------

    @Test fun `html is the model and text its projection`() {
        val body = RichBody.ofHtml("<p>Hello <b>you</b></p><ul><li>one</li></ul>")
        assertNotNull(body.html)
        assertTrue(body.text, body.text.startsWith("Hello you"))
        assertTrue(body.text, "- one" in body.text)
        assertFalse(body.isPlain)
    }

    @Test fun `a reopened body with no html is its text wrapped`() {
        val body = htmlBodyFrom(null) { "hello\nworld" }
        assertEquals("<div>hello<br>world</div>", body.html)
        assertEquals("hello\nworld", body.text)
    }

    @Test fun `a reopened body is sanitised on the way in`() {
        val body = htmlBodyFrom("<p>Hi</p><script>x()</script>") { "unused" }
        assertFalse(body.html.orEmpty(), "script" in body.html.orEmpty())
        assertEquals("Hi", body.text)
    }

    @Test fun `a draft saved from the editor stores html, never nothing`() {
        assertNotNull(draftHtmlToSave(RichBody.ofHtml("<div>x</div>")))
        assertNull(draftHtmlToSave(RichBody.plain("x")))
    }
}
