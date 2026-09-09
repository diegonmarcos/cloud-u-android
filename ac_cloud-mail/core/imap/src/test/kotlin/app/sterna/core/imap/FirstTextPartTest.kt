package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * WHICH SECTION a notification preview is about to read out of somebody's mailbox, decided from the
 */
class FirstTextPartTest {

    /** A BODYSTRUCTURE as a server writes it, parsed by the shipped parser. */
    private fun structure(bodystructure: String): Any? =
        ImapParser(ByteArrayInputStream("$bodystructure\r\n".toByteArray(Charsets.ISO_8859_1)))
            .readResponse()
            .firstOrNull()

    private fun textPart(bodystructure: String): ImapTextPart? = firstTextPart(structure(bodystructure))

    private companion object {
        const val PLAIN = """("text" "plain" ("charset" "iso-8859-1") NIL NIL "quoted-printable" 100 5)"""
        const val HTML = """("text" "html" ("charset" "utf-8") NIL NIL "base64" 400 9)"""
        const val PDF = """("application" "pdf" ("name" "f.pdf") NIL NIL "base64" 900 NIL """ +
            """("attachment" ("filename" "f.pdf")) NIL)"""
        const val PNG = """("image" "png" ("name" "logo.png") NIL "<logo>" "base64" 300 NIL)"""

        /** A text part that is a FILE: an `attachment` disposition and a filename on it. Note the
         *  extra `NIL` at index 8 — a text part carries `lines` at 7, so its md5 is at 8 and its
         *  disposition at 9. */
        const val PLAIN_ATTACHED = """("text" "plain" ("charset" "utf-8") NIL NIL "base64" 200 4 NIL """ +
            """("attachment" ("filename" "notes.txt")) NIL)"""

        /** The older way of saying the same thing: no disposition at all, a `name` on the type. */
        const val PLAIN_NAMED = """("text" "plain" ("charset" "utf-8" "name" "log.txt") NIL NIL "7bit" 200 4)"""

        /** An invitation. MimeParser captures this as `invite.ics`, never as the message's text. */
        const val CALENDAR = """("text" "calendar" ("charset" "utf-8" "method" "REQUEST") NIL NIL "7bit" 500 20)"""

        /** The body, carrying the disposition a sender legitimately puts on a body. */
        const val PLAIN_INLINE = """("text" "plain" ("charset" "iso-8859-1") NIL NIL "quoted-printable" 100 5 NIL """ +
            """("inline" NIL) NIL)"""
    }

    // ---- a text part is not automatically the message -------------------------------------------

    /**
     * THE MUTATION CASE, and the one the app already answers elsewhere: `MimeParser.walk` calls a
     */
    @Test fun `a text part that is an attachment is not the message`() {
        assertEquals(
            ImapTextPart(section = "2", mime = "text/plain", encoding = "quoted-printable", charset = "iso-8859-1"),
            textPart("""($PLAIN_ATTACHED$PLAIN "mixed")"""),
        )
    }

    @Test fun `a text part named by its content type is a file too`() {
        assertEquals(
            ImapTextPart(section = "2", mime = "text/plain", encoding = "quoted-printable", charset = "iso-8859-1"),
            textPart("""($PLAIN_NAMED$PLAIN "mixed")"""),
        )
    }

    @Test fun `a message whose only text part is attached has no preview`() {
        assertNull(textPart("""($PDF$PLAIN_ATTACHED "mixed")"""))
        assertNull(textPart(PLAIN_NAMED))
    }

    /** An invitation's payload is `BEGIN:VCALENDAR VERSION:2.0 PRODID:…`. It is text, and it is
     *  not something to read on a lock screen. */
    @Test fun `a calendar invitation is not the message text`() {
        assertNull(textPart(CALENDAR))
        assertEquals("2", textPart("""($CALENDAR$PLAIN "mixed")""")?.section)
    }

    /** THE INVERSE WITNESS, without which "refuse anything carrying a disposition" would pass every
     *  case above — and that rule would leave a great many ordinary messages with no preview. */
    @Test fun `an inline disposition on the body changes nothing`() {
        assertEquals(
            ImapTextPart(section = "1", mime = "text/plain", encoding = "quoted-printable", charset = "iso-8859-1"),
            textPart(PLAIN_INLINE),
        )
    }

    // ---- the shapes real mail arrives in -------------------------------------------------------

    @Test fun `a message that is one text part is section 1`() {
        assertEquals(
            ImapTextPart(section = "1", mime = "text/plain", encoding = "7bit", charset = "utf-8"),
            textPart("""("text" "plain" ("charset" "utf-8") NIL NIL "7bit" 12 1)"""),
        )
    }

    @Test fun `an HTML-only message keeps its subtype, so the markup can be flattened`() {
        assertEquals(
            ImapTextPart(section = "1", mime = "text/html", encoding = "base64", charset = "utf-8"),
            textPart(HTML),
        )
    }

    @Test fun `in a plain-then-HTML alternative it is the plain part`() {
        assertEquals(
            ImapTextPart(section = "1", mime = "text/plain", encoding = "quoted-printable", charset = "iso-8859-1"),
            textPart("""($PLAIN$HTML "alternative")"""),
        )
    }

    /** THE MUTATION CASE. "The first part" here is a PDF: its base64 is what would be shown. */
    @Test fun `a mail whose first part is an attachment still finds its text`() {
        assertEquals(
            ImapTextPart(section = "2", mime = "text/plain", encoding = "quoted-printable", charset = "iso-8859-1"),
            textPart("""($PDF$PLAIN "mixed")"""),
        )
    }

    /** THE MUTATION CASE, one level down: the text of the commonest mail there is sits at 1.1,
     *  and "the first part" of the message is a multipart — whose own first field is not a type. */
    @Test fun `the text of a nested alternative is found at its own section`() {
        assertEquals(
            ImapTextPart(section = "1.1", mime = "text/plain", encoding = "quoted-printable", charset = "iso-8859-1"),
            textPart("""(($PLAIN$HTML "alternative")$PDF "mixed")"""),
        )
    }

    @Test fun `a deeper nesting is numbered the way MimeParser numbers it`() {
        // multipart/mixed { application/pdf, multipart/related { image/png, text/html } }
        assertEquals(
            ImapTextPart(section = "2.2", mime = "text/html", encoding = "base64", charset = "utf-8"),
            textPart("""($PDF($PNG$HTML "related") "mixed")"""),
        )
    }

    @Test fun `an inline image before the body does not take its place`() {
        assertEquals(
            ImapTextPart(section = "2", mime = "text/html", encoding = "base64", charset = "utf-8"),
            textPart("""($PNG$HTML "related")"""),
        )
    }

    // ---- nothing to preview is an answer, not a failure ----------------------------------------

    @Test fun `a message with no text part at all has no preview to fetch`() {
        assertNull(textPart("""($PDF$PNG "mixed")"""))
        assertNull(textPart("""("application" "pdf" ("name" "f.pdf") NIL NIL "base64" 900 NIL)"""))
    }

    @Test fun `a server that sent no structure yields nothing, and does not throw`() {
        assertNull(firstTextPart(null))
        assertNull(firstTextPart("BODYSTRUCTURE"))
        assertNull(firstTextPart(emptyList<Any?>()))
        assertNull(textPart("""(NIL NIL)"""))
    }

    // ---- what travels with the section ---------------------------------------------------------

    @Test fun `a part that declares no charset says so, rather than inventing one`() {
        // Null here, not "utf-8": the fallback belongs to `MimeParser.charsetNamed`, in one place.
        assertEquals(
            ImapTextPart(section = "1", mime = "text/plain", encoding = "8bit", charset = null),
            textPart("""("text" "plain" NIL NIL NIL "8bit" 12 1)"""),
        )
    }

    @Test fun `the announced charset and encoding travel with the section, whatever their case`() {
        assertEquals(
            ImapTextPart(section = "1", mime = "text/plain", encoding = "quoted-printable", charset = "KOI8-R"),
            textPart("""("TEXT" "PLAIN" ("CHARSET" "KOI8-R") NIL NIL "QUOTED-PRINTABLE" 100 5)"""),
        )
    }

    @Test fun `a parameter list naming other things still yields the charset`() {
        assertEquals(
            "utf-8",
            textPart("""("text" "plain" ("format" "flowed" "charset" "utf-8" "delsp" "yes") NIL NIL "7bit" 9 1)""")
                ?.charset,
        )
    }

    // ---- the same decision, off a real socket ---------------------------------------------------

    /**
     * The wiring, end to end: the structure is asked for by every envelope fetch already, so the
     */
    @Test fun `an envelope fetch carries the text part, with no further command`() {
        val server = FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 1)
                line.startsWith("UID FETCH") ->
                    "* 1 FETCH (UID 7 FLAGS (\\Seen) INTERNALDATE \"01-Jun-2026 10:00:00 +0000\" " +
                        "ENVELOPE (\"Mon, 1 Jun 2026 10:00:00 +0000\" \"Lunch\" " +
                        "((\"Alex\" NIL \"alex\" \"masto.top\")) NIL NIL NIL NIL NIL NIL \"<7@masto.top>\") " +
                        "BODYSTRUCTURE ($PDF$PLAIN \"mixed\"))\r\n$tag OK fetched\r\n"
                else -> ok(tag)
            }
        }
        val message = server.use {
            it.session().use { session ->
                session.select("INBOX")
                session.fetchByUid(7L)
            }
        }

        assertEquals(
            ImapTextPart(section = "2", mime = "text/plain", encoding = "quoted-printable", charset = "iso-8859-1"),
            message?.textPart,
        )
        assertEquals("Lunch", message?.subject)
        assertEquals(true, message?.hasAttachment)
        assertEquals(
            // Every session opens by asking whether the server knows the RFC 2971 ID
            // command (#173); this fixture answers without it, so nothing is named.
            listOf(
                "CAPABILITY",
                "SELECT \"INBOX\"",
                "UID FETCH 7 (UID FLAGS INTERNALDATE ENVELOPE BODYSTRUCTURE BODY.PEEK[HEADER.FIELDS (REFERENCES)])",
                "LOGOUT",
            ),
            server.issued(),
        )
    }
}
