package app.sterna.ui.message

import app.sterna.core.imap.MimeParser
import app.sterna.core.jmap.ContentTooLargeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Executes the two decisions behind the attached-message sheet: which attachment opens as one
 */
class AttachedMessageTest {

    private fun eml(vararg lines: String): ByteArray =
        lines.joinToString("\r\n").toByteArray(Charsets.UTF_8)

    @Test fun `a plain message gives its decoded headers and its text`() {
        val m = attachedMessageOf(
            eml(
                "From: Interne Un <interne.un@example.org>",
                "To: Interne Deux <interne.deux@example.org>",
                "Date: Tue, 1 Sep 2026 10:00:00 +0200",
                "Subject: =?utf-8?q?JOINT-INTERNE-01_R=C3=A9union_d=C3=A9cal=C3=A9e?=",
                "Content-Type: text/plain; charset=utf-8",
                "Content-Transfer-Encoding: 7bit",
                "",
                "la réunion passe à jeudi 14 h",
                "",
            ),
        )
        assertEquals("JOINT-INTERNE-01 Réunion décalée", m.subject)
        assertEquals("Interne Un <interne.un@example.org>", m.from)
        assertEquals("Interne Deux <interne.deux@example.org>", m.to)
        assertEquals("Tue, 1 Sep 2026 10:00:00 +0200", m.date)
        assertNull(m.cc)
        assertTrue("body was: <${m.body}>", "jeudi 14 h" in m.body.trim())
        assertEquals(emptyList<String>(), m.attachmentNames)
    }

    @Test fun `a bidi override in the subject does not reach the sheet`() {
        val forged = Base64.getEncoder().encodeToString("Facture‮fdp.exe".toByteArray(Charsets.UTF_8))
        val m = attachedMessageOf(
            eml(
                "From: =?utf-8?b?$forged?= <a@example.org>",
                "To: =?utf-8?b?$forged?= <b@example.org>",
                "Cc: =?utf-8?b?$forged?= <c@example.org>",
                "Date: =?utf-8?b?$forged?=",
                "Subject: =?utf-8?b?$forged?=",
                "Content-Type: text/plain; charset=utf-8",
                "",
                "corps",
            ),
        )
        assertFalse("subject was: <${m.subject}>", '‮' in m.subject.orEmpty())
        assertEquals("Facturefdp.exe", m.subject)
        // Every header line goes through the same door, not only the subject: From, To, Cc and
        // Date are sender-controlled text bound for the screen just the same.
        assertEquals("Facturefdp.exe <a@example.org>", m.from)
        assertEquals("Facturefdp.exe <b@example.org>", m.to)
        assertEquals("Facturefdp.exe <c@example.org>", m.cc)
        assertEquals("Facturefdp.exe", m.date)
    }

    @Test fun `the text part is preferred when the message carries both`() {
        val m = attachedMessageOf(
            eml(
                "From: a@example.org",
                "Subject: both",
                "Content-Type: multipart/alternative; boundary=\"alt\"",
                "",
                "--alt",
                "Content-Type: text/plain; charset=utf-8",
                "",
                "version texte",
                "--alt",
                "Content-Type: text/html; charset=utf-8",
                "",
                "<p>version <b>html</b></p>",
                "--alt--",
                "",
            ),
        )
        assertEquals("version texte", m.body.trim())
    }

    @Test fun `inline images, invites and pgp control parts are not listed as its attachments`() {
        val png = Base64.getEncoder().encodeToString("png".toByteArray())
        val m = attachedMessageOf(
            eml(
                "From: a@example.org",
                "Subject: newsletter",
                "Content-Type: multipart/mixed; boundary=\"xx\"",
                "",
                "--xx",
                "Content-Type: text/html; charset=utf-8",
                "",
                "<p>voir <img src=\"cid:logo\"></p>",
                "--xx",
                "Content-Type: image/png",
                "Content-ID: <logo>",
                "Content-Transfer-Encoding: base64",
                "",
                png,
                "--xx",
                "Content-Type: text/calendar; method=REQUEST",
                "",
                "BEGIN:VCALENDAR",
                "END:VCALENDAR",
                "--xx",
                "Content-Type: application/pgp-signature",
                "",
                "-----BEGIN PGP SIGNATURE-----",
                "--xx",
                "Content-Type: application/pdf; name=\"rapport.pdf\"",
                "Content-Disposition: attachment; filename=\"rapport.pdf\"",
                "Content-Transfer-Encoding: base64",
                "",
                png,
                "--xx--",
                "",
            ),
        )
        assertEquals(listOf("rapport.pdf"), m.attachmentNames)
    }

    @Test fun `an html-only message is shown as text`() {
        val m = attachedMessageOf(
            eml(
                "From: a@example.org",
                "Subject: html",
                "Content-Type: text/html; charset=utf-8",
                "",
                "<p>Alinéa <b>un</b></p><p>deux</p>",
            ),
        )
        assertFalse("body was: <${m.body}>", '<' in m.body)
        assertTrue("body was: <${m.body}>", "Alinéa un" in m.body)
        assertTrue("body was: <${m.body}>", "deux" in m.body)
    }

    @Test fun `the names of its own attachments are listed`() {
        val notes = Base64.getEncoder().encodeToString("notes".toByteArray())
        val m = attachedMessageOf(
            eml(
                "From: a@example.org",
                "Subject: with file",
                "Content-Type: multipart/mixed; boundary=\"xx\"",
                "",
                "--xx",
                "Content-Type: text/plain; charset=utf-8",
                "",
                "texte principal",
                "--xx",
                "Content-Type: text/plain; name=\"notes.txt\"",
                "Content-Disposition: attachment; filename=\"notes.txt\"",
                "Content-Transfer-Encoding: base64",
                "",
                notes,
                "--xx--",
                "",
            ),
        )
        assertEquals(listOf("notes.txt"), m.attachmentNames)
        assertTrue("body was: <${m.body}>", "texte principal" in m.body)
    }

    @Test fun `a source past the parser's ceiling is refused, not shown empty`() {
        val bytes = ByteArray(MimeParser.MAX_BODY_CHARS + 1) { 'a'.code.toByte() }
        val e = assertThrows(ContentTooLargeException::class.java) { attachedMessageOf(bytes) }
        assertEquals(MimeParser.MAX_BODY_CHARS.toLong(), e.maxBytes)
        assertEquals((MimeParser.MAX_BODY_CHARS + 1).toLong(), e.bytes)
    }

    @Test fun `a message with neither text nor html gives an empty body`() {
        val m = attachedMessageOf(
            eml(
                "From: a@example.org",
                "Subject: binary",
                "Content-Type: application/octet-stream",
                "",
                "\u0000\u0001\u0002",
            ),
        )
        assertEquals("", m.body)
        assertEquals("binary", m.subject)
    }

    @Test fun `only message-rfc822 opens as an attached message`() {
        assertTrue(isAttachedMessage("message/rfc822"))
        assertTrue(isAttachedMessage("MESSAGE/RFC822"))
        assertTrue(isAttachedMessage("message/rfc822; name=x"))
        assertFalse(isAttachedMessage(null))
        assertFalse(isAttachedMessage("application/pdf"))
        assertFalse(isAttachedMessage("text/calendar"))
        assertFalse(isAttachedMessage("message/rfc822-headers"))
    }
}
