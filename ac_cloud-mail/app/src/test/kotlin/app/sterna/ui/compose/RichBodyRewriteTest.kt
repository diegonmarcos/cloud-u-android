package app.sterna.ui.compose

import app.sterna.core.data.text.Inline
import app.sterna.core.data.text.RichBody
import app.sterna.core.data.text.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Changing the sending identity rewrites the signature block of the body as TEXT
 */
class RichBodyRewriteTest {

    private val body = "Salut\n\n-- \nAncienne"   // "Ancienne" sits at [11, 19)

    private fun swapSignature(text: String): String? =
        if (text.endsWith("Ancienne")) text.removeSuffix("Ancienne") + "Nouvelle et longue" else null

    @Test
    fun `a span before the signature stays exactly where it was`() {
        val rich = RichBody(body, mapOf(Inline.BOLD to listOf(Span(0, 5))))

        val out = rewriteRichBody(rich, caret = 5, rewrite = ::swapSignature)

        assertEquals("the rewrite must land in the text", "Salut\n\n-- \nNouvelle et longue", out?.text)
        assertEquals(
            "the bold on `Salut` must survive an identity change untouched: the signature changed, " +
                "the user's styling did not",
            mapOf(Inline.BOLD to listOf(Span(0, 5))), out?.ranges,
        )
    }

    @Test
    fun `a span inside the old signature goes with it`() {
        val rich = RichBody(body, mapOf(
            Inline.BOLD to listOf(Span(0, 5)),
            Inline.ITALIC to listOf(Span(11, 17)),   // "Ancien", strictly inside the replaced segment
        ))

        val out = rewriteRichBody(rich, caret = 0, rewrite = ::swapSignature)

        assertEquals(
            "styling that lived in the old signature must not be smeared over the new one",
            mapOf(Inline.BOLD to listOf(Span(0, 5))), out?.ranges,
        )
    }

    @Test
    fun `a span after an INSERTED signature shifts with the text`() {
        val plain = "Salut\n\n> quoted"
        val rich = RichBody(plain, mapOf(Inline.UNDERLINE to listOf(Span(9, 15))))   // "quoted"
        val insert: (String) -> String? = { text -> text.replaceFirst("\n\n", "\n\n-- \nSig\n\n") }

        val out = rewriteRichBody(rich, caret = 5, rewrite = insert)

        assertEquals("Salut\n\n-- \nSig\n\n> quoted", out?.text)
        assertEquals(mapOf(Inline.UNDERLINE to listOf(Span(18, 24))), out?.ranges)
    }

    @Test
    fun `a rewrite that declines yields null, and nothing is invented`() {
        val rich = RichBody(body, mapOf(Inline.BOLD to listOf(Span(0, 5))))
        assertNull(rewriteRichBody(rich, caret = 0) { null })
    }
}
