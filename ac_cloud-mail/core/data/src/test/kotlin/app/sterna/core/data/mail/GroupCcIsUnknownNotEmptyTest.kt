package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.SmtpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * **A Cc that is nothing but a group must read as UNKNOWN, never as empty** — executed on a real
 */
class GroupCcIsUnknownNotEmptyTest {

    /**
     * THE case: `Cc: undisclosed-recipients:;`, i.e. a group start and its end, and no bcc.
     * Unknown — the original must be kept.
     */
    @Test(timeout = 30_000)
    fun `a Cc holding only an empty group reads as unknown`() {
        ScriptedImapServer(folderWith(cc = GROUP_WITH_END, bcc = "NIL")).use { server ->
            val read = runBlocking { ccAndBccOf(server) }
            assertNull(
                "⛔ `Cc: undisclosed-recipients:;` is addressing that could not be resolved to an " +
                    "address, i.e. UNKNOWN. Answering an empty set here tells the save that the " +
                    "replacement carries everything the original had, and the copy still holding " +
                    "the header is expunged. Got $read",
                read,
            )
            assertEquals(
                "and it is read off the draft the caller named, in the folder it named",
                listOf(
                    // Every session opens by asking whether the server knows the RFC 2971 ID
                    // command (#173); this fixture answers without it, so nothing is named.
                    "CAPABILITY",
                    "SELECT \"$DRAFTS\"",
                    "UID FETCH $UID (UID FLAGS INTERNALDATE ENVELOPE BODYSTRUCTURE BODY.PEEK[HEADER.FIELDS (REFERENCES)])",
                ),
                server.issued(),
            )
        }
    }

    /**
     * A group start with NO end. HYPOTHETICAL, and kept on purpose: no server here has been
     */
    @Test(timeout = 30_000)
    fun `a Cc holding a group start with no end reads as unknown too`() {
        ScriptedImapServer(folderWith(cc = GROUP_START_ONLY, bcc = "NIL")).use { server ->
            assertNull(
                "the second open of the same draft must not answer differently from the first",
                runBlocking { ccAndBccOf(server) },
            )
        }
    }

    /**
     * THE witness that gives the two above their meaning: a real address in the Cc comes back
     */
    @Test(timeout = 30_000)
    fun `a Cc holding a real address reads as that address`() {
        ScriptedImapServer(folderWith(cc = COPY_CAT, bcc = "NIL")).use { server ->
            assertEquals(
                "a readable Cc is read, and the destroy decision gets a set it can compare",
                setOf("copy@masto.top"),
                runBlocking { ccAndBccOf(server) },
            )
        }
    }

    /**
     * THE OTHER witness, and the one the defect's whole shape rests on: a draft with neither Cc
     */
    @Test(timeout = 30_000)
    fun ccAndBccOfADraftWithNeitherCcNorBcc() {
        ScriptedImapServer(folderWith(cc = "NIL", bcc = "NIL")).use { server ->
            assertEquals(
                "no copied parties at all is a KNOWN empty addressing — nothing to lose, the " +
                    "server copy may be replaced in place",
                emptySet<String>(),
                runBlocking { ccAndBccOf(server) },
            )
        }
    }

    /**
     * Both halves are read together (`message.cc + message.bcc`), so a group naming nobody in the
     */
    @Test(timeout = 30_000)
    fun `an empty group in the Bcc makes the read unknown though the Cc is readable`() {
        ScriptedImapServer(folderWith(cc = COPY_CAT, bcc = GROUP_WITH_END)).use { server ->
            assertNull(
                "one unreadable entry on either side makes the read unknown; a set of just " +
                    "copy@masto.top would say the bcc was empty, and it is not",
                runBlocking { ccAndBccOf(server) },
            )
        }
    }

    // ---- fixtures -----------------------------------------------------------------------------

    /**
     * The shipped call, POSITIONALLY: folder then uid, the order the save and the send both pass
     */
    private suspend fun ccAndBccOf(server: ScriptedImapServer): Set<String>? =
        service().ccAndBccOf(credentials(server), DRAFTS, UID, ImapBudget.NO_BUDGET)

    /** A Drafts folder holding one message, UID [UID], whose envelope carries [cc] and [bcc]. */
    private fun folderWith(cc: String, bcc: String): (String, String) -> String = { tag, line ->
        when {
            line.startsWith("LOGIN") -> "$tag OK logged in\r\n"
            line.startsWith("SELECT") ->
                "* 1 EXISTS\r\n* OK [UIDVALIDITY 9] ok\r\n$tag OK [READ-WRITE] selected\r\n"
            line.startsWith("UID FETCH") -> envelope(cc, bcc) + "$tag OK fetch completed\r\n"
            else -> "$tag OK done\r\n"
        }
    }

    /** One `UID FETCH` row. Ten ENVELOPE slots: cc is the 7th and bcc the 8th, and getting those
     *  two the wrong way round is its own data loss — see `envelopeAddresses`. */
    private fun envelope(cc: String, bcc: String) =
        "* 1 FETCH (UID $UID FLAGS (\\Draft) INTERNALDATE \"01-Jun-2026 10:00:00 +0000\" " +
            "ENVELOPE (\"Mon, 1 Jun 2026 10:00:00 +0000\" \"Half written\" " +
            "((\"Alex\" NIL \"alex\" \"masto.top\")) NIL NIL NIL $cc $bcc NIL \"<d1@masto.top>\") " +
            "BODYSTRUCTURE (\"text\" \"plain\" (\"charset\" \"utf-8\") NIL NIL \"7bit\" 12 1))\r\n"

    private fun service() = ImapMailService(ImapClient(), SmtpClient(), unusedTokenRefresher())

    private fun credentials(server: ScriptedImapServer) = AccountCredentials(
        server = "scripted",
        username = "alex@masto.top",
        password = "secret",
        id = "acc",
        protocol = MailProtocol.IMAP,
        imap = MailEndpoint(server.host, server.port, ConnectionSecurity.NONE),
    )

    private companion object {
        const val DRAFTS = "Drafts"
        const val UID = 42L

        /** `Cc: undisclosed-recipients:;` on the wire: a mailbox with no host (the group start),
         *  then an entry with nothing at all in it (the group end). Neither is an address. */
        const val GROUP_WITH_END = "((NIL NIL \"undisclosed-recipients\" NIL)(NIL NIL NIL NIL))"

        /** A group start alone, with no end marker — a shape no server here has been seen to
         * send (hypothetical), not the result of our own round trip: what this app appends is
         *  `undisclosed-recipients:;`, which comes back start AND end. */
        const val GROUP_START_ONLY = "((NIL NIL \"undisclosed-recipients\" NIL))"

        /** …and a recipient that really is one. */
        const val COPY_CAT = "((\"Copy Cat\" NIL \"copy\" \"masto.top\"))"

        /**
         * An [OAuthTokenRefresher] that is never used and cannot be built — the same trick, and for
         */
        fun unusedTokenRefresher(): OAuthTokenRefresher {
            val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null)
            val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            return allocate.invoke(unsafe, OAuthTokenRefresher::class.java) as OAuthTokenRefresher
        }
    }
}
