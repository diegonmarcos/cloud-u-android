package app.sterna.ui.message

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailBodyValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reader shows the html part whenever there is one; text-only mail is shown as html too. */
class ReaderHtmlFirstTest {
    private fun email(html: String?, text: String?): Email {
        val values = buildMap {
            if (html != null) put("h", EmailBodyValue(html))
            if (text != null) put("t", EmailBodyValue(text))
        }
        return Email(
            id = "m",
            htmlBody = if (html != null) listOf(EmailBodyPart(partId = "h", type = "text/html")) else emptyList(),
            textBody = if (text != null) listOf(EmailBodyPart(partId = "t", type = "text/plain")) else emptyList(),
            bodyValues = values,
        )
    }

    @Test fun `with both parts the html one is rendered`() {
        val body = readerBody(email("<p>Rich <b>words</b></p>", "plain words"), plainText = false, derivedNotice = "d", noContent = "n")
        assertTrue(body.richHtml)
        assertTrue(body.fragment, "<b>words</b>" in body.fragment)
        assertFalse(body.fragment, "plain words" in body.fragment)
    }

    @Test fun `the default reading mode is html`() {
        assertFalse(plainTextForBody(override = null, setting = null))
    }

    @Test fun `a text-only mail is escaped html with its web addresses linked`() {
        val body = readerBody(email(null, "see https://example.org/a?x=1&y=2, ok <b>"), plainText = false, derivedNotice = "d", noContent = "n")
        assertFalse(body.richHtml)
        assertTrue(body.fragment, "<a href=\"https://example.org/a?x=1&amp;y=2\">https://example.org/a?x=1&amp;y=2</a>," in body.fragment)
        assertTrue(body.fragment, "&lt;b&gt;" in body.fragment)
        assertFalse(body.fragment, "<b>" in body.fragment)
    }

    @Test fun `trailing punctuation is not part of the address`() {
        assertEquals("<a href=\"https://x.org\">https://x.org</a>.", linkifyEscaped("https://x.org."))
        assertEquals("no link here", linkifyEscaped("no link here"))
    }
}
