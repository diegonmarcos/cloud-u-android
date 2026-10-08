package app.sterna.ui.compose

import app.sterna.core.data.text.RichBody
import app.sterna.core.data.text.toPlainText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reply's quoted original on the wire: the original's sanitised HTML in a blockquote under the usual
 * header line, not the plain "> " lines the editor shows. The text alternative keeps the plain quote.
 */
class ReplyQuoteHtmlTest {

    private val attribution = "On Mon, 5 Oct 2026, Ana wrote:"
    private val originalHtml =
        "<table width=\"600\"><tr><td><h1>Offer</h1><p>See <a href=\"https://example.invalid/a\">the details</a></p>" +
            "<img src=\"cid:logo@x\"><script>alert(1)</script></td></tr></table>"
    private val plainBlock = "$attribution\n> Offer\n> See the details"
    private val quote = QuoteForHtml(plainBlock, buildQuoteHtml(attribution, originalHtml, "Offer\nSee the details"))

    private fun plain(text: String) = RichBody(text, emptyMap())

    @Test fun `the quote keeps the original's structure inside a blockquote under the header line`() {
        val html = buildQuoteHtml(attribution, originalHtml, "x")
        assertTrue(html.startsWith("<div>On Mon, 5 Oct 2026, Ana wrote:</div><blockquote type=\"cite\""))
        assertTrue("the table and the link survive", html.contains("<table width=\"600\">") && html.contains("href=\"https://example.invalid/a\""))
        assertTrue(html.endsWith("</blockquote>"))
        assertFalse("script is gone", html.contains("<script"))
        assertTrue("an uncarried inline picture is not a broken box", html.contains("[image]") && !html.contains("cid:"))
    }

    @Test fun `a text-only original is quoted escaped, line by line`() {
        val html = buildQuoteHtml(attribution, null, "a < b\nsecond")
        assertTrue(html.contains("a &lt; b<br>second"))
    }

    @Test fun `the sent html replaces the plain quote lines with the blockquote`() {
        val body = plain("Thanks Ana.\n\n$plainBlock")
        val html = htmlBodyWithSignature(body, "", "", false, quote)
        assertTrue(html.startsWith("Thanks Ana.<br><br><div>On Mon, 5 Oct 2026, Ana wrote:</div><blockquote"))
        assertFalse("no raw '>' quote lines are left in the html", html.contains("&gt; Offer"))
        assertTrue(html.contains("<h1>Offer</h1>"))
    }

    @Test fun `the plain-text alternative still carries the quote as quoted lines`() {
        val body = plain("Thanks Ana.\n\n$plainBlock")
        val text = toPlainText(body)
        assertTrue(text.contains("$attribution\n> Offer\n> See the details"))
    }

    @Test fun `an edited quote is the user's text and wins`() {
        val body = plain("Thanks Ana.\n\n$attribution\n> Offer\n> I changed this line")
        val html = htmlBodyWithSignature(body, "", "", false, quote)
        assertFalse(html.contains("<blockquote"))
        assertTrue(html.contains("I changed this line"))
    }

    @Test fun `no quote object changes nothing`() {
        val body = plain("hello\nworld")
        assertEquals("hello<br>world", htmlBodyWithSignature(body, "", "", false))
    }

    @Test fun `the suggested reply sits above the quote in the html too`() {
        val withSuggestion = withSuggestedReply("Gracias, lo reviso hoy.", "\n\n$plainBlock")
        val html = htmlBodyWithSignature(plain(withSuggestion), "", "", false, quote)
        assertTrue(html.startsWith("Gracias, lo reviso hoy.<br><br><div>On Mon"))
    }

    @Test fun `with an html signature both substitutions apply and do not collide`() {
        val signature = "-- \nAna"
        val body = plain("Thanks.\n\n$plainBlock\n\n$signature")
        val html = htmlBodyWithSignature(body, "Ana", "<b>Ana</b>", true, quote)
        assertTrue(html.contains("<blockquote") && html.contains("<b>Ana</b>"))
    }

    @Test fun `html is what every outgoing message is made of`() {
        // The send path always builds an html alternative next to the text one, for a new message, a
        // reply and a forward alike: there is no plain-only mode to be in.
        val vm = java.io.File(generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .first { java.io.File(it, "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt").isFile },
            "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt").readText()
        assertTrue(vm.contains("private suspend fun bodiesForSend(userBody: RichBody, identity: StoredIdentity?): Pair<String, String?>"))
        assertTrue("both alternatives are made from the one body", vm.contains("toPlainText(userBody)") && vm.contains("htmlBodyWithSignature("))
    }
}
