package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.subscriptionSyncOnToggle
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.SmtpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **What ticking "show only subscribed folders" ON actually puts on the wire** (#174).
 */
class SubscriptionToggleAsksTheServerTest {

    @Test(timeout = 30_000)
    fun `ticking the box asks the server which folders are subscribed`() {
        server().use { server ->
            val sync = subscriptionSyncOnToggle(ACCOUNT, enabled = true) ?: throw AssertionError(
                "⛔ moving the switch to ON owes the account a folder sync, and got none: the box " +
                    "moves, no LSUB ever goes out, and every cached folder keeps its default " +
                    "isSubscribed = true — so the drawer hides nothing until something else " +
                    "happens to re-list the folders. That is the reported defect, whole.",
            )
            assertEquals(
                "the sync must name the account whose switch was moved — a settings screen can be " +
                    "open on an account that is not the selected one",
                ACCOUNT,
                sync.accountId,
            )

            val load = runBlocking {
                service().loadFolder(
                    credentials(server),
                    requestedMailboxId = null,
                    limit = 1,
                    onlySubscribed = sync.onlySubscribed,
                    onPage = {},
                    // No opening lines: this test counts what ONE TICK of the switch costs the
                    // server, and a preview read would add a `UID FETCH` per section to that list.
                    cachedPreviewsFor = null,
                )
            }

            assertEquals(
                "the second round trip is the whole fix. Commands received: ${server.issued()}",
                listOf("""LSUB "" "*""""),
                server.issued().filter { it.startsWith("LSUB") },
            )
            assertEquals(
                "and the rows handed to the cache carry WHAT THE SERVER SAID, folder by folder — " +
                    "a listing that asked nothing brings them all back subscribed",
                mapOf("INBOX" to true, "Corbeille" to false, "Archives" to true),
                load.mailboxes.associate { it.id to it.isSubscribed },
            )
        }
    }

    @Test(timeout = 30_000)
    fun `unticking it costs no round trip at all`() {
        server().use { server ->
            assertNull(
                "⛔ turning the setting OFF has nothing to ask anybody: the subscriptions are " +
                    "already in the cache and the filter switches itself off. A sync here is a " +
                    "network round trip paid to untick a box",
                subscriptionSyncOnToggle(ACCOUNT, enabled = false),
            )
            assertEquals("nothing may even have dialled the server", 0, server.connections.get())
        }
    }

    @Test(timeout = 30_000)
    fun `an LSUB the server refuses hides not one folder`() {
        server(lsub = { tag -> "$tag NO subscriptions unavailable\r\n" }).use { server ->
            val sync = subscriptionSyncOnToggle(ACCOUNT, enabled = true) ?: throw AssertionError(
                "⛔ moving the switch to ON owes the account a folder sync, and got none",
            )

            val load = runBlocking {
                service().loadFolder(
                    credentials(server),
                    requestedMailboxId = null,
                    limit = 1,
                    onlySubscribed = sync.onlySubscribed,
                    onPage = {},
                    // No opening lines: this test counts what ONE TICK of the switch costs the
                    // server, and a preview read would add a `UID FETCH` per section to that list.
                    cachedPreviewsFor = null,
                )
            }

            assertTrue(
                "the LSUB was never even sent, so this proves nothing. Commands: ${server.issued()}",
                server.issued().any { it.startsWith("LSUB") },
            )
            assertEquals(
                "a refusal means UNKNOWN, never 'subscribed to nothing' — the folder list must " +
                    "come back whole",
                listOf("INBOX", "Corbeille", "Archives"),
                load.mailboxes.map { it.id },
            )
            assertEquals(
                "⛔ and every one of them still subscribed: let a failed LSUB through and a passing " +
                    "server error empties the drawer",
                listOf(true, true, true),
                load.mailboxes.map { it.isSubscribed },
            )
        }
    }

    /**
     * **Everything the tick really puts on the wire** — the whole command list, in order.
     */
    @Test(timeout = 30_000)
    fun `the tick costs a listing, a select and one discarded fetch`() {
        server(exists = 12).use { server ->
            val sync = subscriptionSyncOnToggle(ACCOUNT, enabled = true) ?: throw AssertionError("ON owes a sync")

            runBlocking {
                service().loadFolder(
                    credentials(server),
                    requestedMailboxId = null,
                    limit = 1,
                    onlySubscribed = sync.onlySubscribed,
                    onPage = {},
                    // No opening lines: this test counts what ONE TICK of the switch costs the
                    // server, and a preview read would add a `UID FETCH` per section to that list.
                    cachedPreviewsFor = null,
                )
            }

            assertEquals(
                "this is what one tick of the switch costs a server, and the KDocs beside it are " +
                    "answerable to this list",
                listOf(
                    // The first is the connect-time question of the RFC 2971 ID command (#173),
                    // whose answer is deliberately kept by nobody; the second is the listing's own
                    // SPECIAL-USE question, which is why it is asked again.
                    "CAPABILITY",
                    "CAPABILITY",
                    """LIST "" "*"""",
                    """LSUB "" "*"""",
                    """SELECT "INBOX"""",
                    "SEARCH UNSEEN UNDELETED",
                    "FETCH 12:12 (UID FLAGS INTERNALDATE ENVELOPE BODYSTRUCTURE BODY.PEEK[HEADER.FIELDS (REFERENCES)])",
                ),
                server.issued(),
            )
        }
    }

    // ---- the harness ------------------------------------------------------------------------------

    /**
     * Three mailboxes, two of them subscribed. [lsub] scripts the LSUB answer, tag included, so a
     */
    private fun server(exists: Int = 0, lsub: (String) -> String = ::subscriptions) = ScriptedImapServer { tag, line ->
        when {
            line.startsWith("LSUB") -> lsub(tag)
            line.startsWith("LIST") ->
                "* LIST (\\HasNoChildren) \".\" \"INBOX\"\r\n" +
                    "* LIST (\\HasNoChildren) \".\" \"Corbeille\"\r\n" +
                    "* LIST (\\HasNoChildren) \".\" \"Archives\"\r\n$tag OK listed\r\n"
            line.startsWith("SELECT") -> "* $exists EXISTS\r\n* OK [UIDVALIDITY 9] ok\r\n$tag OK [READ-WRITE] selected\r\n"
            else -> "$tag OK done\r\n"
        }
    }

    private fun subscriptions(tag: String) =
        "* LSUB (\\HasNoChildren) \".\" \"INBOX\"\r\n" +
            "* LSUB (\\HasNoChildren) \".\" \"Archives\"\r\n$tag OK listed\r\n"

    private fun service() = ImapMailService(ImapClient(), SmtpClient(), unusedTokenRefresher())

    private fun credentials(server: ScriptedImapServer) = AccountCredentials(
        server = "scripted",
        username = "alex@example.org",
        password = "secret",
        id = ACCOUNT,
        protocol = MailProtocol.IMAP,
        imap = MailEndpoint(server.host, server.port, ConnectionSecurity.NONE),
    )

    private companion object {
        const val ACCOUNT = "acc-imap-1"

        /** [MoveNeedsTheNumberingStatedNowTest]'s: nothing here refreshes a token. */
        fun unusedTokenRefresher(): OAuthTokenRefresher {
            val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null)
            val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            return allocate.invoke(unsafe, OAuthTokenRefresher::class.java) as OAuthTokenRefresher
        }
    }
}
