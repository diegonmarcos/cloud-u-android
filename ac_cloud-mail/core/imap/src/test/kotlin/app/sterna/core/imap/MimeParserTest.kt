package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class MimeParserTest {
    @Test
    fun parsesPlainTextBody() {
        val raw = "Content-Type: text/plain; charset=utf-8\r\n\r\nHello world"
        val body = MimeParser.parseBody(raw)
        assertEquals("Hello world", body.text)
        assertEquals(0, body.attachments.size)
    }

    @Test
    fun decodesBase64TextBody() {
        val encoded = Base64.getEncoder().encodeToString("Bonjour".toByteArray())
        val raw = "Content-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: base64\r\n\r\n$encoded"
        assertEquals("Bonjour", MimeParser.parseBody(raw).text)
    }

    @Test
    fun extractsMultipartBodyAndAttachment() {
        val fileBytes = "PDF-DATA".toByteArray()
        val fileB64 = Base64.getEncoder().encodeToString(fileBytes)
        val raw = buildString {
            append("Content-Type: multipart/mixed; boundary=\"BND\"\r\n\r\n")
            append("--BND\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n\r\n")
            append("See attached\r\n")
            append("--BND\r\n")
            append("Content-Type: application/pdf; name=\"doc.pdf\"\r\n")
            append("Content-Transfer-Encoding: base64\r\n")
            append("Content-Disposition: attachment; filename=\"doc.pdf\"\r\n\r\n")
            append("$fileB64\r\n")
            append("--BND--\r\n")
        }
        val body = MimeParser.parseBody(raw)
        assertEquals("See attached", body.text?.trim())
        assertEquals(1, body.attachments.size)
        val att = body.attachments.first()
        assertEquals("doc.pdf", att.name)
        assertEquals("application/pdf", att.type)
        assertEquals("base64", att.encoding)
        assertEquals("2", att.section) // second part of the top-level multipart
    }

    @Test
    fun prefersHtmlOverPlainInAlternative() {
        val raw = buildString {
            append("Content-Type: multipart/alternative; boundary=\"A\"\r\n\r\n")
            append("--A\r\n")
            append("Content-Type: text/plain\r\n\r\nplain\r\n")
            append("--A\r\n")
            append("Content-Type: text/html\r\n\r\n<p>rich</p>\r\n")
            append("--A--\r\n")
        }
        val body = MimeParser.parseBody(raw)
        assertNotNull(body.html)
        assertEquals("<p>rich</p>", body.html?.trim())
    }

    @Test
    fun capturesInlineImageContentId() {
        val imgB64 = Base64.getEncoder().encodeToString("PNG".toByteArray())
        val raw = buildString {
            append("Content-Type: multipart/related; boundary=\"R\"\r\n\r\n")
            append("--R\r\n")
            append("Content-Type: text/html\r\n\r\n<p><img src=\"cid:logo@x\"></p>\r\n")
            append("--R\r\n")
            append("Content-Type: image/png\r\n")
            append("Content-Transfer-Encoding: base64\r\n")
            append("Content-ID: <logo@x>\r\n")
            append("Content-Disposition: inline\r\n\r\n")
            append("$imgB64\r\n")
            append("--R--\r\n")
        }
        val body = MimeParser.parseBody(raw)
        assertEquals(1, body.attachments.size)
        val att = body.attachments.first()
        assertEquals("logo@x", att.cid) // angle brackets stripped
        assertEquals("image/png", att.type)
    }

    @Test
    fun decodeBytesRoundTripsBase64() {
        val original = byteArrayOf(1, 2, 3, 65, 66, 67)
        val encoded = Base64.getEncoder().encodeToString(original)
        assertEquals(original.toList(), MimeParser.decodeBytes(encoded, "base64").toList())
    }

    @Test
    fun keepsNestedMultipartSectionPaths() {
        // multipart/mixed [ multipart/alternative [ plain, html ], attachment ] — the ordinary
        // shape of a rich message with a file; section paths must stay 1.1 / 1.2 / 2.
        val fileB64 = Base64.getEncoder().encodeToString("DATA".toByteArray())
        val raw = buildString {
            append("Content-Type: multipart/mixed; boundary=\"OUT\"\r\n\r\n")
            append("--OUT\r\n")
            append("Content-Type: multipart/alternative; boundary=\"IN\"\r\n\r\n")
            append("--IN\r\n")
            append("Content-Type: text/plain\r\n\r\nplain\r\n")
            append("--IN\r\n")
            append("Content-Type: text/html\r\n\r\n<p>rich</p>\r\n")
            append("--IN--\r\n")
            append("--OUT\r\n")
            append("Content-Type: application/pdf\r\n")
            append("Content-Transfer-Encoding: base64\r\n")
            append("Content-Disposition: attachment; filename=\"doc.pdf\"\r\n\r\n")
            append("$fileB64\r\n")
            append("--OUT--\r\n")
        }
        val body = MimeParser.parseBody(raw)
        assertEquals("<p>rich</p>", body.html?.trim())
        assertEquals("plain", body.text?.trim())
        assertEquals(1, body.attachments.size)
        assertEquals("2", body.attachments.first().section)
        // The nested leaves stay addressable by their path.
        assertEquals("plain", MimeParser.partAt(raw, "1.1")?.second?.trim())
        assertEquals("<p>rich</p>", MimeParser.partAt(raw, "1.2")?.second?.trim())
        assertEquals(fileB64, MimeParser.partAt(raw, "2")?.second?.trim())
    }

    // ---- 8-bit bodies and their declared charset ---------------------------------------------

    /**
     * What [ImapParser] hands over: the wire bytes of [text] in [charset], as a byte container
     */
    private fun wire(text: String, charset: java.nio.charset.Charset): String =
        String(text.toByteArray(charset), Charsets.ISO_8859_1)

    private fun eightBitMessage(text: String, charset: java.nio.charset.Charset, declared: String): String =
        "Content-Type: text/plain; charset=$declared\r\nContent-Transfer-Encoding: 8bit\r\n\r\n" +
            wire(text, charset)

    /**
     * The reported defect: a message sent in 8 bits with a charset other than UTF-8. Nothing
     */
    @Test
    fun anEightBitLatin1BodyIsReadWithItsDeclaredCharset() {
        val body = MimeParser.parseBody(
            eightBitMessage("Café à Noël", Charsets.ISO_8859_1, "iso-8859-1"),
        )
        assertEquals("Café à Noël", body.text)
    }

    /** Cyrillic in its native charset: almost every character is 8-bit, so almost every
     *  character was destroyed rather than a handful of accents. */
    @Test
    fun anEightBitKoi8BodyIsReadWithItsDeclaredCharset() {
        val koi8 = charset("KOI8-R")
        val body = MimeParser.parseBody(eightBitMessage("Привет, мир", koi8, "koi8-r"))
        assertEquals("Привет, мир", body.text)
    }

    /**
     * THE WITNESS for "the parser reads bytes now": a UTF-8 body in 8 bits is the ordinary case,
     */
    @Test
    fun anEightBitUtf8BodyIsStillCorrect() {
        val body = MimeParser.parseBody(eightBitMessage("Café 日本語 🐦", Charsets.UTF_8, "utf-8"))
        assertEquals("Café 日本語 🐦", body.text)
    }

    /** An 8-bit part with no declared charset falls back to UTF-8, as everywhere else. */
    @Test
    fun anEightBitBodyWithNoCharsetFallsBackToUtf8() {
        val raw = "Content-Type: text/plain\r\nContent-Transfer-Encoding: 8bit\r\n\r\n" +
            wire("Café", Charsets.UTF_8)
        assertEquals("Café", MimeParser.parseBody(raw).text)
    }

    /**
     * THE WITNESS for the majority case: `base64` and `quoted-printable` are ASCII on the wire
     */
    @Test
    fun base64AndQuotedPrintableAreUnaffected() {
        val b64 = Base64.getEncoder().encodeToString("Café à Noël".toByteArray(Charsets.ISO_8859_1))
        val base64Raw = "Content-Type: text/plain; charset=iso-8859-1\r\n" +
            "Content-Transfer-Encoding: base64\r\n\r\n$b64"
        assertEquals("Café à Noël", MimeParser.parseBody(base64Raw).text)

        val qpRaw = "Content-Type: text/plain; charset=iso-8859-1\r\n" +
            "Content-Transfer-Encoding: quoted-printable\r\n\r\nCaf=E9 =E0 No=EBl"
        assertEquals("Café à Noël", MimeParser.parseBody(qpRaw).text)

        val utf8B64 = Base64.getEncoder().encodeToString("日本語".toByteArray(Charsets.UTF_8))
        val utf8Raw = "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Transfer-Encoding: base64\r\n\r\n$utf8B64"
        assertEquals("日本語", MimeParser.parseBody(utf8Raw).text)
    }

    /**
     * The silent half of the defect. An attachment carried as `8bit`/`binary` is written to disk
     */
    @Test
    fun anEightBitAttachmentKeepsItsOctets() {
        val original = byteArrayOf(0x50.toByte(), 0xC3.toByte(), 0xA9.toByte(), 0x00, 0xFF.toByte(), 0x7F)
        val asParsed = String(original, Charsets.ISO_8859_1)
        assertEquals(original.toList(), MimeParser.decodeBytes(asParsed, "8bit").toList())
        assertEquals(original.toList(), MimeParser.decodeBytes(asParsed, "binary").toList())
        assertFalse(
            "a lost byte shows up as '?'",
            MimeParser.decodeBytes(asParsed, "8bit").contains(0x3F.toByte()),
        )
    }

    /**
     * The shortcut that keeps an ordinary `7bit` part from being copied twice: when the body is
     */
    @Test
    fun anAsciiSevenBitBodyIsUnchangedUnderEveryAsciiTransparentCharset() {
        for (declared in listOf("us-ascii", "utf-8", "iso-8859-1", "nosuchcharset")) {
            val raw = "Content-Type: text/plain; charset=$declared\r\n" +
                "Content-Transfer-Encoding: 7bit\r\n\r\nHello world"
            assertEquals(declared, "Hello world", MimeParser.parseBody(raw).text)
        }
    }

    /**
     * THE WITNESS that the shortcut checks the charset and not just the bytes. A UTF-16 body is
     */
    @Test
    fun aUtf16BodyStillTakesTheCharsetPath() {
        val raw = "Content-Type: text/plain; charset=utf-16be\r\n" +
            "Content-Transfer-Encoding: 8bit\r\n\r\n" + wire("Hi", charset("UTF-16BE"))
        assertEquals("Hi", MimeParser.parseBody(raw).text)
    }

    /** An HTML part gets the same treatment as a plain one — same call, both branches. */
    @Test
    fun anEightBitHtmlPartIsDecodedToo() {
        val raw = "Content-Type: text/html; charset=iso-8859-1\r\nContent-Transfer-Encoding: 8bit\r\n\r\n" +
            wire("<p>Café</p>", Charsets.ISO_8859_1)
        assertEquals("<p>Café</p>", MimeParser.parseBody(raw).html)
    }

    // ---- Hostile structure ------------------------------------------------------------------

    @Test
    fun aFloodOfBoundariesStopsAtTheSiblingCap() {
        // 60 000 one-line parts. The cap must bound the WORK, not just the recursion: splitting
        // the whole body first would materialise every segment before the first one is dropped.
        val parts = 60_000
        val raw = buildString(24 * parts + 128) {
            append("Content-Type: multipart/mixed; boundary=\"F\"\r\n\r\n")
            repeat(parts) { append("--F\r\nContent-Type: text/plain\r\n\r\np$it\r\n") }
            append("--F--\r\n")
        }
        val started = System.nanoTime()
        val body = MimeParser.parseBody(raw)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        // Only the first part contributes the body; the rest are simply never looked at.
        assertEquals("p0", body.text?.trim())
        assertEquals(0, body.attachments.size)
        assertTrue("parsing took ${elapsedMs}ms", elapsedMs < 5_000)
    }

    @Test
    fun aFloodOfEmptyBoundariesIsNotAPartExplosion() {
        // Bare delimiters with nothing between them: none of these is a part, and walking them
        // must stay linear instead of allocating a string per segment.
        val raw = buildString {
            append("Content-Type: multipart/mixed; boundary=\"F\"\r\n\r\n")
            repeat(200_000) { append("--F\r\n") }
            append("--F--\r\n")
        }
        val started = System.nanoTime()
        val body = MimeParser.parseBody(raw)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertNull(body.text)
        assertNull(body.html)
        assertEquals(0, body.attachments.size)
        assertTrue("parsing took ${elapsedMs}ms", elapsedMs < 5_000)
    }

    @Test
    fun attachmentsBeyondTheSiblingCapAreNotCollected() {
        val cap = MimeParser.MAX_PARTS
        val raw = buildString {
            append("Content-Type: multipart/mixed; boundary=\"F\"\r\n\r\n")
            repeat(cap + 50) { i ->
                append("--F\r\n")
                append("Content-Type: application/pdf\r\n")
                append("Content-Disposition: attachment; filename=\"f$i.pdf\"\r\n\r\n")
                append("x\r\n")
            }
            append("--F--\r\n")
        }
        assertEquals(cap, MimeParser.parseBody(raw).attachments.size)
    }

    @Test
    fun deepNestingStaysBounded() {
        // 200 levels of multipart, far past MAX_DEPTH: the walk must stop, not recurse.
        val depth = 200
        // Fixed-width names so no boundary is a prefix of another ("b1" would match "b199").
        fun b(i: Int) = "b%03d".format(i)
        val raw = buildString {
            append("Content-Type: multipart/mixed; boundary=\"${b(0)}\"\r\n\r\n")
            for (i in 0 until depth) {
                append("--${b(i)}\r\n")
                append("Content-Type: multipart/mixed; boundary=\"${b(i + 1)}\"\r\n\r\n")
            }
            append("--${b(depth)}\r\nContent-Type: text/plain\r\n\r\nburied\r\n")
            for (i in depth downTo 0) append("--${b(i)}--\r\n")
        }
        val body = MimeParser.parseBody(raw)
        // Too deep to reach — refused, not crashed.
        assertNull(body.text)
        assertNull(body.html)
    }

    @Test
    fun aSourceOverTheParseLimitIsRefusedNotParsed() {
        val raw = "Content-Type: text/plain\r\n\r\n" + "x".repeat(MimeParser.MAX_BODY_CHARS)
        val body = MimeParser.parseBody(raw)
        assertTrue(body.tooLarge)
        assertNull(body.text)
        assertNull(body.html)
        assertEquals(0, body.attachments.size)
    }

    @Test
    fun anOrdinaryMessageIsNotFlaggedTooLarge() {
        assertFalse(MimeParser.parseBody("Content-Type: text/plain\r\n\r\nHello").tooLarge)
    }

    @Test
    fun headerOfReadsTopLevelHeadersCaseInsensitivelyAndUnfolded() {
        val raw = buildString {
            append("Message-ID: <abc@example.com>\r\n")
            append("References: <one@example.com>\r\n")
            append(" <two@example.com>\r\n") // folded continuation
            append("Content-Type: text/plain\r\n\r\nHello")
        }
        assertEquals("<abc@example.com>", MimeParser.headerOf(raw, "message-id"))
        assertEquals("<one@example.com> <two@example.com>", MimeParser.headerOf(raw, "References"))
        assertEquals(null, MimeParser.headerOf(raw, "In-Reply-To"))
    }

    /**
     * "View headers" shows a header as it was sent — encoded-words stay encoded, deliberately.
     */
    @Test
    fun rawHeadersShowRawEightBitValuesAsText() {
        val raw = "Subject: " + wire("Réunion", Charsets.UTF_8) + "\r\n" +
            "X-Legacy: " + wire("Réunion", Charsets.ISO_8859_1) + "\r\n" +
            "X-Encoded: =?utf-8?B?UsOpdW5pb24=?=\r\n\r\nbody"
        val headers = MimeParser.rawHeaders(raw).toMap()
        assertEquals("Réunion", headers["Subject"])
        assertEquals("Réunion", headers["X-Legacy"])
        assertEquals("=?utf-8?B?UsOpdW5pb24=?=", headers["X-Encoded"])
    }
}
