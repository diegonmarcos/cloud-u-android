package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Attachment filenames that are not ASCII (Codeberg #101, the reporter's second sentence: "the
 */
class AttachmentNameTest {

    private fun message(disposition: String, contentType: String = "application/pdf"): String =
        """
        From: a@b.c
        Content-Type: multipart/mixed; boundary="B"

        --B
        Content-Type: text/plain

        hello
        --B
        Content-Type: $contentType
        Content-Disposition: $disposition
        Content-Transfer-Encoding: base64

        AAAA
        --B--
        """.trimIndent().replace("\n", "\r\n")

    private fun nameOf(disposition: String, contentType: String = "application/pdf"): String? =
        MimeParser.parseBody(message(disposition, contentType)).attachments.firstOrNull()?.name

    /** RFC 2231 §4: charset, language, percent-encoded bytes. The shape the standard prescribes. */
    @Test fun `an extended parameter is decoded with its charset`() {
        assertEquals(
            "Отчёт.pdf",
            nameOf("attachment; filename*=UTF-8''%D0%9E%D1%82%D1%87%D1%91%D1%82.pdf"),
        )
    }

    /** RFC 2231 §3: a long name split over sections, reassembled in index order. */
    @Test fun `a split parameter is reassembled before it is decoded`() {
        assertEquals(
            "Отчёт.pdf",
            nameOf(
                "attachment; filename*0*=UTF-8''%D0%9E%D1%82%D1%87; " +
                    "filename*1*=%D1%91%D1%82; filename*2=\".pdf\"",
            ),
        )
    }

    /** A multi-byte character straddling the split: decoding section by section would have
     *  produced two replacement characters instead of one letter. */
    @Test fun `a character split across two sections survives`() {
        assertEquals(
            "Отчёт",
            nameOf("attachment; filename*0*=UTF-8''%D0%9E%D1%82%D1%87%D1; filename*1*=%91%D1%82"),
        )
    }

    /** Not legal in a parameter, but very common in the wild — and the reason we reuse the
     *  encoded-word decoder rather than writing a second one. */
    @Test fun `an RFC 2047 word in a filename is decoded`() {
        assertEquals("Отчёт.pdf", nameOf("attachment; filename=\"=?UTF-8?B?0J7RgtGH0ZHRgi5wZGY=?=\""))
        assertEquals("Отчёт.pdf", nameOf("attachment; filename=\"=?utf-8?Q?=D0=9E=D1=82=D1=87=D1=91=D1=82=2Epdf?=\""))
    }

    @Test fun `a plain ASCII filename is unchanged`() {
        assertEquals("report.pdf", nameOf("attachment; filename=report.pdf"))
        assertEquals("report.pdf", nameOf("attachment; filename=\"report.pdf\""))
    }

    /** `filename` CONTAINS `name`: the old regex answered the Content-Type's `name` parameter
     *  when asked for the disposition's, and vice versa. */
    @Test fun `the name parameter is not found inside filename`() {
        assertEquals(
            "invoice.pdf",
            nameOf("attachment", contentType = "application/pdf; name=\"invoice.pdf\""),
        )
        // A semicolon inside the quoted value does not split the parameter list either.
        assertEquals("a;b.pdf", nameOf("attachment; filename=\"a;b.pdf\""))
    }

    /** An unknown charset falls back to UTF-8 instead of dropping the name. */
    @Test fun `an unknown charset still yields a name`() {
        assertEquals("report.pdf", nameOf("attachment; filename*=nosuchcharset''report.pdf"))
    }

    /** A crafted name must not be able to reverse how it reads on screen. */
    @Test fun `a bidi override is stripped from the displayed name`() {
        assertEquals("annexegpj.exe", nameOf("attachment; filename*=UTF-8''annexe%E2%80%AEgpj.exe"))
    }

    /** No filename at all: the part is still listed, under the fallback label. */
    @Test fun `a part with no filename keeps its fallback name`() {
        assertEquals("attachment", nameOf("attachment"))
    }
}
