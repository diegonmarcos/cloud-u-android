package app.sterna.ui.compose

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailBodyValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quoting an HTML original used to strip its `<blockquote>` nesting, so every past round of an
 */
class QuoteDepthTest {

    private fun htmlMail(html: String) = Email(
        id = "m",
        htmlBody = listOf(EmailBodyPart(partId = "h", type = "text/html")),
        bodyValues = mapOf("h" to EmailBodyValue(html)),
    )

    private fun textMail(text: String) = Email(
        id = "m",
        textBody = listOf(EmailBodyPart(partId = "t", type = "text/plain")),
        bodyValues = mapOf("t" to EmailBodyValue(text)),
    )

    /** What the composer actually writes: the original quoted one level deeper. */
    private fun quoteOf(o: Email): String =
        quotedOriginalText(o).lineSequence().joinToString("\n") { deepenQuote(it) }

    @Test fun textOutsideAnyBlockquoteHasNoMarker() {
        assertEquals("Sounds good.", htmlToQuotedText("<div>Sounds good.</div>"))
    }

    @Test fun oneBlockquoteBecomesOneLevel() {
        val html = "<div>Sure.</div><blockquote><div>Original line</div></blockquote>"
        assertEquals("Sure.\n> Original line", htmlToQuotedText(html))
    }

    @Test fun nestedBlockquotesDeepen() {
        val html = """
            <div>Third round</div>
            <blockquote><div>Second round</div>
              <blockquote><div>First round</div></blockquote>
            </blockquote>
        """.trimIndent()
        assertEquals("Third round\n> Second round\n>> First round", htmlToQuotedText(html))
    }

    @Test fun gmailAndAppleAttributesDoNotHideTheBlockquote() {
        val gmail = """<div dir="ltr">Yes</div><blockquote class="gmail_quote" style="margin:0 0 0 .8ex">
            <div>Older</div></blockquote>"""
        assertEquals("Yes\n> Older", htmlToQuotedText(gmail))
        val apple = """<div>Yes</div><blockquote type="cite"><div>Older</div></blockquote>"""
        assertEquals("Yes\n> Older", htmlToQuotedText(apple))
    }

    @Test fun everyLineOfAMultiLineQuoteIsMarked() {
        val html = "<blockquote><div>one</div><div>two</div><div>three</div></blockquote>"
        assertEquals("> one\n> two\n> three", htmlToQuotedText(html))
    }

    @Test fun textAfterAClosingBlockquoteComesBackToTheOuterLevel() {
        val html = "<div>a</div><blockquote><div>b</div></blockquote><div>c</div>"
        assertEquals("a\n> b\nc", htmlToQuotedText(html))
    }

    @Test fun anUnbalancedClosingTagDoesNotGoNegative() {
        assertEquals("a\nb", htmlToQuotedText("<div>a</div></blockquote><div>b</div>"))
    }

    @Test fun anUnclosedBlockquoteJustKeepsItsLevel() {
        assertEquals("a\n> b", htmlToQuotedText("<div>a</div><blockquote><div>b</div>"))
    }

    // --- The composed quote: one more level than the original carried ---

    @Test fun replyingToAnHtmlReplyReadsOneThenTwoMarkers() {
        val html = "<div>Second round</div><blockquote><div>First round</div></blockquote>"
        assertEquals("> Second round\n>> First round", quoteOf(htmlMail(html)))
    }

    @Test fun plainTextOriginalsStillDeepenWithoutDoubleCounting() {
        // A plain-text original already carries its own markers: they gain exactly one level.
        assertEquals("> Second\n>> First", quoteOf(textMail("Second\n> First")))
        assertEquals(">>> Older", quoteOf(textMail(">> Older")))
    }

    @Test fun aPlainFirstReplyGetsASingleMarker() {
        assertEquals("> Hello there", quoteOf(textMail("Hello there")))
    }

    @Test fun theSignatureIsStillCutFromAQuotedHtmlOriginal() {
        val html = "<div>Body</div><div>-- </div><div>Alice</div>"
        assertTrue("signature dropped", !quoteOf(htmlMail(html)).contains("Alice"))
        assertEquals("> Body", quoteOf(htmlMail(html)))
    }

    @Test fun aQuotedSignatureInsideTheHistoryIsLeftInPlace() {
        // The delimiter inside a blockquote is part of the quoted history (it comes out as
        // "> -- "), not the sender's own sign-off, so it must not truncate the quote.
        val html = "<div>Body</div><blockquote><div>-- </div><div>Bob</div></blockquote>"
        val quote = quoteOf(htmlMail(html))
        assertTrue("history kept: $quote", quote.contains("Bob"))
    }
}
