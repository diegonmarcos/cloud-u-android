package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.db.EmailRecipients
import app.sterna.core.data.db.LOCAL_DRAFT_ID_PREFIX
import app.sterna.core.data.db.LocalDraftEntity
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.SmtpClient
import app.sterna.core.jmap.model.EmailAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The round trip a draft naming nobody has to survive**, measured on `emu`/Stalwart on
 */
class GroupTokenSurvivesTheAppendRoundTripTest {

    @Test(timeout = 30_000)
    fun `the token the APPEND writes comes back naming the group`() {
        val row = LocalDraftEntity(
            accountId = "acc", id = LOCAL_DRAFT_ID_PREFIX + "d1", messageId = "mid@masto.top",
            toAddresses = "bob@masto.top", cc = "undisclosed-recipients", bcc = null,
            subject = "Half written", textBody = "text",
            createdAtMillis = NOW, updatedAtMillis = NOW, notBeforeMillis = NOW,
        )

        val written = localDraftOutgoing(row, "alex@masto.top", emptyList(), NOW).cc.single()

        // The server's half of the trip, and the ONLY thing modelled here: RFC 5322 group syntax
        // `name:;` is handed back as a start `(NIL NIL "name" NIL)` and an end `(NIL NIL NIL NIL)`
        assertTrue(
            "⛔ what the APPEND writes must BE group syntax. Got \"$written\", which Stalwart " +
                "answers `cc = NIL` for — the field vanishes on the second open and the copy " +
                "holding it is expunged",
            written.endsWith(":;"),
        )
        val handedBack = "((NIL NIL \"${written.removeSuffix(":;")}\" NIL)(NIL NIL NIL NIL))"

        ScriptedImapServer(folderWith(cc = handedBack)).use { server ->
            val reopened = runBlocking {
                service().fetchByUid(credentials(server), DRAFTS, UID)
            }
            assertEquals(
                "the reopened draft must show the group by name and no address — that entry is " +
                    "what keeps the Cc 'unknown' instead of 'proved empty'",
                listOf(EmailAddress(name = "undisclosed-recipients", email = "")),
                EmailRecipients.decode(reopened?.ccJson),
            )
        }
    }

    // ---- fixtures -----------------------------------------------------------------------------

    /** A Drafts folder holding one message, UID [UID], whose envelope carries [cc]. */
    private fun folderWith(cc: String): (String, String) -> String = { tag, line ->
        when {
            line.startsWith("LOGIN") -> "$tag OK logged in\r\n"
            line.startsWith("SELECT") ->
                "* 1 EXISTS\r\n* OK [UIDVALIDITY 9] ok\r\n$tag OK [READ-WRITE] selected\r\n"
            line.startsWith("UID FETCH") -> envelope(cc) + "$tag OK fetch completed\r\n"
            else -> "$tag OK done\r\n"
        }
    }

    /** One `UID FETCH` row. Cc is the ENVELOPE's 7th slot; the bcc beside it stays NIL. */
    private fun envelope(cc: String) =
        "* 1 FETCH (UID $UID FLAGS (\\Draft) INTERNALDATE \"01-Jun-2026 10:00:00 +0000\" " +
            "ENVELOPE (\"Mon, 1 Jun 2026 10:00:00 +0000\" \"Half written\" " +
            "((\"Alex\" NIL \"alex\" \"masto.top\")) NIL NIL NIL $cc NIL NIL \"<d1@masto.top>\") " +
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
        const val UID = 431L
        const val NOW = 1_760_000_000_000L

        /** Never used and never built — the same trick, for the same reason, as
         *  [GroupCcIsUnknownNotEmptyTest]'s: the REAL service is required and a refresher needs an
         *  Android `Context`. `freshAccessToken` returns at `credentials.oauth ?: return null`. */
        fun unusedTokenRefresher(): OAuthTokenRefresher {
            val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null)
            val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            return allocate.invoke(unsafe, OAuthTokenRefresher::class.java) as OAuthTokenRefresher
        }
    }
}
