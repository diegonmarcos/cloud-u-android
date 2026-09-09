package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/**
 * The whitespace that separates two adjacent RFC 2047 encoded-words (Codeberg #123): "Réunion de
 */
class EncodedWordSeparatorTest {

    /** [text] as one base64 encoded-word — the shape a mail client emits. */
    private fun b(text: String): String =
        "=?utf-8?B?${Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))}?="

    /** [text] as one quoted-printable encoded-word (`_` for space, as RFC 2047 §4.2 allows). */
    private fun q(text: String): String {
        val body = text.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
            val v = byte.toInt() and 0xFF
            when {
                v == 0x20 -> "_"
                v in 0x41..0x5A || v in 0x61..0x7A || v in 0x30..0x39 -> v.toChar().toString()
                else -> "=%02X".format(v)
            }
        }
        return "=?utf-8?Q?$body?="
    }

    // ---- The rule ----------------------------------------------------------------------------

    /** The reported subject, cut exactly where the reporter's sender cut it. */
    @Test fun `a space between two encoded words is not text`() {
        assertEquals("Réunion de rentrée", decodeWords("${b("Réunion de ren")} ${b("trée")}"))
        assertEquals("Réunion de rentrée", decodeWords("${q("Réunion de ren")} ${q("trée")}"))
    }

    /** The same cut carried by a folded header line, which is how a long subject really travels. */
    @Test fun `a folding CRLF between two encoded words is not text`() {
        assertEquals("Réunion de rentrée", decodeWords("${b("Réunion de ren")}\r\n ${b("trée")}"))
        assertEquals("Réunion de rentrée", decodeWords("${b("Réunion de ren")}\r\n\t${b("trée")}"))
        // Folding without the continuation space is malformed, but a lenient reader still sees
        // linear whitespace and nothing else, so the rule applies unchanged.
        assertEquals("Réunion de rentrée", decodeWords("${b("Réunion de ren")}\r\n${b("trée")}"))
        assertEquals("Réunion de rentrée", decodeWords("${b("Réunion de ren")}\t${b("trée")}"))
    }

    /** A long run cut into three, and the two gaps both disappear. */
    @Test fun `three encoded words in a row join into one string`() {
        assertEquals(
            "Réunion de rentrée du conseil pédagogique",
            decodeWords("${b("Réunion de rentrée ")} ${b("du conseil ")}\r\n ${b("pédagogique")}"),
        )
    }

    // ---- The guards, which is where a fix like this breaks something else ---------------------

    /** THE WITNESS for plain text: a gap that is not only whitespace is not a separator. */
    @Test fun `text between two encoded words survives with its spaces`() {
        assertEquals("Réunion de rentrée", decodeWords("${b("Réunion")} de ${b("rentrée")}"))
        assertEquals("Réunion - rentrée", decodeWords("${b("Réunion")} - ${b("rentrée")}"))
        assertEquals("Réunion, rentrée", decodeWords("${b("Réunion")}, ${b("rentrée")}"))
    }

    /** THE WITNESS on the other side: whitespace between a word and plain text is a real space. */
    @Test fun `whitespace between an encoded word and plain text survives`() {
        assertEquals("Réunion de rentrée", decodeWords("${b("Réunion")} de rentrée"))
        assertEquals("Réunion de rentrée", decodeWords("Réunion de ${b("rentrée")}"))
        assertEquals("Re: Réunion", decodeWords("Re: ${b("Réunion")}"))
    }

    /** A header with no encoded-word at all is not this function's business. */
    @Test fun `a header without encoded words is untouched`() {
        assertEquals("Réunion de rentrée", decodeWords("Réunion de rentrée"))
        assertEquals("spaced  out   subject", decodeWords("spaced  out   subject"))
        assertEquals("", decodeWords(""))
        assertNull(decodeWords(null))
    }

    /**
     * A space the sender ENCODED is content and has nothing to do with §6.2. Asserted with a
     * doubled space as the sentinel: keeping the separator too would give "Hello  world".
     */
    @Test fun `a space inside an encoded word is content and survives`() {
        assertEquals("Hello world", decodeWords("${b("Hello ")} ${b("world")}"))
        assertEquals("Hello world", decodeWords("${b("Hello")} ${b(" world")}"))
        assertEquals("Réunion de rentrée", decodeWords("${b("Réunion")} ${b(" de rentrée")}"))
    }

    /**
     * A word whose payload is not decodable survives verbatim (that fallback predates this fix
     */
    @Test fun `a word that will not decode keeps its text and still separates`() {
        val broken = "=?utf-8?B?A?=" // a lone base64 char: not a decodable unit
        assertEquals(broken, decodeWords(broken))
        assertEquals("Ré${broken}nion", decodeWords("${b("Ré")} $broken ${b("nion")}"))
        // And the neighbours around a broken word are still joined to each other.
        assertEquals("Réunion", decodeWords("${b("Réu")} ${b("nion")}"))
    }

    /** Leading and trailing whitespace is trimmed, exactly as it was before this change. */
    @Test fun `the edges of the header are trimmed as before`() {
        assertEquals("Réunion", decodeWords("  ${b("Réunion")}  "))
        assertEquals("Réunion", decodeWords("\r\n ${b("Réu")} ${b("nion")}\r\n"))
        assertEquals("Réunion", decodeWords(b(" Réunion ")))
    }

    /** THE WITNESS for the ordinary case: one word, both encodings, still decodes as it did. */
    @Test fun `a single encoded word is unaffected`() {
        assertEquals("Réunion", decodeWords("=?utf-8?B?UsOpdW5pb24=?="))
        assertEquals("Réunion", decodeWords("=?iso-8859-1?Q?R=E9union?="))
        assertEquals("Réunion de rentrée", decodeWords("=?utf-8?Q?R=C3=A9union_de_rentr=C3=A9e?="))
    }

    // ---- The three call shapes ----------------------------------------------------------------

    /** An IMAP literal token: `{n}` then the bytes — the only way a folded value reaches us. */
    private fun literal(value: String): String = "{${value.length}}\r\n$value"

    private fun fetchedMessage(subject: String, fromName: String): ImapMessage? =
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 1)
                line.startsWith("UID FETCH") ->
                    "* 1 FETCH (UID 7 FLAGS (\\Seen) INTERNALDATE \"01-Jun-2026 10:00:00 +0000\" " +
                        "ENVELOPE (\"Mon, 1 Jun 2026 10:00:00 +0000\" ${literal(subject)} " +
                        "((${literal(fromName)} NIL \"alex.rivera\" \"masto.top\")) " +
                        "NIL NIL NIL NIL NIL NIL \"<7@masto.top>\") " +
                        "BODYSTRUCTURE (\"text\" \"plain\" (\"charset\" \"utf-8\") NIL NIL " +
                        "\"7bit\" 12 1))\r\n$tag OK fetched\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            server.session().use { session ->
                session.select("INBOX")
                session.fetchByUid(7L)
            }
        }

    /** Shape 1 and 2, off a real socket: the subject and the sender's display name. */
    @Test fun `a folded subject and display name arrive as single words`() {
        val message = fetchedMessage(
            subject = "${q("Réunion de ren")}\r\n ${q("trée")}",
            fromName = "${b("Amélie Dupont-Lef")} ${b("èvre")}",
        )
        assertEquals("Réunion de rentrée", message?.subject)
        assertEquals("Amélie Dupont-Lefèvre", message?.fromName)
    }

    /** Shape 3: an attachment filename, split the same way (Codeberg #101 goes through here). */
    @Test fun `a split filename is joined`() {
        val disposition = "attachment; filename=\"${b("Rapport-annuel-péd")} ${b("agogique.pdf")}\""
        val source = """
            From: a@b.c
            Content-Type: multipart/mixed; boundary="B"

            --B
            Content-Type: text/plain

            hello
            --B
            Content-Type: application/pdf
            Content-Disposition: $disposition
            Content-Transfer-Encoding: base64

            AAAA
            --B--
        """.trimIndent().replace("\n", "\r\n")
        assertEquals(
            "Rapport-annuel-pédagogique.pdf",
            MimeParser.parseBody(source).attachments.firstOrNull()?.name,
        )
    }
}
