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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A destroy may only go out under a numbering the server states IN THE SELECT IT IS ABOUT TO
 */
class DestroyNeedsTheNumberingStatedNowTest {

    // ---- the wire ----------------------------------------------------------------------------

    @Test fun `a server that stops stating its numbering gets no expunge`() {
        val store = RememberedNumbering()
        var refusal: Throwable? = null
        // Yesterday the folder announced 42; today's SELECT carries no UIDVALIDITY line.
        ScriptedImapServer(scripted { select -> if (select == 1) 42L else null }).use { server ->
            val service = service(store)
            val credentials = credentials(server)
            runBlocking {
                // Yesterday: enumerating the Trash is what records 42, and 42 is what gets frozen
                // with the user's confirmation of the destroy.
                assertEquals(42L, service.snapshotUids(credentials, TRASH, 100).uidValidity)
                // Today: the held-back destroy runs, carrying the frozen 42.
                refusal = runCatching { service.deleteBatch(credentials, TRASH, UIDS, 42L) }.exceptionOrNull()
            }

            assertEquals(
                "not one message may be flagged \\Deleted under a numbering the server did not " +
                    "state: these UIDs belong to yesterday's numbering, and nothing says it is " +
                    "still in force",
                emptyList<String>(),
                server.issued("UID STORE"),
            )
            assertEquals(
                "and nothing may be erased",
                emptyList<String>(),
                server.issued("UID EXPUNGE"),
            )
        }
        assertTrue(
            "the refusal must reach the caller — swallowed, the UI reports a destroy that never " +
                "happened, got $refusal",
            refusal is ImapNumberingUnconfirmed,
        )
        assertEquals(TRASH, (refusal as ImapNumberingUnconfirmed).mailbox)
        assertEquals(42L, (refusal as ImapNumberingUnconfirmed).expected)
        assertEquals(
            "and the folder must NOT be invalidated: dropping the caches would throw away the " +
                "pending purge snapshot the user confirmed, on a server that is merely quiet",
            emptyList<String>(),
            store.invalidated,
        )
        assertEquals(
            "nor may the silence overwrite what was recorded",
            mapOf("$ACCOUNT/$TRASH" to 42L),
            store.numbers,
        )
    }

    /** The inverse witness. Without it "refuse always" satisfies the test above, and that is a
     *  total regression: no IMAP permanent delete would ever go through again. */
    @Test fun `a server still stating the same numbering destroys exactly as before`() {
        val store = RememberedNumbering()
        ScriptedImapServer(scripted { 42L }).use { server ->
            val service = service(store)
            val credentials = credentials(server)
            runBlocking {
                service.snapshotUids(credentials, TRASH, 100)
                service.deleteBatch(credentials, TRASH, UIDS, 42L)
            }

            assertEquals(listOf("UID STORE 7:9 +FLAGS (\\Deleted)"), server.issued("UID STORE"))
            assertEquals(listOf("UID EXPUNGE 7:9"), server.issued("UID EXPUNGE"))
        }
    }

    /**
     * THE ARGUMENT ITSELF, on a perfectly healthy server. The twin of
     */
    @Test fun `a destroy that opposes nothing is refused, on a perfectly healthy server`() {
        val store = RememberedNumbering()
        var refusal: Throwable? = null
        ScriptedImapServer(scripted { 42L }).use { server ->
            val service = service(store)
            val credentials = credentials(server)
            runBlocking {
                assertEquals("the server is healthy and states its numbering", 42L, service.snapshotUids(credentials, TRASH, 100).uidValidity)
                refusal = runCatching { service.deleteBatch(credentials, TRASH, UIDS, null) }.exceptionOrNull()
            }

            assertEquals(
                "a destroy with NOTHING to oppose may not be licensed by the server's own answer: " +
                    "that is comparing the number to itself, which is what an ordinary refresh has " +
                    "already realigned after a renumbering",
                emptyList<String>(),
                server.issued("UID STORE"),
            )
            assertEquals("and nothing may be erased", emptyList<String>(), server.issued("UID EXPUNGE"))
        }
        assertTrue(
            "the refusal must reach the caller, got $refusal",
            refusal is ImapNumberingUnconfirmed,
        )
        assertEquals(TRASH, (refusal as ImapNumberingUnconfirmed).mailbox)
        assertNull("and it must say it had nothing to oppose", (refusal as ImapNumberingUnconfirmed).expected)
        assertEquals(
            "a refusal invalidates nothing: the server is healthy, and dropping the caches would " +
                "throw away a purge snapshot the user confirmed",
            emptyList<String>(),
            store.invalidated,
        )
    }

    /**
     * The server that NEVER states a numbering is not this guard's business: nothing is recorded
     */
    @Test fun `a server that never states a numbering is refused without reaching the destroy`() {
        val store = RememberedNumbering()
        ScriptedImapServer(scripted { null }).use { server ->
            val service = service(store)
            val credentials = credentials(server)
            runBlocking {
                assertEquals(0L, service.snapshotUids(credentials, TRASH, 100).uidValidity)
                val frozen = service.recordedUidValidity(ACCOUNT, TRASH)
                assertNull("an unstated numbering must never be recorded", frozen)

                val plan = UidValidity.imapDestroyPlan(UIDS.map { id(TRASH, it) }, frozen)
                assertEquals(emptyMap<String, List<String>>(), plan.byFolder)
                assertEquals(UIDS.map { id(TRASH, it) }, plan.refused)
            }

            assertEquals(emptyList<String>(), server.issued("UID STORE"))
        }
    }

    // ---- the decision, executed, argument by argument -----------------------------------------

    @Test fun `a numbering the server just stated, equal to the frozen one, licenses the destroy`() {
        assertTrue(UidValidity.mayDestroyUnderStatedNumbering(frozen = 42L, stated = 42L))
        assertTrue(UidValidity.mayDestroyUnderStatedNumbering(frozen = 1L, stated = 1L))
        assertTrue(UidValidity.mayDestroyUnderStatedNumbering(frozen = Long.MAX_VALUE, stated = Long.MAX_VALUE))
    }

    @Test fun `a numbering the server did not state licenses nothing`() {
        // THE defect: 0 is "the SELECT carried no UIDVALIDITY line", and it used to sail through
        // because `ImapSession.select` only compares two POSITIVE numbers.
        assertFalseThat("stated 0", UidValidity.mayDestroyUnderStatedNumbering(frozen = 42L, stated = 0L))
        assertFalseThat("stated -1", UidValidity.mayDestroyUnderStatedNumbering(frozen = 42L, stated = -1L))
    }

    @Test fun `a frozen numbering that is absent or empty licenses nothing`() {
        assertFalseThat("frozen null", UidValidity.mayDestroyUnderStatedNumbering(frozen = null, stated = 42L))
        assertFalseThat("frozen 0", UidValidity.mayDestroyUnderStatedNumbering(frozen = 0L, stated = 42L))
        assertFalseThat("frozen -1", UidValidity.mayDestroyUnderStatedNumbering(frozen = -1L, stated = 42L))
        assertFalseThat("both absent", UidValidity.mayDestroyUnderStatedNumbering(frozen = null, stated = 0L))
    }

    @Test fun `two numberings that differ license nothing`() {
        assertFalseThat("42 vs 43", UidValidity.mayDestroyUnderStatedNumbering(frozen = 42L, stated = 43L))
        assertFalseThat("43 vs 42", UidValidity.mayDestroyUnderStatedNumbering(frozen = 43L, stated = 42L))
    }

    private fun assertFalseThat(what: String, licensed: Boolean) =
        assertEquals("$what must license no destroy", false, licensed)

    // ---- fixtures -----------------------------------------------------------------------------

    /**
     * A server whose Nth SELECT announces [announce] `(n)` — null meaning NO `UIDVALIDITY` line at
     */
    private fun scripted(announce: (Int) -> Long?): (String, String) -> String {
        var selects = 0
        return { tag, line ->
            when {
                line.startsWith("LOGIN") -> "$tag OK [CAPABILITY IMAP4rev1 UIDPLUS] logged in\r\n"
                line.startsWith("CAPABILITY") -> "* CAPABILITY IMAP4rev1 UIDPLUS\r\n$tag OK done\r\n"
                line.startsWith("SELECT") -> {
                    val stated = announce(++selects)?.let { "* OK [UIDVALIDITY $it] ok\r\n" }.orEmpty()
                    "* 3 EXISTS\r\n" + stated + "* OK [UIDNEXT 20] ok\r\n$tag OK [READ-WRITE] selected\r\n"
                }
                line.startsWith("UID SEARCH") -> "* SEARCH 7 8 9\r\n$tag OK search completed\r\n"
                else -> "$tag OK done\r\n"
            }
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

    private fun id(mailbox: String, uid: Long) = ImapMailService.emailId(ACCOUNT, mailbox, uid)

    /** The store as the app really has it, minus Room: [MailboxUidValidityStore] needs three DAOs.
     *  It keeps `record`'s invariant — a value of 0 or less is never written — because that is what
     *  makes "frozen > 0" mean "the server stated a numbering at some point". */
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
        const val TRASH = "Trash"
        val UIDS = listOf(7L, 8L, 9L)

        /**
         * An [OAuthTokenRefresher] that is never used and cannot be built.
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
