package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * The envelope's `Cc` (index 6) and `Bcc` (index 7), read off a real socket.
 */
class CopiesEnvelopeTest {

    /** The wire bytes of [text] in [charset], as the byte container a parsed token is. */
    private fun wire(text: String, charset: Charset): String =
        String(text.toByteArray(charset), Charsets.ISO_8859_1)

    /** An IMAP literal — the only shape RFC 3501 leaves a server for a non-ASCII envelope value. */
    private fun literal(value: String): String = "{${value.length}}\r\n$value"

    /**
     * One message fetched over a real socket, each envelope address slot spelled out and distinct.
     * A test overrides only the slot it is about.
     */
    private fun fetched(
        from: String = "((\"Alex Rivera\" NIL \"alex.rivera\" \"masto.top\"))",
        sender: String = "((\"Bounces\" NIL \"bounces\" \"masto.top\"))",
        replyTo: String = "((\"Support\" NIL \"support\" \"masto.top\"))",
        to: String = "((\"Team\" NIL \"team\" \"masto.top\"))",
        cc: String = "((\"Copy Cat\" NIL \"copy\" \"masto.top\"))",
        bcc: String = "((\"Hidden One\" NIL \"hidden\" \"masto.top\"))",
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
    fun `the bcc addresses are read into their own list`() {
        val message = fetched()

        assertEquals(
            listOf(ImapAddress(name = "Hidden One", email = "hidden@masto.top")),
            message?.bcc,
        )
    }

    /**
     * THE SLOT. Index 7 is the blind copy: if it is what the visible `Cc` field is filled from,
     */
    @Test
    fun `the bcc is not read as the cc, and the cc is not read as the bcc`() {
        val message = fetched()

        assertEquals(listOf("copy@masto.top"), message?.cc?.map { it.email })
        assertEquals(listOf("hidden@masto.top"), message?.bcc?.map { it.email })
        assertTrue(
            "bcc (envelope index 7) must never surface in cc (index 6)",
            message?.cc.orEmpty().none { it.email == "hidden@masto.top" },
        )
        assertTrue(
            "to (envelope index 5) must not leak into cc (index 6)",
            message?.cc.orEmpty().none { it.email == "team@masto.top" },
        )
        // The neighbours are unchanged: nothing here was borrowed from them.
        assertEquals("alex.rivera@masto.top", message?.fromEmail)
        assertEquals(listOf("team@masto.top"), message?.to?.map { it.email })
        assertEquals(listOf("support@masto.top"), message?.replyTo?.map { it.email })
    }

    /**
     * A copied address is not shown and forgotten: it is prefilled as a recipient of a reply-all
     */
    @Test
    fun `an EAI cc address is decoded on both halves, not left as octets`() {
        val message = fetched(
            cc = "((NIL NIL ${literal(wire("копия", Charsets.UTF_8))} " +
                "${literal(wire("почта.рф", Charsets.UTF_8))}))",
        )

        assertEquals(listOf("копия@почта.рф"), message?.cc?.map { it.email })
    }

    /** Same treatment for the blind copy: one code path, one decoding. */
    @Test
    fun `an EAI bcc address is decoded on both halves`() {
        val message = fetched(
            bcc = "((NIL NIL ${literal(wire("скрытый", Charsets.UTF_8))} " +
                "${literal(wire("почта.рф", Charsets.UTF_8))}))",
        )

        assertEquals(listOf("скрытый@почта.рф"), message?.bcc?.map { it.email })
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

    /** Several copied parties: the order they were written in is the order they are answered in. */
    @Test
    fun `several cc addresses keep their order`() {
        val message = fetched(
            cc = "((\"Copy Cat\" NIL \"copy\" \"masto.top\")" +
                "(\"Billing\" NIL \"billing\" \"masto.top\"))",
        )

        assertEquals(listOf("copy@masto.top", "billing@masto.top"), message?.cc?.map { it.email })
        assertEquals(listOf("Copy Cat", "Billing"), message?.cc?.map { it.name })
    }

    /** No copies at all — the common case: empty lists, and no address borrowed from a neighbour. */
    @Test
    fun `a message without copies has empty lists, not borrowed addresses`() {
        val message = fetched(cc = "NIL", bcc = "NIL")

        assertEquals(emptyList<ImapAddress>(), message?.cc)
        assertEquals(emptyList<ImapAddress>(), message?.bcc)
        assertEquals(listOf("team@masto.top"), message?.to?.map { it.email })
        assertEquals(listOf("support@masto.top"), message?.replyTo?.map { it.email })
    }

    /**
     * Slot 6 NIL, slot 7 filled — the shape a fallback survives. Every other fixture here fills
     */
    @Test
    fun `a bcc with no cc is not read as the cc`() {
        val message = fetched(cc = "NIL")

        assertEquals(emptyList<ImapAddress>(), message?.cc)
        assertEquals(listOf("hidden@masto.top"), message?.bcc?.map { it.email })
        assertEquals(listOf("team@masto.top"), message?.to?.map { it.email })
    }

    /** Group syntax: the delimiters carry no host and are skipped, as they are in `to`. */
    @Test
    fun `group syntax delimiters are skipped in the copies`() {
        val message = fetched(
            cc = "((NIL NIL \"the-list\" NIL)" +
                "(\"Copy Cat\" NIL \"copy\" \"masto.top\")(NIL NIL NIL NIL))",
        )

        assertEquals(
            listOf(ImapAddress(name = "Copy Cat", email = "copy@masto.top")),
            message?.cc,
        )
    }

    /** The shared fixture every other IMAP test fetches through carries a cc, and it is slot 6. */
    @Test
    fun `the shared fetch fixture carries the cc, and it is not the to`() {
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
        assertEquals(listOf("team@masto.top"), message?.to?.map { it.email })
    }
}
