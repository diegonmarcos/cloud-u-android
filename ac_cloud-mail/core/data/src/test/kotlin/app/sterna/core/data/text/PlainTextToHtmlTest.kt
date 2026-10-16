package app.sterna.core.data.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Text becomes the HTML body of a message in ONE place: escaped, line breaks kept, one `<div>`. */
class PlainTextToHtmlTest {

    @Test fun `a line is a div`() {
        assertEquals("<div>on my way</div>", plainTextToHtml("on my way"))
    }

    @Test fun `line breaks of every kind become br`() {
        assertEquals("<div>a<br>b<br>c<br><br>d</div>", plainTextToHtml("a\nb\r\nc\r\rd".replace("\r\r", "\n\n")))
        assertEquals("<div>a<br>b</div>", plainTextToHtml("a\r\nb"))
        assertEquals("<div>a<br>b</div>", plainTextToHtml("a\rb"))
    }

    @Test fun `markup in the text is text, never markup`() {
        val html = plainTextToHtml("<script>alert(1)</script> & <b>x</b>")
        assertFalse(html, html.contains("<script"))
        assertFalse(html, html.contains("<b>"))
        assertEquals("<div>&lt;script&gt;alert(1)&lt;/script&gt; &amp; &lt;b&gt;x&lt;/b&gt;</div>", html)
    }

    @Test fun `empty text is an empty line and not an empty string`() {
        assertEquals("<div><br></div>", plainTextToHtml(""))
    }

    @Test fun `the html reads back as the same text`() {
        val text = "Hi Ana,\n\nsee you at 6 <ok> & bring snacks\nAlex"
        assertEquals(text, htmlToText(plainTextToHtml(text)))
    }

    @Test fun `result is always html`() {
        assertTrue(looksLikeHtml(plainTextToHtml("anything")))
        assertTrue(looksLikeHtml(plainTextToHtml("")))
    }
}
