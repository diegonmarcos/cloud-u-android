package app.sterna.push

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailBodyValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A reply typed in the notification shade leaves as HTML, with the original quoted - never text only. */
class QuickReplyBodiesTest {

    private val attribution = "On Mon 5 Oct, Alice wrote:"

    private fun original(html: String? = null, text: String? = null): Email {
        val textParts = if (text != null) listOf(EmailBodyPart(partId = "t", type = "text/plain")) else emptyList()
        val htmlParts = if (html != null) listOf(EmailBodyPart(partId = "h", type = "text/html")) else emptyList()
        val values = buildMap {
            if (text != null) put("t", EmailBodyValue(text))
            if (html != null) put("h", EmailBodyValue(html))
        }
        return Email(
            id = "m1", subject = "Sprint", from = listOf(EmailAddress(name = "Alice", email = "a@x.org")),
            textBody = textParts, htmlBody = htmlParts, bodyValues = values,
        )
    }

    @Test fun `the typed text is html even with nothing to quote`() {
        val b = quickReplyBodies("on my way", null, attribution)
        assertEquals("on my way", b.text)
        assertEquals("<div>on my way</div>", b.html)
    }

    @Test fun `a headers-only original is not quoted`() {
        val b = quickReplyBodies("ok", Email(id = "m1", subject = "s", preview = "preview only"), attribution)
        assertEquals("<div>ok</div>", b.html)
        assertFalse(b.text.contains("preview"))
    }

    @Test fun `an html original is quoted as its own html inside a blockquote under the attribution`() {
        val b = quickReplyBodies("Thanks", original(html = "<p>See <b>attached</b></p><script>x()</script>"), attribution)
        assertTrue(b.html, b.html.startsWith("<div>Thanks</div><br><div>$attribution</div><blockquote type=\"cite\""))
        assertTrue(b.html, b.html.contains("<b>attached</b>"))
        assertFalse("sanitised", b.html.contains("<script"))
    }

    @Test fun `a text original is quoted as escaped html with line breaks`() {
        val b = quickReplyBodies("Thanks", original(text = "a < b\nsecond"), attribution)
        assertTrue(b.html, b.html.contains("<blockquote type=\"cite\""))
        assertTrue(b.html, b.html.contains("a &lt; b<br>second"))
    }

    @Test fun `the text alternative carries the reply and the quote lines`() {
        val b = quickReplyBodies("Thanks", original(text = "first\nsecond"), attribution)
        assertEquals("Thanks\n\n$attribution\n> first\n> second", b.text)
    }
}
