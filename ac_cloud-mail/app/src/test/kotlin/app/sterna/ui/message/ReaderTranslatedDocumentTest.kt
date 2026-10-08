package app.sterna.ui.message

import app.sterna.core.data.text.InPlaceHtmlTranslation
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailBodyValue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The translated fragment standing in for the message's own, EXECUTED through the real document
 * builder: the page keeps every fit rule, the layout and the links, and shows the translation or the
 * original - never both.
 */
class ReaderTranslatedDocumentTest {
    private val html = """<table width="600"><tr><td><a href="https://example.invalid/a">Open the offer</a></td></tr></table>"""
    private val email = Email(
        id = "1",
        htmlBody = listOf(EmailBodyPart(partId = "h", type = "text/html")),
        bodyValues = mapOf("h" to EmailBodyValue(value = html)),
    )
    private val light = EmailTheme("#ffffff", "#111111", "#0b5fff", false)
    private val dark = EmailTheme("#101418", "#e3e3e3", "#a8c7fa", true)

    private fun fragmentOf(theme: EmailTheme) = readerBody(email, false, "derived", "none").fragment

    private fun doc(theme: EmailTheme, translated: String?) =
        buildHtmlDocument(email, theme = theme, noContent = "none", translatedFragment = translated)

    @Test fun `the original shows when no translation is given`() {
        val page = doc(light, null)
        assertTrue(page.contains("Open the offer"))
    }

    @Test fun `the translation replaces the text and nothing else, in both themes`() {
        for (theme in listOf(light, dark)) {
            val translated = InPlaceHtmlTranslation.translate(fragmentOf(theme)) { it.uppercase() }.html
            val page = doc(theme, translated)
            assertTrue("translated text is on the page", page.contains("OPEN THE OFFER"))
            assertFalse("the original is not also on the page", page.contains("Open the offer"))
            assertTrue("the link survives", page.contains("href=\"https://example.invalid/a\""))
            assertTrue("the fixed-width table is still capped by the fit rules", page.contains(FIT_CSS.trim().lines().first().trim()))
            assertTrue(page.contains("width=\"600\""))
        }
    }

    @Test fun `the translation goes through the same pipeline as the original`() {
        // dark mode inverts the whole page for rich HTML: the translated page must be the inverted one too
        assertTrue(doc(dark, "<p>X</p>").contains("invert(1)"))
        assertFalse(doc(light, "<p>X</p>").contains("invert(1)"))
    }
}
