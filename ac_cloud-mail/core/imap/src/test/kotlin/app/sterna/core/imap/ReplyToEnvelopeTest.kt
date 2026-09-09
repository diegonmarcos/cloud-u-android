package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * The envelope's `Reply-To` (index 4), read off a real socket.
 */
class ReplyToEnvelopeTest {

    /** The wire bytes of [text] in [charset], as the byte container a parsed token is. */
    private fun wire(text: String, charset: Charset): String =
        String(text.toByteArray(charset), Charsets.ISO_8859_1)

    /** An IMAP literal — the only shape RFC 3501 leaves a server for a non-ASCII envelope value. */
    private fun literal(value: String): String = "{${value.length}}\r\n$value"

    /**
     * One message fetched over a real socket, with each envelope address slot spelled out.
     * Defaults give four distinguishable addresses; a test overrides only the slot it is about.
     */
    private fun fetched(
        replyTo: String = "((\"Support\" NIL \"support\" \"masto.top\"))",
        from: String = "((\"Alex Rivera\" NIL \"alex.rivera\" \"masto.top\"))",
        // Slot 3, `sender`, is the neighbour BELOW reply-to, and it is the dangerous one: mailing
        // lists are exactly where it is filled (a bounce address) and where Reply-To may be
        // absent. Left NIL — as every fixture in this module had it — a reader that fell back to
        // slot 3 would look identical to a correct one.
        sender: String = "((\"Bounces\" NIL \"bounces\" \"masto.top\"))",
        to: String = "((\"Team\" NIL \"team\" \"masto.top\"))",
        cc: String = "((\"Copy Cat\" NIL \"copy\" \"masto.top\"))",
    ): ImapMessage? {
        val response = { tag: String ->
            "* 1 FETCH (UID 7 FLAGS (\\Seen) INTERNALDATE \"01-Jun-2026 10:00:00 +0000\" " +
                "ENVELOPE (\"Mon, 1 Jun 2026 10:00:00 +0000\" \"Hi\" " +
                "$from $sender $replyTo $to $cc NIL NIL \"<7@masto.top>\") " +
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
    fun `the reply-to address is read, with its display name`() {
        val message = fetched()

        assertEquals(
            listOf(ImapAddress(name = "Support", email = "support@masto.top")),
            message?.replyTo,
        )
    }

    /**
     * THE SLOT, not merely "an address". `cc` is the very next envelope field and carries an
     */
    @Test
    fun `the cc addresses are not mistaken for the reply-to`() {
        val message = fetched()

        assertEquals(listOf("support@masto.top"), message?.replyTo?.map { it.email })
        assertTrue(
            "cc (envelope index 6) must not leak into replyTo (index 4)",
            message?.replyTo.orEmpty().none { it.email == "copy@masto.top" },
        )
        assertTrue(
            "sender (envelope index 3) must not leak into replyTo (index 4)",
            message?.replyTo.orEmpty().none { it.email == "bounces@masto.top" },
        )
        // The neighbours are unchanged: nothing here was borrowed from them.
        assertEquals("alex.rivera@masto.top", message?.fromEmail)
        assertEquals(listOf("team@masto.top"), message?.to?.map { it.email })
    }

    /**
     * No `Reply-To` at all — the common case, and the pre-existing behaviour: an empty list.
     */
    @Test
    fun `a message without reply-to has an empty list, not a borrowed address`() {
        val message = fetched(replyTo = "NIL")

        assertEquals(emptyList<ImapAddress>(), message?.replyTo)
        assertEquals("alex.rivera@masto.top", message?.fromEmail)
        assertEquals(listOf("team@masto.top"), message?.to?.map { it.email })
    }

    /** A header may name several; the order it was written in is the order it is answered in. */
    @Test
    fun `several reply-to addresses keep their order`() {
        val message = fetched(
            replyTo = "((\"Support\" NIL \"support\" \"masto.top\")" +
                "(\"Billing\" NIL \"billing\" \"masto.top\"))",
        )

        assertEquals(
            listOf("support@masto.top", "billing@masto.top"),
            message?.replyTo?.map { it.email },
        )
        assertEquals(listOf("Support", "Billing"), message?.replyTo?.map { it.name })
    }

    /**
     * The address is not displayed and forgotten, it is REPLIED TO. An internationalised address
     */
    @Test
    fun `an EAI reply-to address is decoded, not left as octets`() {
        val message = fetched(
            replyTo = "((NIL NIL ${literal(wire("ответ", Charsets.UTF_8))} " +
                "${literal(wire("почта.рф", Charsets.UTF_8))}))",
        )

        assertEquals(listOf("ответ@почта.рф"), message?.replyTo?.map { it.email })
    }

    /** An RFC 2047 display name is decoded like every other envelope name. */
    @Test
    fun `an encoded-word display name is decoded`() {
        val message = fetched(
            replyTo = "((\"=?utf-8?B?U8OpY3VyaXTDqQ==?=\" NIL \"secu\" \"masto.top\"))",
        )

        assertEquals("Sécurité", message?.replyTo?.single()?.name)
        assertEquals("secu@masto.top", message?.replyTo?.single()?.email)
    }

    /** Group syntax: the delimiters carry no host and are skipped, as they are in `to`. */
    @Test
    fun `group syntax delimiters are skipped`() {
        val message = fetched(
            replyTo = "((NIL NIL \"the-list\" NIL)" +
                "(\"Support\" NIL \"support\" \"masto.top\")(NIL NIL NIL NIL))",
        )

        assertEquals(
            listOf(ImapAddress(name = "Support", email = "support@masto.top")),
            message?.replyTo,
        )
    }

    /** The shared fixture every other IMAP test fetches through carries it too. */
    @Test
    fun `the shared fetch fixture carries reply-to, and it is not the cc`() {
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

        assertEquals(listOf("support@masto.top"), message?.replyTo?.map { it.email })
    }
}
