package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/**
 * [MimeParser.decodedHeaderOf] — the read that hands a header over READY TO SHOW, next to
 */
class DecodedHeaderOfTest {

    /** [text] as one RFC 2047 base64 encoded-word. */
    private fun word(text: String): String =
        "=?utf-8?B?" + Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)) + "?="

    /** [text] as the raw UTF-8 octets a header carries when the sender skips RFC 2047. */
    private fun rawBytes(text: String): String =
        String(text.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)

    private fun message(subject: String): String =
        "From: a@b.test\r\nSubject: $subject\r\nContent-Type: text/plain\r\n\r\nbody\r\n"

    @Test fun `an encoded-word header is raw through headerOf and readable through decodedHeaderOf`() {
        val raw = message(word("Réunion / Отчёт"))
        assertEquals(word("Réunion / Отчёт"), MimeParser.headerOf(raw, "Subject"))
        assertEquals("Réunion / Отчёт", MimeParser.decodedHeaderOf(raw, "Subject"))
    }

    @Test fun `a bidi override survives headerOf and is removed by decodedHeaderOf`() {
        val raw = message(rawBytes("Facture \u202Efdp.exe"))
        assertEquals(rawBytes("Facture \u202Efdp.exe"), MimeParser.headerOf(raw, "Subject"))
        assertEquals("Facture fdp.exe", MimeParser.decodedHeaderOf(raw, "Subject"))
    }

    @Test fun `an absent header reads as null, not as an empty string`() {
        val raw = message("Plain")
        assertNull(MimeParser.headerOf(raw, "X-Nothing"))
        assertNull(MimeParser.decodedHeaderOf(raw, "X-Nothing"))
    }

    @Test fun `the header name is case-insensitive`() {
        val raw = message(word("Réunion / Отчёт"))
        assertEquals("Réunion / Отчёт", MimeParser.decodedHeaderOf(raw, "SUBJECT"))
        assertEquals("Réunion / Отчёт", MimeParser.decodedHeaderOf(raw, "sUbJeCt"))
    }

    // -- where the header block ENDS ---------------------------------------------------------------
    //
    // `headerOf` reads the header block alone, and it stopped copying the body to get at it: on a
    // decrypted PGP/MIME entity that copy was the whole entity, attachments included. The three
    // cases below are the ones the split has to keep answering exactly as it did.

    /** The blank line ends the block: nothing under it is a header, whatever it looks like. */
    @Test fun `the body is not read as headers, however much it looks like one`() {
        val raw = "Subject: cover\r\nFrom: a@b.test\r\n\r\nSubject: protected\r\nX-Body: yes\r\n"
        assertEquals("cover", MimeParser.headerOf(raw, "Subject"))
        assertEquals("cover", MimeParser.decodedHeaderOf(raw, "Subject"))
        assertNull("a body line is not a header", MimeParser.headerOf(raw, "X-Body"))
    }

    /** A part with no blank line at all is all headers — the split has always answered that. */
    @Test fun `a part with no blank line at all is header block to its last line`() {
        val raw = "Subject: no body follows\r\nFrom: a@b.test"
        assertEquals("no body follows", MimeParser.headerOf(raw, "Subject"))
        assertEquals("no body follows", MimeParser.decodedHeaderOf(raw, "Subject"))
        assertEquals("a@b.test", MimeParser.headerOf(raw, "From"))
    }

    /** A folded header, which nothing pinned on this path: the continuation lines belong to it. */
    @Test fun `a folded header reads as one unfolded value`() {
        val raw = "From: a@b.test\r\nSubject: a long one\r\n that was folded\r\n\ttwice\r\n\r\nbody\r\n"
        assertEquals("a long one that was folded twice", MimeParser.headerOf(raw, "Subject"))
        assertEquals("a long one that was folded twice", MimeParser.decodedHeaderOf(raw, "Subject"))
    }

    /** The same fold with no body at all: both rules at once. */
    @Test fun `a folded header with no body still reads whole`() {
        val raw = "Subject: =?utf-8?Q?R=C3=A9union?=\r\n de mardi"
        assertEquals("=?utf-8?Q?R=C3=A9union?= de mardi", MimeParser.headerOf(raw, "Subject"))
        assertEquals("Réunion de mardi", MimeParser.decodedHeaderOf(raw, "Subject"))
    }
}
