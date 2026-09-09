package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.ImapMoved
import app.sterna.core.imap.ImapSearchCriteria
import app.sterna.core.imap.SmtpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The twin of [DestroyNeedsTheNumberingStatedNowTest], for the gesture the user actually makes:
 */
class MoveNeedsTheNumberingStatedNowTest {

    // ---- the wire, with nothing frozen to oppose ----------------------------------------------
    // The shape EVERY call site had before this change: four arguments, no numbering carried. It
    // is not a corner case, it is `MailRepository.delete` as shipped.

    @Test fun `a server that stops stating its numbering gets no move`() {
        val store = RememberedNumbering()
        var refusal: Throwable? = null
        // Yesterday the folder announced 42; today's SELECT carries no UIDVALIDITY line.
        ScriptedImapServer(scripted { select -> if (select == 1) 42L else null }).use { server ->
            val service = service(store)
            val credentials = credentials(server)
            runBlocking {
                // Yesterday: browsing the folder is what records 42, and 42 is what the id on
                // screen belongs to.
                assertEquals(42L, service.snapshotUids(credentials, INBOX, 100).uidValidity)
                // Today: the user swipes the message to the Trash.
                refusal = runCatching { service.move(credentials, INBOX, 7L, TRASH) }.exceptionOrNull()
            }

            assertEquals(
                "not one message may be moved under a numbering the server did not state: this " +
                    "UID belongs to yesterday's numbering, and nothing says it is still in force",
                emptyList<String>(),
                server.issued("UID MOVE"),
            )
            assertEquals(
                "nor by the copy fallback, which ends in an expunge of the source",
                emptyList<String>(),
                server.issued("UID COPY"),
            )
            assertEquals(
                "and nothing may be flagged \\Deleted",
                emptyList<String>(),
                server.issued("UID STORE"),
            )
            assertOneConnection(server)
        }
        assertTrue(
            "the refusal must reach the caller — swallowed, the row is dropped from the cache for " +
                "a move that never happened, got $refusal",
            refusal is ImapNumberingUnconfirmed,
        )
        assertEquals(INBOX, (refusal as ImapNumberingUnconfirmed).mailbox)
        assertEquals(
            "and it carries what the call opposed, which here is nothing",
            null, (refusal as ImapNumberingUnconfirmed).expected,
        )
        assertEquals(
            "the folder must NOT be invalidated: dropping the caches would throw away every " +
                "pending purge snapshot it holds, on a server that is merely quiet",
            emptyList<String>(),
            store.invalidated,
        )
        assertEquals(
            "nor may the silence overwrite what was recorded",
            mapOf("$ACCOUNT/$INBOX" to 42L),
            store.numbers,
        )
    }

    @Test fun `a server that stops stating its numbering gets no batch move either`() {
        val store = RememberedNumbering()
        var refusal: Throwable? = null
        ScriptedImapServer(scripted { select -> if (select == 1) 42L else null }).use { server ->
            val service = service(store)
            val credentials = credentials(server)
            runBlocking {
                assertEquals(42L, service.snapshotUids(credentials, INBOX, 100).uidValidity)
                refusal = runCatching { service.moveBatch(credentials, INBOX, UIDS, TRASH) }.exceptionOrNull()
            }

            assertEquals(emptyList<String>(), server.issued("UID MOVE"))
            assertEquals(emptyList<String>(), server.issued("UID COPY"))
            assertEquals(emptyList<String>(), server.issued("UID STORE"))
            assertOneConnection(server)
        }
        assertTrue(
            "the batch path is the one the bulk selection takes, and it must refuse the same way, " +
                "got $refusal",
            refusal is ImapNumberingUnconfirmed,
        )
        assertEquals(INBOX, (refusal as ImapNumberingUnconfirmed).mailbox)
        assertEquals(null, (refusal as ImapNumberingUnconfirmed).expected)
        assertEquals(emptyList<String>(), store.invalidated)
        assertEquals(mapOf("$ACCOUNT/$INBOX" to 42L), store.numbers)
    }

    /**
     * And a move that opposes nothing is refused even on a PERFECTLY HEALTHY server — the case
     */
    @Test fun `a move that opposes nothing is refused, on a perfectly healthy server`() {
        val store = RememberedNumbering()
        var single: Throwable? = null
        var batch: Throwable? = null
        ScriptedImapServer(scripted { 42L }).use { server ->
            val service = service(store)
            val credentials = credentials(server)
            runBlocking {
                service.snapshotUids(credentials, INBOX, 100)
                single = runCatching { service.move(credentials, INBOX, 7L, TRASH) }.exceptionOrNull()
                batch = runCatching { service.moveBatch(credentials, INBOX, UIDS, TRASH) }.exceptionOrNull()
            }

            assertEquals(
                "the server states 42 and 42 is what is recorded — and it still licenses nothing, " +
                    "because comparing the current number with itself is not a confirmation",
                emptyList<String>(),
                server.issued("UID MOVE"),
            )
            assertEquals(emptyList<String>(), server.issued("UID COPY"))
            assertOneConnection(server)
        }
        assertTrue("got $single", single is ImapNumberingUnconfirmed)
        assertTrue("got $batch", batch is ImapNumberingUnconfirmed)
        assertEquals(null, (single as ImapNumberingUnconfirmed).expected)
        assertEquals(null, (batch as ImapNumberingUnconfirmed).expected)
    }

    // ---- the wire, with the gesture's own numbering frozen -------------------------------------

    /**
     * What the call sites do now: they carry the number the folder was last seen under. The
     */
    @Test fun `a frozen numbering the server no longer states refuses the move and names it`() {
        val store = RememberedNumbering()
        var single: Throwable? = null
        var batch: Throwable? = null
        ScriptedImapServer(scripted { select -> if (select == 1) 42L else null }).use { server ->
            val service = service(store)
            val credentials = credentials(server)
            runBlocking {
                assertEquals(42L, service.snapshotUids(credentials, INBOX, 100).uidValidity)
                single = runCatching { service.move(credentials, INBOX, 7L, TRASH, 42L) }.exceptionOrNull()
                batch = runCatching { service.moveBatch(credentials, INBOX, UIDS, TRASH, 42L) }.exceptionOrNull()
            }

            assertEquals(emptyList<String>(), server.issued("UID MOVE"))
            assertEquals(emptyList<String>(), server.issued("UID COPY"))
            assertEquals(emptyList<String>(), server.issued("UID STORE"))
            assertOneConnection(server)
        }
        assertTrue("got $single", single is ImapNumberingUnconfirmed)
        assertTrue("got $batch", batch is ImapNumberingUnconfirmed)
        listOf(single, batch).forEach { refusal ->
            assertEquals(INBOX, (refusal as ImapNumberingUnconfirmed).mailbox)
            assertEquals(
                "the refusal must carry the number that was opposed, not a rediscovered one",
                42L, refusal.expected,
            )
        }
        assertEquals(emptyList<String>(), store.invalidated)
        assertEquals(mapOf("$ACCOUNT/$INBOX" to 42L), store.numbers)
    }

    /**
     * The inverse witness. Without it "refuse always" satisfies every case above, and that is a
     */
    @Test fun `a server still stating the same numbering moves exactly as before`() {
        val store = RememberedNumbering()
        var single: ImapMoved = ImapMoved.NONE
        var batch: ImapMoved = ImapMoved.NONE
        ScriptedImapServer(scripted { 42L }).use { server ->
            val service = service(store)
            val credentials = credentials(server)
            runBlocking {
                service.snapshotUids(credentials, INBOX, 100)
                single = service.move(credentials, INBOX, 7L, TRASH, 42L)
                batch = service.moveBatch(credentials, INBOX, UIDS, TRASH, 42L)
            }

            assertEquals(
                listOf("UID MOVE 7 \"Trash\"", "UID MOVE 7:9 \"Trash\""),
                server.issued("UID MOVE"),
            )
        }
        assertEquals(
            "and the destination UID must still come back, or Undo has nowhere to move it from",
            100L, single.uids[7L],
        )
        assertEquals(
            "and so must the whole COPYUID mapping, or an undone bulk move restores nothing",
            mapOf(7L to 100L, 8L to 101L, 9L to 102L), batch.uids,
        )
    }

    // ---- the guard must not fire at a healthy server -------------------------------------------

    /**
     * THE COST OF GETTING THIS WRONG, and it was wrong: a folder the user has never OPENED, met
     */
    @Test fun `a search records the numbering of the folder it looked in, so a hit can be moved`() {
        val store = RememberedNumbering()
        var moved: ImapMoved = ImapMoved.NONE
        ScriptedImapServer(searchable()).use { server ->
            val service = service(store)
            val credentials = credentials(server)
            runBlocking {
                // The user searches. ARCHIVE has never been opened, so nothing knows its numbering.
                val hits = service.search(
                    credentials,
                    listOf(ARCHIVE),
                    ImapSearchCriteria(from = "dana@example.org"),
                    requireAttachment = false,
                    limit = 10,
                )
                assertEquals("the search must find the hit it is scripted to find", 1, hits.messages.size)

                assertEquals(
                    "and the walk's own SELECT must have been recorded: it is the only time this " +
                        "folder is ever entered, and without it the move below has nothing to freeze",
                    42L,
                    service.recordedUidValidity(ACCOUNT, ARCHIVE),
                )

                // The user acts on the hit, freezing what was just recorded — as MailRepository does.
                moved = service.move(credentials, ARCHIVE, 7L, TRASH, service.recordedUidValidity(ACCOUNT, ARCHIVE))
            }

            assertEquals(
                "the move must GO OUT: nothing was renumbered, the server states 42 and 42 is what " +
                    "was recorded. A refusal here is the guard firing at a healthy server",
                listOf("UID MOVE 7 \"Trash\""),
                server.issued("UID MOVE"),
            )
        }
        assertEquals(100L, moved.uids[7L])
    }

    // ---- what the refusal must COST ------------------------------------------------------------

    /**
     * WHERE THE REFUSAL IS RAISED, read as the only thing that can show it: the number of
     */
    private fun assertOneConnection(server: ScriptedImapServer) = assertEquals(
        "the refusal must have cost exactly ONE connection. A second means the throw moved " +
            "inside the onMailbox block, where `withSession`'s retry closes the session, " +
            "reconnects, LOGINs and re-SELECTs before failing anyway — a whole round trip for an " +
            "answer already in hand. Commands were: ${server.issued()}",
        1, server.connections.get(),
    )

    // ---- fixtures -----------------------------------------------------------------------------

    /**
     * A server whose Nth SELECT announces [announce] `(n)` — null meaning NO `UIDVALIDITY` line at
     */
    private fun scripted(announce: (Int) -> Long?): (String, String) -> String {
        var selects = 0
        return { tag, line ->
            when {
                line.startsWith("LOGIN") -> "$tag OK [CAPABILITY IMAP4rev1 UIDPLUS MOVE] logged in\r\n"
                line.startsWith("CAPABILITY") -> "* CAPABILITY IMAP4rev1 UIDPLUS MOVE\r\n$tag OK done\r\n"
                line.startsWith("SELECT") -> {
                    val stated = announce(++selects)?.let { "* OK [UIDVALIDITY $it] ok\r\n" }.orEmpty()
                    "* 3 EXISTS\r\n" + stated + "* OK [UIDNEXT 20] ok\r\n$tag OK [READ-WRITE] selected\r\n"
                }
                line.startsWith("UID SEARCH") -> "* SEARCH 7 8 9\r\n$tag OK search completed\r\n"
                line.startsWith("UID MOVE") -> "$tag OK [${copyUid(line)}] done\r\n"
                else -> "$tag OK done\r\n"
            }
        }
    }

    /** A server whose ARCHIVE folder can be SEARCHed, states 42, and holds one matching message.
     *  The `UID FETCH` envelope is the one `SearchHitOpensFromTheServerTest` uses — the search
     *  walk drops a folder whose fetch parses no message, and a dropped folder reports no
     *  numbering, which would make the test pass for the wrong reason. */
    private fun searchable(): (String, String) -> String = { tag, line ->
        when {
            line.startsWith("LOGIN") -> "$tag OK [CAPABILITY IMAP4rev1 UIDPLUS MOVE] logged in\r\n"
            line.startsWith("CAPABILITY") -> "* CAPABILITY IMAP4rev1 UIDPLUS MOVE\r\n$tag OK done\r\n"
            line.startsWith("SELECT") ->
                "* 3 EXISTS\r\n* OK [UIDVALIDITY 42] ok\r\n* OK [UIDNEXT 20] ok\r\n$tag OK [READ-WRITE] selected\r\n"
            line.startsWith("UID SEARCH") -> "* SEARCH 7\r\n$tag OK search completed\r\n"
            line.startsWith("UID FETCH") ->
                "* 7 FETCH (UID 7 FLAGS () INTERNALDATE \"01-Jun-2026 10:00:00 +0000\" " +
                    "ENVELOPE (\"Mon, 1 Jun 2026 10:00:00 +0000\" \"Quarterly figures\" " +
                    "((\"Dana\" NIL \"dana\" \"example.org\")) NIL NIL " +
                    "((\"Alex\" NIL \"alex\" \"example.org\")) NIL NIL NIL \"<q3@example.org>\") " +
                    "BODYSTRUCTURE (\"text\" \"plain\" (\"charset\" \"utf-8\") NIL NIL \"7bit\" 12 1))\r\n" +
                    "$tag OK fetch completed\r\n"
            line.startsWith("UID MOVE") -> "$tag OK [${copyUid(line)}] done\r\n"
            else -> "$tag OK done\r\n"
        }
    }

    /** The `COPYUID` for the UID set of [line], mapping 7,8,9 onto 100,101,102 in the destination.
     *  Scripted rather than computed: an unexpected set must fail loudly here instead of coming
     *  back as an empty mapping, which reads exactly like "the server reported none". */
    private fun copyUid(line: String): String {
        val set = line.removePrefix("UID MOVE ").substringBefore(' ')
        return when (set) {
            "7" -> "COPYUID 42 7 100"
            "7:9" -> "COPYUID 42 7:9 100:102"
            else -> error("unscripted UID set: $set")
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

    /** See the twin in [DestroyNeedsTheNumberingStatedNowTest]; duplicated rather than shared, so
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
        const val TRASH = "Trash"
        const val ARCHIVE = "Archive"
        val UIDS = listOf(7L, 8L, 9L)

        /** See [DestroyNeedsTheNumberingStatedNowTest]'s twin — `ImapMailService` requires an
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
