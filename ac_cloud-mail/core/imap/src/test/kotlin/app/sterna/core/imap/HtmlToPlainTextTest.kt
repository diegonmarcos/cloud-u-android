package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlToPlainTextTest {
    @Test fun `breaks, paragraphs and entities`() =
        assertEquals("a & b\nc\n\nd", htmlToPlainText("<p>a &amp; b<br>c</p><p></p>d"))

    @Test fun `a blockquote's lines are quoted`() =
        assertEquals("On Mon, Ana wrote:\n> one\n> two", htmlToPlainText("<div>On Mon, Ana wrote:</div><blockquote type=\"cite\">one<br>two</blockquote>"))

    @Test fun `script and style content never reach the text`() =
        assertEquals("ok", htmlToPlainText("<style>p{color:red}</style><script>alert(1)</script>ok"))
}
