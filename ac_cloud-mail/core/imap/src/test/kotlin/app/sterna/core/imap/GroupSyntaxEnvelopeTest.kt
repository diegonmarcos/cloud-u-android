package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RFC 5322 group syntax in an envelope address slot — `Cc: undisclosed-recipients:;` and its
 */
class GroupSyntaxEnvelopeTest {

    /** `undisclosed-recipients:;` as a server hands it over: a group start, then a group end. */
    private val emptyGroup = "((NIL NIL \"undisclosed-recipients\" NIL)(NIL NIL NIL NIL))"

    /** What an empty group must become: a named destination that names nobody. */
    private val undisclosed = ImapAddress(name = "undisclosed-recipients", email = null)

    /**
     * One message fetched over a real socket, each envelope address slot spelled out and
     * distinct. A test overrides only the slot it is about.
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

    // ---- the four slots, over the wire ----

    @Test
    fun `an empty group in cc becomes one named entry with no address`() {
        val message = fetched(cc = emptyGroup)

        assertEquals(listOf(undisclosed), message?.cc)
        assertEquals(listOf("hidden@masto.top"), message?.bcc?.map { it.email })
        assertEquals(listOf("team@masto.top"), message?.to?.map { it.email })
        assertEquals(listOf("support@masto.top"), message?.replyTo?.map { it.email })
    }

    @Test
    fun `an empty group in bcc becomes one named entry with no address`() {
        val message = fetched(bcc = emptyGroup)

        assertEquals(listOf(undisclosed), message?.bcc)
        assertEquals(listOf("copy@masto.top"), message?.cc?.map { it.email })
        assertEquals(listOf("team@masto.top"), message?.to?.map { it.email })
    }

    @Test
    fun `an empty group in to becomes one named entry with no address`() {
        val message = fetched(to = emptyGroup)

        assertEquals(listOf(undisclosed), message?.to)
        assertEquals(listOf("copy@masto.top"), message?.cc?.map { it.email })
        assertEquals(listOf("support@masto.top"), message?.replyTo?.map { it.email })
    }

    @Test
    fun `an empty group in reply-to becomes one named entry with no address`() {
        val message = fetched(replyTo = emptyGroup)

        assertEquals(listOf(undisclosed), message?.replyTo)
        assertEquals(listOf("team@masto.top"), message?.to?.map { it.email })
        assertEquals(listOf("copy@masto.top"), message?.cc?.map { it.email })
    }

    /** A group with members is carried BY its members: the delimiters add nothing. */
    @Test
    fun `a group with members is unchanged, the group name does not appear`() {
        val message = fetched(
            cc = "((NIL NIL \"the-list\" NIL)" +
                "(NIL NIL \"membre.un\" \"masto.top\")" +
                "(NIL NIL \"membre.deux\" \"masto.top\")(NIL NIL NIL NIL))",
        )

        assertEquals(
            listOf(
                ImapAddress(name = null, email = "membre.un@masto.top"),
                ImapAddress(name = null, email = "membre.deux@masto.top"),
            ),
            message?.cc,
        )
    }

    @Test
    fun `an empty group beside an ordinary address keeps both, in order`() {
        val message = fetched(
            cc = "((NIL NIL \"undisclosed-recipients\" NIL)(NIL NIL NIL NIL)" +
                "(\"Copy Cat\" NIL \"copy\" \"masto.top\"))",
        )

        assertEquals(
            listOf(undisclosed, ImapAddress(name = "Copy Cat", email = "copy@masto.top")),
            message?.cc,
        )
    }

    @Test
    fun `two empty groups in one field give two entries`() {
        val message = fetched(
            cc = "((NIL NIL \"undisclosed-recipients\" NIL)(NIL NIL NIL NIL)" +
                "(NIL NIL \"the-list\" NIL)(NIL NIL NIL NIL))",
        )

        assertEquals(
            listOf(undisclosed, ImapAddress(name = "the-list", email = null)),
            message?.cc,
        )
    }

    /**
     * A group start with nothing after it: no member follows, so the name is carried.
     */
    @Test
    fun `a group start with no end is carried as a name`() {
        val message = fetched(
            cc = "((\"Copy Cat\" NIL \"copy\" \"masto.top\")(NIL NIL \"the-list\" NIL))",
        )

        assertEquals(
            listOf(
                ImapAddress(name = "Copy Cat", email = "copy@masto.top"),
                ImapAddress(name = "the-list", email = null),
            ),
            message?.cc,
        )
    }

    /** The same shape alone in the slot. Hypothetical too: our own round trip now writes
     *  `undisclosed-recipients:;` and gets a start AND an end back. */
    @Test
    fun `a cc that is a bare group start alone reopens with its name`() {
        val message = fetched(cc = "((NIL NIL \"undisclosed-recipients\" NIL))")

        assertEquals(listOf(undisclosed), message?.cc)
        assertEquals(listOf("hidden@masto.top"), message?.bcc?.map { it.email })
    }

    /** A group name is header text: it arrives as an encoded-word and comes out decoded. */
    @Test
    fun `an encoded-word group name is decoded`() {
        val message = fetched(
            cc = "((NIL NIL \"=?utf-8?B?w4lxdWlwZSBTw6ljdXJpdMOp?=\" NIL)(NIL NIL NIL NIL))",
        )

        // The name of a group lives in the MAILBOX field (index 2); index 0 is NIL, which is
        // why the field was lost in the first place. It gets decodeWords like any other display
        // name — decodeHeaderBytes alone would hand back the raw =?utf-8?B?…?= to the composer.
        assertEquals(
            listOf(ImapAddress(name = "Équipe Sécurité", email = null)),
            message?.cc,
        )
    }

    // ---- the decision itself, run rather than read ----
    //
    // The socket cases above prove the four slots are wired to it; these run it on the entry
    // lists a parsed ENVELOPE is made of. RFC 3501 address = (name adl mailbox host).

    private fun groupStart(name: String?) = listOf<Any?>(null, null, name, null)
    private val groupEnd = listOf<Any?>(null, null, null, null)
    private fun mailbox(name: String?, box: String, host: String) =
        listOf<Any?>(name, null, box, host)

    @Test
    fun `pure - an empty group yields exactly one named entry`() {
        assertEquals(
            listOf(ImapAddress(name = "undisclosed-recipients", email = null)),
            envelopeAddresses(listOf(groupStart("undisclosed-recipients"), groupEnd)),
        )
    }

    @Test
    fun `pure - a group with a member yields the member alone`() {
        assertEquals(
            listOf(ImapAddress(name = "Member", email = "membre@masto.top")),
            envelopeAddresses(
                listOf(groupStart("the-list"), mailbox("Member", "membre", "masto.top"), groupEnd),
            ),
        )
    }

    @Test
    fun `pure - a group start at the end of the list yields its name`() {
        assertEquals(
            listOf(ImapAddress(name = "the-list", email = null)),
            envelopeAddresses(listOf(groupStart("the-list"))),
        )
    }

    @Test
    fun `pure - a group start followed by a name-only entry is dropped`() {
        // `Cc: the-list: "Nobody";` — a member can be a display name with no address at all.
        // A rule that only looked for the `(NIL NIL NIL NIL)` end marker would emit the group
        // name too, and the field would carry a recipient nobody wrote.
        assertEquals(
            listOf(ImapAddress(name = "Nobody", email = null)),
            envelopeAddresses(
                listOf(groupStart("the-list"), listOf<Any?>("Nobody", null, null, null)),
            ),
        )
    }

    @Test
    fun `pure - a group end on its own yields nothing`() {
        assertEquals(emptyList<ImapAddress>(), envelopeAddresses(listOf(groupEnd)))
    }

    @Test
    fun `pure - two group ends in a row yield nothing`() {
        // The guard that drops an entry with neither an address nor a name is only NARROWED
        // by the empty group, never removed: a delimiter must never become a nameless, addressless
        // recipient in the composer's Cc field.
        assertEquals(emptyList<ImapAddress>(), envelopeAddresses(listOf(groupEnd, groupEnd)))
    }

    @Test
    fun `pure - a group start followed by a group start yields both names`() {
        // Start, start, end. Neither start is followed by a MEMBER, so both are named
        // destinations that name nobody, and both are carried. Pinned as the rule gives it: no
        // sender writes this, and losing a name here is the loss this whole file exists to stop.
        assertEquals(
            listOf(ImapAddress(name = "outer", email = null), ImapAddress(name = "inner", email = null)),
            envelopeAddresses(listOf(groupStart("outer"), groupStart("inner"), groupEnd)),
        )
    }

    @Test
    fun `pure - order is the wire order`() {
        assertEquals(
            listOf(
                ImapAddress(name = "Ann", email = "ann@masto.top"),
                ImapAddress(name = "undisclosed-recipients", email = null),
                ImapAddress(name = "Bob", email = "bob@masto.top"),
            ),
            envelopeAddresses(
                listOf(
                    mailbox("Ann", "ann", "masto.top"),
                    groupStart("undisclosed-recipients"),
                    groupEnd,
                    mailbox("Bob", "bob", "masto.top"),
                ),
            ),
        )
    }

    @Test
    fun `pure - an entry with a host is an address, never a group start`() {
        // The group start is recognised by the ABSENCE of a host. An address whose display name
        // is missing is not one, and must not lose its address to a group name.
        assertEquals(
            listOf(ImapAddress(name = null, email = "membre@masto.top")),
            envelopeAddresses(listOf(mailbox(null, "membre", "masto.top"), groupEnd)),
        )
    }

    @Test
    fun `pure - a named entry with no mailbox is not a group start`() {
        // Display name but no address at all: carried as a name, as it always was, and it does
        // not swallow the entry that follows.
        assertEquals(
            listOf(ImapAddress(name = "Nobody", email = null)),
            envelopeAddresses(listOf(listOf<Any?>("Nobody", null, null, null), groupEnd)),
        )
    }

    @Test
    fun `pure - an empty list and a null list give nothing`() {
        assertEquals(emptyList<ImapAddress>(), envelopeAddresses(emptyList()))
        assertEquals(emptyList<ImapAddress>(), envelopeAddresses(null))
    }

    @Test
    fun `pure - the group name is decoded like any other display name`() {
        assertEquals(
            listOf(ImapAddress(name = "Équipe Sécurité", email = null)),
            envelopeAddresses(
                listOf(groupStart("=?utf-8?B?w4lxdWlwZSBTw6ljdXJpdMOp?="), groupEnd),
            ),
        )
    }

    @Test
    fun `pure - an 8-bit group name is read as UTF-8, like the other headers`() {
        // The parser hands over a literal as a byte container, one char per wire byte.
        val raw = String("Équipe".toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)

        assertEquals(
            listOf(ImapAddress(name = "Équipe", email = null)),
            envelopeAddresses(listOf(groupStart(raw), groupEnd)),
        )
    }
}
