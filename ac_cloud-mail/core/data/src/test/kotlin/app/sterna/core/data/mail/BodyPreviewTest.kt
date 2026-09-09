package app.sterna.core.data.mail

import app.sterna.core.imap.ImapTextPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.Charset

/**
 * A fetched fragment of a body, turned into the line a notification may show — executed, on the
 */
class BodyPreviewTest {

    /** [text] as it travels: encoded in [charset], then held one char per octet. */
    private fun wire(text: String, charset: Charset): String =
        String(text.toByteArray(charset), Charsets.ISO_8859_1)

    private fun part(mime: String, encoding: String?, charset: String?) =
        ImapTextPart(section = "1", mime = mime, encoding = encoding, charset = charset)

    private fun preview(
        raw: String,
        mime: String = "text/plain",
        encoding: String? = "7bit",
        charset: String? = "utf-8",
    ) = BodyPreview.fromPart(raw, part(mime, encoding, charset))

    // ---- the charset the sender announced ------------------------------------------------------

    @Test fun `an 8-bit UTF-8 body reads as its text`() {
        assertEquals("Café à Noël 日本語", preview(wire("Café à Noël 日本語", Charsets.UTF_8), encoding = "8bit"))
    }

    /** The charset is not a formality. Read as UTF-8, this body is a run of replacement
     *  characters; read as latin-1, it is what was written. */
    @Test fun `a latin-1 body is read as latin-1, not as UTF-8`() {
        val raw = wire("Réunion à Noël", Charsets.ISO_8859_1)
        assertEquals("Réunion à Noël", preview(raw, encoding = "8bit", charset = "iso-8859-1"))
    }

    @Test fun `a KOI8-R body is read as KOI8-R`() {
        val raw = wire("Привет, мир", charset("KOI8-R"))
        assertEquals("Привет, мир", preview(raw, encoding = "8bit", charset = "koi8-r"))
    }

    @Test fun `a windows-1252 body keeps its punctuation`() {
        val raw = wire("It’s — done", charset("windows-1252"))
        assertEquals("It’s — done", preview(raw, encoding = "8bit", charset = "windows-1252"))
    }

    @Test fun `a charset nobody knows falls back to UTF-8 rather than failing`() {
        val raw = wire("Café", Charsets.UTF_8)
        assertEquals("Café", preview(raw, encoding = "8bit", charset = "x-nonesuch-9"))
        assertEquals("Café", preview(raw, encoding = "8bit", charset = null))
        assertEquals("Café", preview(raw, encoding = "8bit", charset = ""))
    }

    // ---- the transfer-encoding -----------------------------------------------------------------

    @Test fun `a quoted-printable body is decoded, soft breaks included`() {
        val raw = "R=C3=A9union de l=\r\n'=C3=A9quipe"
        assertEquals("Réunion de l'équipe", preview(raw, encoding = "quoted-printable"))
    }

    /**
     * THE OTHER CUT THAT ALWAYS HAPPENS, and the commonest encoding of accented mail. The QP
     */
    @Test fun `a quoted-printable escape cut in half does not reach the screen`() {
        assertEquals("Café et th", preview("Caf=C3=A9 et th=C", encoding = "quoted-printable"))
        assertEquals("Bonjour", preview("Bonjour=", encoding = "quoted-printable"))
        assertEquals("Bonjour", preview("Bonjour=\r", encoding = "quoted-printable"))
    }

    /** THE INVERSE WITNESS: a COMPLETE escape or soft break at the very end still decodes — "drop
     *  the tail" must not become "drop a character of every message". */
    @Test fun `a complete escape at the end of the fragment survives`() {
        assertEquals("2 = 2", preview("2 =3D 2", encoding = "quoted-printable"))
        assertEquals("abc", preview("abc=\r\n", encoding = "quoted-printable"))
        assertEquals("Café", preview("Caf=C3=A9", encoding = "quoted-printable"))
    }

    @Test fun `a base64 body is decoded`() {
        val raw = java.util.Base64.getEncoder().encodeToString("Bonjour à tous".toByteArray(Charsets.UTF_8))
        assertEquals("Bonjour à tous", preview(raw, encoding = "base64"))
    }

    /**
     * THE CUT THAT ALWAYS HAPPENS. base64 carries three octets per four characters, so a
     */
    @Test fun `a base64 body cut mid-quantum still decodes to text, never to base64`() {
        val text = "The quick brown fox jumps over the lazy dog. "
        val encoded = java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        val cut = encoded.take(31) // 31 is not a multiple of 4: 7 whole quanta, 21 octets

        val shown = preview(cut, encoding = "base64")

        assertEquals(text.take(21), shown)
        assertFalse("the base64 itself must never reach the screen", shown!!.contains(cut.take(8)))
    }

    @Test fun `a base64 body wrapped in CRLF decodes across its lines`() {
        val text = "Bonjour, ceci est un message assez long pour tenir sur plusieurs lignes."
        val encoded = java.util.Base64.getMimeEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

        assertEquals(text, preview(encoded, encoding = "base64"))
    }

    /** A cut multi-byte character must not surface as a black diamond at the end of the line. */
    @Test fun `a UTF-8 character cut in half is dropped, not shown as a replacement`() {
        val whole = wire("Café", Charsets.UTF_8) // 5 octets: the é is two of them
        val shown = preview(whole.dropLast(1), encoding = "8bit")

        assertEquals("Caf", shown)
        assertFalse(shown!!.contains('�'))
    }

    // ---- HTML --------------------------------------------------------------------------------

    @Test fun `an HTML part is flattened to its text`() {
        val html = "<html><head><style>p{color:red}</style></head><body><p>Hi <b>there</b></p></body></html>"

        assertEquals("Hi there", preview(html, mime = "text/html"))
    }

    /**
     * The first 8 KB of a newsletter is very often its stylesheet. `htmlToText` removes a
     */
    @Test fun `an HTML fragment cut inside a style block shows nothing, never the CSS`() {
        val cut = "<html><head><style>body{font-family:Helvetica;color:#333;background:#fff"

        assertNull(preview(cut, mime = "text/html"))
    }

    /** `<script>` and `<head>`, not only `<style>`: a cut swallows whichever came first, and
     *  `htmlToText` removes none of them without their closing tag. Without this case, narrowing
     *  the rule to `(style)` alone leaves the suite green and the JavaScript on the screen. */
    @Test fun `an HTML fragment cut inside a script or a head shows neither`() {
        assertEquals("Hello", preview("<p>Hello</p><script>var a = 1; if (a < 2) { go(", mime = "text/html"))
        assertEquals("Hello", preview("<p>Hello</p><head><meta charset=\"utf-8\"><title>New", mime = "text/html"))
    }

    @Test fun `the text before a cut style block still shows`() {
        val cut = "<p>Sale ends today</p><style>.x{color:red;background"

        assertEquals("Sale ends today", preview(cut, mime = "text/html"))
    }

    @Test fun `an HTML fragment cut in the middle of a tag does not show the tag`() {
        val cut = "<p>Hello there</p><div class=\"container\" style=\"margin:0;padd"

        assertEquals("Hello there", preview(cut, mime = "text/html"))
    }

    @Test fun `HTML entities are decoded and block breaks kept`() {
        assertEquals(
            "Café &amp; co\nSecond line",
            preview("<p>Caf&eacute; &amp;amp; co</p><p>Second line", mime = "text/html"),
        )
    }

    /** A plain-text part is NOT flattened: an angle-bracketed URL is content, and htmlToText
     *  would delete it as a tag. */
    @Test fun `a plain-text part keeps what would look like markup`() {
        assertEquals(
            "See <https://example.org/a> and mail <bob@example.org>",
            preview("See <https://example.org/a> and mail <bob@example.org>"),
        )
    }

    // ---- nothing to show is an answer -----------------------------------------------------------

    @Test fun `an empty or blank part yields null, never an empty line`() {
        assertNull(preview(""))
        assertNull(preview("   \r\n\t  "))
        assertNull(preview("", mime = "text/html"))
        assertNull(preview("<div>\n  <span> </span>\n</div>", mime = "text/html"))
    }

    // ---- the shaping belongs to the notification, not here ---------------------------------------

    /**
     * THE LAYERING, pinned. `MailNotificationText.shapePreview` flattens the blanks and caps at
     */
    @Test fun `nothing is capped, flattened or elided here`() {
        val long = "x".repeat(1000)
        assertEquals(long, preview(long))
        assertEquals("line one\nline two", preview("line one\nline two"))
        assertEquals("  spaced  out ", preview("  spaced  out "))
    }
}
