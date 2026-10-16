package app.sterna.export

import java.io.ByteArrayOutputStream
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MhtmlBuilderTest {
    private val png = ByteArray(300) { (it * 7).toByte() }
    private val pdf = ByteArray(5000) { (it % 251).toByte() }

    private fun build(msg: ExportMessage, boundary: String = "=_Mhtml_test"): String {
        val out = ByteArrayOutputStream()
        writeMhtml(out, msg, LABELS, boundary)
        return out.toString(Charsets.ISO_8859_1)
    }

    private val msg get() = message(
        parts = listOf(
            part("logo.png", png, "image/png", cid = "logo@x", inline = true),
            part("Rapport.pdf", pdf, "application/pdf"),
        ),
    )

    @Test fun `top level is multipart related with html root and CRLF everywhere`() {
        val raw = build(msg)
        val mime = parseMime(raw)
        assertTrue(mime.topHeaders.contains("MIME-Version: 1.0"))
        assertTrue(mime.topHeaders.contains("Content-Type: multipart/related; type=\"text/html\"; boundary=\"=_Mhtml_test\""))
        assertTrue(mime.topHeaders.contains("From: Alex <alex@example.org>"))
        assertTrue(mime.topHeaders.contains("Date: Thu, 24 Sep 2026 10:00:00 GMT"))
        assertEquals(3, mime.parts.size)
        assertEquals("text/html; charset=\"utf-8\"", mime.parts[0].first["content-type"])
        assertFalse("bare LF found", Regex("(?<!\r)\n").containsMatchIn(raw))
        assertTrue(raw.trimEnd().endsWith("--=_Mhtml_test--"))
    }

    @Test fun `every boundary line is exact and the closing one is last`() {
        val raw = build(msg)
        assertEquals(3, Regex("(?m)^--=_Mhtml_test(?=\r\n)").findAll(raw).count())
        assertEquals(1, Regex("(?m)^--=_Mhtml_test--(?=\r\n)").findAll(raw).count())
    }

    @Test fun `generated boundary cannot occur in base64 or quoted-printable bodies`() {
        val b = newBoundary()
        assertTrue(b.startsWith("=_"))
        assertFalse(Base64.getEncoder().encodeToString(ByteArray(4096) { it.toByte() }).contains("=_"))
        assertFalse(quotedPrintable("a=_b = _").contains("=_"))
    }

    @Test fun `inline image is mapped by Content-ID and Content-Location cid`() {
        val mime = parseMime(build(msg))
        val img = mime.parts[1].first
        assertEquals("<logo@x>", img["content-id"])
        assertEquals("cid:logo@x", img["content-location"])
        assertEquals("inline; filename=\"logo.png\"", img["content-disposition"])
        // The html root still references the same cid, so the two meet.
        assertTrue(decodeQp(mime.parts[0].second).contains("src=\"cid:logo@x\""))
    }

    @Test fun `attachment keeps its name and decodes to the original bytes`() {
        val mime = parseMime(build(msg))
        val (h, body) = mime.parts[2]
        assertEquals("attachment; filename=\"Rapport.pdf\"", h["content-disposition"])
        assertEquals("base64", h["content-transfer-encoding"])
        assertArrayEquals(pdf, Base64.getMimeDecoder().decode(body))
        val inline = mime.parts[1].second
        assertArrayEquals(png, Base64.getMimeDecoder().decode(inline))
    }

    @Test fun `base64 lines are 76 columns at most`() {
        val mime = parseMime(build(msg))
        mime.parts[2].second.split("\r\n").forEach { assertTrue(it.length <= 76) }
        assertEquals(76, mime.parts[2].second.split("\r\n").first().length)
    }

    @Test fun `html root is quoted-printable utf8 within 76 columns and round trips`() {
        val raw = build(message(body = "<p>" + "é".repeat(100) + " = end</p>"))
        val html = parseMime(raw).parts[0]
        assertEquals("quoted-printable", html.first["content-transfer-encoding"])
        html.second.split("\r\n").forEach { assertTrue("line too long: ${it.length}", it.length <= 76) }
        val text = decodeQp(html.second)
        assertTrue(text.contains("é".repeat(100) + " = end"))
        assertTrue("headers block is in the page", text.contains("<b>From:</b> Alex &lt;alex@example.org&gt;"))
    }

    @Test fun `non-ascii subject is an RFC 2047 encoded word`() {
        val top = parseMime(build(message(subject = "Rapport d'été ☀"))).topHeaders
        val line = top.split("\r\n").first { it.startsWith("Subject:") }
        assertTrue(line, line.startsWith("Subject: =?UTF-8?B?"))
        val decoded = Regex("=\\?UTF-8\\?B\\?([^?]+)\\?=").findAll(top.substringAfter("Subject: ").substringBefore("Content-Type")).joinToString("") {
            String(Base64.getDecoder().decode(it.groupValues[1]), Charsets.UTF_8)
        }
        assertEquals("Rapport d'été ☀", decoded)
    }

    @Test fun `duplicate attachment names are numbered and quotes in names cannot break the header`() {
        val raw = build(message(parts = listOf(part("a\"b.txt", byteArrayOf(1)), part("a\"b.txt", byteArrayOf(2)))))
        val h = parseMime(raw).parts.drop(1).map { it.first["content-disposition"] }
        assertEquals(listOf("attachment; filename=\"a_b.txt\"", "attachment; filename=\"a_b (2).txt\""), h)
    }

    @Test fun `cancel stops the build and signals ExportCancelled`() {
        var calls = 0
        try {
            writeMhtml(ByteArrayOutputStream(), msg, LABELS, isCancelled = { ++calls > 2 })
            org.junit.Assert.fail("expected ExportCancelled")
        } catch (e: ExportCancelled) {
        }
    }

    @Test fun `progress reaches the total of the parts`() {
        var last = 0L to 0L
        writeMhtml(ByteArrayOutputStream(), msg, LABELS, progress = { d, t -> last = d to t })
        assertEquals((png.size + pdf.size).toLong(), last.second)
        assertEquals(last.second, last.first)
    }
}

class MhtmlNonAsciiNameTest {
    @Test fun `a non-ascii filename travels as RFC 2231 next to its ascii shadow`() {
        val out = ByteArrayOutputStream()
        writeMhtml(out, message(parts = listOf(part("Rapport été.pdf", byteArrayOf(1)))), LABELS)
        val disp = parseMime(out.toString(Charsets.ISO_8859_1)).parts[1].first["content-disposition"]
        assertEquals("attachment; filename=\"Rapport _t_.pdf\"; filename*=UTF-8''Rapport%20%C3%A9t%C3%A9.pdf", disp)
    }
}
