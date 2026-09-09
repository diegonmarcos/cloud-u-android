package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

/**
 * [MimeParser.decodedHeaderValue] — the same sanitising door as [MimeParser.decodedHeaderOf], for
 */
class DecodedHeaderValueTest {

    private fun word(text: String): String =
        "=?utf-8?B?" + Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)) + "?="

    @Test fun `an encoded-word value comes back readable`() {
        assertEquals("Émile Zola <emile@example.org>", MimeParser.decodedHeaderValue(word("Émile Zola <emile@example.org>")))
    }

    @Test fun `a bidi override is removed from the value`() {
        assertEquals("Facture fdp.exe", MimeParser.decodedHeaderValue(word("Facture ‮fdp.exe")))
    }
}
