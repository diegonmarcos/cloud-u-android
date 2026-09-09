package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.ImapUidValidityChanged
import app.sterna.core.imap.SmtpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The READ half of the numbering guard, RUN against a real [ImapMailService] on a scripted socket
 */
class ReadUnderTheNumberingItWasTickedUnderTest {

    /**
     * NOT ONE OCTET. The refusal has to land here, on the read, because [crossAccountMove] runs
     */
    @Test fun `the numbering of the tick is what is opposed, and a renumbered folder is not read`() {
        val store = RememberedNumbering()
        var refusal: Throwable? = null
        var read: String? = null
        ScriptedImapServer(stating(77L)).use { server ->
            val service = service(store)
            val credentials = credentials(server)
            runBlocking {
                // What a background pass wrote after it met the renumbering: the folder's record
                // now says 77, which is also what the server says.
                store.record(ACCOUNT, INBOX, 77L)
                // The row was ticked under 42.
                refusal = runCatching { read = service.fetchSource(credentials, INBOX, 7L, 42L) }.exceptionOrNull()
            }

            assertEquals(
                "the SELECT must have gone out — the refusal is the server's answer to it, not a " +
                    "decision taken without asking",
                1, server.issued("SELECT").size,
            )
            assertEquals(
                "and NOT ONE OCTET may be fetched: this uid belonged to the numbering the row was " +
                    "ticked under, and the folder no longer carries it. Read under the FOLDER's " +
                    "record instead — 77 against 77 — the fetch goes out and the octets of " +
                    "whatever holds uid 7 now are the ones copied to the other account",
                emptyList<String>(), server.issued("UID FETCH"),
            )
            assertEquals(
                "the refusal must cost exactly ONE connection: ImapUidValidityChanged is not an " +
                    "ImapException, so runWithRetry re-throws it instead of reconnecting",
                1, server.connections.get(),
            )
        }
        assertNull("and nothing may come back to be written on the other account", read)
        assertTrue(
            "the refusal must reach the caller as the renumbering it is — crossAccountMove turns " +
                "it into a Failed, which is the PARTLY_FAILED banner. Got $refusal",
            refusal is ImapUidValidityChanged,
        )
        val renumbered = refusal as ImapUidValidityChanged
        assertEquals(INBOX, renumbered.mailbox)
        assertEquals(
            "and it must name the number that was OPPOSED — the stamp of the tick, 42. Naming 77 " +
                "would mean the folder's own record was opposed to itself and the refusal came " +
                "from somewhere else entirely",
            42L, renumbered.expected,
        )
        assertEquals(77L, renumbered.observed)
        assertEquals(
            "and the refusal must not rewrite the record with the tick's number: the server's 77 " +
                "is the truth about the folder, and the next ordinary read must still work",
            mapOf("$ACCOUNT/$INBOX" to 77L), store.numbers,
        )
    }

    /**
     * THE INVERSE WITNESS. Without it, "refuse every read that carries a stamp" satisfies the
     * case above — and every cross-account move on every healthy server would fail, for ever.
     */
    @Test fun `the numbering the folder still carries reads the message`() {
        val store = RememberedNumbering()
        var read: String? = null
        ScriptedImapServer(stating(77L)).use { server ->
            val service = service(store)
            val credentials = credentials(server)
            runBlocking {
                store.record(ACCOUNT, INBOX, 77L)
                read = service.fetchSource(credentials, INBOX, 7L, 77L)
            }

            assertEquals(
                "nothing was renumbered: the row was ticked under 77 and the server states 77. A " +
                    "refusal here is the guard firing at a healthy server, i.e. an outage",
                listOf("UID FETCH 7 (BODY.PEEK[])"), server.issued("UID FETCH"),
            )
        }
        assertEquals("and the octets must be the message's own", SOURCE, read)
    }

    /**
     * And a read that carries NOTHING keeps the behaviour every ordinary reader depends on: the
     */
    @Test fun `a read that freezes nothing falls back on the record, exactly as before`() {
        val store = RememberedNumbering()
        var read: String? = null
        ScriptedImapServer(stating(77L)).use { server ->
            val service = service(store)
            val credentials = credentials(server)
            runBlocking {
                store.record(ACCOUNT, INBOX, 77L)
                read = service.fetchSource(credentials, INBOX, 7L)
            }

            assertEquals(listOf("UID FETCH 7 (BODY.PEEK[])"), server.issued("UID FETCH"))
        }
        assertEquals(SOURCE, read)
    }

    // ---- fixtures -----------------------------------------------------------------------------

    /** A server whose every SELECT announces [announce], and whose only FETCH answers [SOURCE] as
     *  a literal — the shape `ImapClient.fetchSource` reads through `bodyItem()`. */
    private fun stating(announce: Long): (String, String) -> String = { tag, line ->
        when {
            line.startsWith("LOGIN") -> "$tag OK [CAPABILITY IMAP4rev1 UIDPLUS MOVE] logged in\r\n"
            line.startsWith("CAPABILITY") -> "* CAPABILITY IMAP4rev1 UIDPLUS MOVE\r\n$tag OK done\r\n"
            line.startsWith("SELECT") ->
                "* 3 EXISTS\r\n* OK [UIDVALIDITY $announce] ok\r\n* OK [UIDNEXT 20] ok\r\n$tag OK [READ-WRITE] selected\r\n"
            line.startsWith("UID FETCH") ->
                "* 1 FETCH (UID 7 BODY[] {${SOURCE.length}}\r\n$SOURCE)\r\n$tag OK fetch completed\r\n"
            else -> "$tag OK done\r\n"
        }
    }

    private fun service(store: UidValidityStore) =
        ImapMailService(ImapClient(), SmtpClient(), unusedTokenRefresher(), store)

    private fun credentials(server: ScriptedImapServer) = AccountCredentials(
        server = "scripted",
        username = "tester",
        password = "secret",
        id = ACCOUNT,
        protocol = MailProtocol.IMAP,
        imap = MailEndpoint(server.host, server.port, ConnectionSecurity.NONE),
    )

    /** See the twin in [MoveNeedsTheNumberingStatedNowTest]; duplicated rather than shared, so
     *  that neither test's fixture can be loosened for both at once. */
    private class RememberedNumbering : UidValidityStore {
        val numbers = mutableMapOf<String, Long>()
        val invalidated = mutableListOf<String>()
        override var onRenumbered: ((String, String) -> Unit)? = null
        override suspend fun recorded(accountId: String, mailboxId: String): Long? =
            numbers["$accountId/$mailboxId"]
        override suspend fun record(accountId: String, mailboxId: String, uidValidity: Long) {
            if (uidValidity <= 0L) return
            numbers["$accountId/$mailboxId"] = uidValidity
        }
        override suspend fun invalidate(accountId: String, mailboxId: String, uidValidity: Long) {
            invalidated += "$accountId/$mailboxId"
            record(accountId, mailboxId, uidValidity)
        }
    }

    private companion object {
        const val ACCOUNT = "acc1"
        const val INBOX = "INBOX"
        const val SOURCE = "Subject: Quarterly figures\r\n\r\nHello.\r\n"

        /** See [MoveNeedsTheNumberingStatedNowTest]'s twin — `ImapMailService` requires an
         *  `OAuthTokenRefresher`, which requires an Android `Context`, and a password account
         *  never reads a field of it. */
        fun unusedTokenRefresher(): OAuthTokenRefresher {
            val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null)
            val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            return allocate.invoke(unsafe, OAuthTokenRefresher::class.java) as OAuthTokenRefresher
        }
    }
}
