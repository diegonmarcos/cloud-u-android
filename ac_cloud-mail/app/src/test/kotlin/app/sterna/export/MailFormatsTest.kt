package app.sterna.export

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MailFormatsTest {
    @Test fun `eml is the raw source byte for byte`() {
        val raw = byteArrayOf(0x46, 0x72, 0x6f, 0x6d, 0x3a, 0x20, 0xe9.toByte(), 0x0d, 0x0a, 0x0d, 0x0a, 0x00, 0xff.toByte())
        val out = ByteArrayOutputStream()
        writeEml(out, raw)
        assertArrayEquals(raw, out.toByteArray())
    }

    @Test fun `mbox separates messages and quotes From lines mboxrd style`() {
        val a = "Subject: a\n\nFrom me\n>From you\nbye".toByteArray()
        val b = "Subject: b\n\nok\n".toByteArray()
        val out = ByteArrayOutputStream()
        writeMbox(out, listOf("MAILER-DAEMON Thu Sep 24 10:00:00 2026" to a, "MAILER-DAEMON Thu Sep 24 11:00:00 2026" to b))
        assertEquals(
            "From MAILER-DAEMON Thu Sep 24 10:00:00 2026\nSubject: a\n\n>From me\n>>From you\nbye\n\n" +
                "From MAILER-DAEMON Thu Sep 24 11:00:00 2026\nSubject: b\n\nok\n\n",
            out.toString(Charsets.ISO_8859_1),
        )
    }

    @Test fun `mbox from line uses the asctime form and survives a bad date`() {
        assertEquals("MAILER-DAEMON Thu Sep 24 10:00:00 2026", mboxFromLine("2026-09-24T10:00:00Z"))
        assertEquals("MAILER-DAEMON Thu Jan 1 00:00:00 1970", mboxFromLine(null))
    }
}
