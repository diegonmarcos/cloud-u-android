package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * The envelope's `Cc` (index 6) and `Bcc` (index 7), read off a real socket.
 */
class CcBccEnvelopeTest {

    /** The wire bytes of [text] in [charset], as the byte container a parsed token is. */
    private fun wire(text: String, charset: Charset): String =
        String(text.toByteArray(charset), Charsets.ISO_8859_1)

    /** An IMAP literal — the only shape RFC 3501 leaves a server for a non-ASCII envelope value. */
    private fun literal(value: String): String = "{${value.length}}\r\n$value"

    /**
     * One message fetched over a real socket, with each envelope address slot spelled out.
     */
    private fun fetched(
        cc: String = "((\"Copy Cat\" NIL \"copy\" \"masto.top\"))",
        bcc: String = "((\"Blind Cat\" NIL \"blind\" \"masto.top\"))",
        from: String = "((\"Alex Rivera\" NIL \"alex.rivera\" \"masto.top\"))",
        sender: String = "((\"Bounces\" NIL \"bounces\" \"masto.top\"))",
        replyTo: String = "((\"Support\" NIL \"support\" \"masto.top\"))",
        to: String = "((\"Team\" NIL \"team\" \"masto.top\"))",
    ): ImapMessage? {
        val response = { tag: String ->
            "* 1 FETCH (UID 7 FLAGS (\\Seen) INTERNALDATE \"01-Jun-2026 10:00:00 +0000\" " +
                "ENVELOPE (\"Mon, 1 Jun 2026 10:00:00 +0000\" \"Hi\" " +
                "$from $sender $replyTo $to $cc $bcc NIL \"<7@masto.top>\") " +
                "BODYSTRUCTURE (\"text\" \"plain\" (\"charset\" \"utf-8\") NIL NIL \"7bit\" 12 1))\r\n" +
                "$tag OK fetched\r\n"
        }
        return FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 1)
                line.startsWith("UID FETCH") -> response(tag)
                else -> ok(tag)
            }
        }.use { server ->
            server.session().use { session ->
                session.select("INBOX")
                session.fetchByUid(7L)
            }
        }
    }

    @Test
    fun `the cc addresses are read, with their display names`() {
        val message = fetched()

        assertEquals(
            listOf(ImapAddress(name = "Copy Cat", email = "copy@masto.top")),
            message?.cc,
        )
    }

    @Test
    fun `the bcc addresses are read, with their display names`() {
        val message = fetched()

        assertEquals(
            listOf(ImapAddress(name = "Blind Cat", email = "blind@masto.top")),
            message?.bcc,
        )
    }

    /**
     * THE SLOT, not merely "an address". `to` is the envelope field immediately below cc, and it
     */
    @Test
    fun `the to addresses are not mistaken for the cc`() {
        val message = fetched()

        assertEquals(listOf("copy@masto.top"), message?.cc?.map { it.email })
        assertTrue(
            "to (envelope index 5) must not leak into cc (index 6)",
            message?.cc.orEmpty().none { it.email == "team@masto.top" },
        )
        assertTrue(
            "bcc (envelope index 7) must not leak into cc (index 6)",
            message?.cc.orEmpty().none { it.email == "blind@masto.top" },
        )
        // The neighbours are unchanged: nothing here was borrowed from them.
        assertEquals(listOf("team@masto.top"), message?.to?.map { it.email })
        assertEquals(listOf("support@masto.top"), message?.replyTo?.map { it.email })
        assertEquals("alex.rivera@masto.top", message?.fromEmail)
        assertEquals("<7@masto.top>", message?.messageId)
    }

    /** And the same one slot down: bcc is index 7, not the cc above it. */
    @Test
    fun `the cc addresses are not mistaken for the bcc`() {
        val message = fetched()

        assertEquals(listOf("blind@masto.top"), message?.bcc?.map { it.email })
        assertTrue(
            "cc (envelope index 6) must not leak into bcc (index 7)",
            message?.bcc.orEmpty().none { it.email == "copy@masto.top" },
        )
    }

    /**
     * No `Cc` and no `Bcc` — the common case, and the one that MUST be an empty list rather than
     */
    @Test
    fun `a message without cc or bcc has empty lists, not borrowed addresses`() {
        val message = fetched(cc = "NIL", bcc = "NIL")

        assertEquals(emptyList<ImapAddress>(), message?.cc)
        assertEquals(emptyList<ImapAddress>(), message?.bcc)
        assertEquals(listOf("team@masto.top"), message?.to?.map { it.email })
        assertEquals("<7@masto.top>", message?.messageId)
    }

    /** A header may name several; all of them are the addressing, so all of them are read. */
    @Test
    fun `several cc addresses are all read, in order`() {
        val message = fetched(
            cc = "((\"Jordan Lee\" NIL \"jordan.lee\" \"masto.top\")" +
                "(\"Sam Diaz\" NIL \"sam.diaz\" \"masto.top\"))",
        )

        assertEquals(
            listOf("jordan.lee@masto.top", "sam.diaz@masto.top"),
            message?.cc?.map { it.email },
        )
    }

    /**
     * An internationalised address (RFC 6531, which Stalwart speaks) can only arrive as a literal.
     */
    @Test
    fun `an EAI cc address is decoded, not left as octets`() {
        val message = fetched(
            cc = "((NIL NIL ${literal(wire("копия", Charsets.UTF_8))} " +
                "${literal(wire("почта.рф", Charsets.UTF_8))}))",
        )

        assertEquals(listOf("копия@почта.рф"), message?.cc?.map { it.email })
    }

    /** An RFC 2047 display name is decoded like every other envelope name. */
    @Test
    fun `an encoded-word cc display name is decoded`() {
        val message = fetched(
            cc = "((\"=?utf-8?B?U8OpY3VyaXTDqQ==?=\" NIL \"secu\" \"masto.top\"))",
        )

        assertEquals("Sécurité", message?.cc?.single()?.name)
        assertEquals("secu@masto.top", message?.cc?.single()?.email)
    }

    /** Group syntax: the delimiters carry no host and are skipped, as they are in `to`. */
    @Test
    fun `group syntax delimiters are skipped`() {
        val message = fetched(
            cc = "((NIL NIL \"the-list\" NIL)" +
                "(\"Copy Cat\" NIL \"copy\" \"masto.top\")(NIL NIL NIL NIL))",
        )

        assertEquals(
            listOf(ImapAddress(name = "Copy Cat", email = "copy@masto.top")),
            message?.cc,
        )
    }

    /** The shared fetch fixture carries a cc, and it is not the to beside it. */
    @Test
    fun `the shared fetch fixture carries cc, and it is not the to`() {
        val message = FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 1)
                line.startsWith("UID FETCH") -> fetchResponse(tag, listOf(7L)) { false }
                else -> ok(tag)
            }
        }.use { server ->
            server.session().use { session ->
                session.select("INBOX")
                session.fetchByUid(7L)
            }
        }

        assertEquals(listOf("copy@masto.top"), message?.cc?.map { it.email })
        // That fixture leaves bcc NIL, as a server usually does.
        assertEquals(emptyList<ImapAddress>(), message?.bcc)
    }
}
