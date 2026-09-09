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
 * THE UNDO, and the numbering it opposes (Codeberg #99).
 */
class UndoMovesUnderTheNumberingCopyUidStatedTest {

    // ---- the witness: the destination folder was never SELECTed, and Undo still goes out -------

    /**
     * The whole defect in one run. The INBOX states 42, the Trash states 77, and nothing has ever
     */
    @Test fun `the undo goes out under the numbering COPYUID stated for a folder never selected`() {
        val store = RememberedNumbering()
        var frozen: Long? = null
        var undone: Map<Long, Long> = emptyMap()
        ScriptedImapServer(twoFolders()).use { server ->
            val service = service(store)
            val credentials = credentials(server)
            runBlocking {
                // Yesterday the user browsed the INBOX: 42 is recorded, and the ids belong to it.
                assertEquals(42L, service.snapshotUids(credentials, INBOX, 100).uidValidity)

                // The gesture: delete = move to the Trash, opposing what the INBOX stated.
                val landed = service.moveBatch(credentials, INBOX, UIDS, TRASH, 42L)
                assertEquals(
                    "the forward move must report where each message landed",
                    mapOf(7L to 100L, 8L to 101L, 9L to 102L), landed.uids,
                )
                assertEquals(
                    "and the destination's numbering, which is the only thing the Undo can oppose",
                    77L, landed.destinationUidValidity,
                )
                assertNull(
                    "⭐ and the app must still know NOTHING about the Trash's numbering by way of a " +
                        "read: it was never SELECTed. This is what makes the assertion below mean " +
                        "something — a green run here cannot be the recorded value in disguise",
                    service.recordedUidValidity(ACCOUNT, TRASH),
                )

                // What MailRepository writes into lastImapMove (pinned as source in
                // ImapMoveUnderNumberingTest), and the shipped routing decision, executed.
                val landings = UIDS.associate { uid ->
                    idOf(uid) to ImapLoc(TRASH, landed.uids.getValue(uid), landed.destinationUidValidity)
                }
                val routes = UidValidity.imapUndoRoutes(UIDS.map { idOf(it) to INBOX }) { landings[it] }
                assertEquals("one folder, one numbering, one move back", 1, routes.size)
                frozen = routes.single().frozenUidValidity

                // The Undo itself, as restoreAll makes it.
                val route = routes.single()
                undone = service.moveBatch(
                    credentials, route.currentFolder, route.uids, route.sourceFolder, route.frozenUidValidity,
                ).uids
            }

            assertEquals(
                "the move back must REACH THE WIRE. Refused, the mail stays in the Trash, the user " +
                    "is told the restore failed, and there is no second chance — this is the " +
                    "defect entire",
                listOf("UID MOVE 7:9 \"Trash\"", "UID MOVE 100:102 \"INBOX\""),
                server.issued("UID MOVE"),
            )
        }
        assertEquals(
            "the numbering opposed by the move back must be the Trash's, as COPYUID stated it — " +
                "not the INBOX's 42, which is the folder the mail is going TO",
            77L, frozen,
        )
        assertEquals(
            "and the move back must report its own mapping, or nothing gets re-cached",
            mapOf(100L to 200L, 101L to 201L, 102L to 202L), undone,
        )
    }

    /**
     * The inverse witness, and the shape of the first delivery: asking the store what the Trash's
     */
    @Test fun `the same undo, opposing what the store recorded for the trash, is refused`() {
        val store = RememberedNumbering()
        var refusal: Throwable? = null
        ScriptedImapServer(twoFolders()).use { server ->
            val service = service(store)
            val credentials = credentials(server)
            runBlocking {
                service.snapshotUids(credentials, INBOX, 100)
                val landed = service.moveBatch(credentials, INBOX, UIDS, TRASH, 42L)
                val read = service.recordedUidValidity(ACCOUNT, TRASH)
                refusal = runCatching {
                    service.moveBatch(credentials, TRASH, landed.uids.values.toList(), INBOX, read)
                }.exceptionOrNull()
            }

            assertEquals(
                "nothing may go back out under a numbering nobody stated",
                listOf("UID MOVE 7:9 \"Trash\""),
                server.issued("UID MOVE"),
            )
        }
        assertTrue(
            "the read answers null on a folder never SELECTed, and a move that opposes nothing is " +
                "refused — got $refusal",
            refusal is ImapNumberingUnconfirmed,
        )
        assertEquals(TRASH, (refusal as ImapNumberingUnconfirmed).mailbox)
        assertNull((refusal as ImapNumberingUnconfirmed).expected)
    }

    // ---- the mute witness: no COPYUID, no route, and NO new noise ------------------------------

    /**
     * THE GUARD THAT MUST NOT MOVE. A server that reports no `COPYUID` loses the Undo, silently,
     */
    @Test fun `a server reporting no COPYUID loses the undo in silence, as it always did`() {
        val store = RememberedNumbering()
        var routes: List<UidValidity.ImapUndoRoute> = listOf(UidValidity.ImapUndoRoute("x", "y", 1L, emptyList()))
        var failure: Throwable? = null
        ScriptedImapServer(mute()).use { server ->
            val service = service(store)
            val credentials = credentials(server)
            runBlocking {
                assertEquals(42L, service.snapshotUids(credentials, INBOX, 100).uidValidity)
                val landed = service.moveBatch(credentials, INBOX, UIDS, TRASH, 42L)
                assertEquals("no COPYUID means no mapping", emptyMap<Long, Long>(), landed.uids)
                assertNull("and no numbering to freeze", landed.destinationUidValidity)

                // Nothing landed, so lastImapMove holds nothing for these ids — as it did before.
                val landings = emptyMap<String, ImapLoc>()
                failure = runCatching {
                    routes = UidValidity.imapUndoRoutes(UIDS.map { idOf(it) to INBOX }) { landings[it] }
                }.exceptionOrNull()
            }

            assertEquals(
                "the forward move went out and NOTHING went out for the Undo",
                listOf("UID MOVE 7:9 \"Trash\""),
                server.issued("UID MOVE"),
            )
        }
        assertEquals("an id nothing knows about must produce no route at all", emptyList<Any>(), routes)
        assertNull("and no refusal, no exception, no noise on a path that was always mute", failure)
    }

    // ---- the routing decision itself, executed -------------------------------------------------

    /**
     * THE NUMBERING IS PART OF THE ROUTE KEY. Two waves moved into the same Trash either side of
     */
    @Test fun `two numberings for one folder are two move-backs, not one`() {
        val landings = mapOf(
            "a" to ImapLoc(TRASH, 100L, 77L),
            "b" to ImapLoc(TRASH, 101L, 78L),
            "c" to ImapLoc(TRASH, 102L, 77L),
        )

        val routes = UidValidity.imapUndoRoutes(listOf("a" to INBOX, "b" to INBOX, "c" to INBOX)) { landings[it] }

        assertEquals(
            "one route per numbering: merging them sends half these UIDs out under a number they " +
                "never belonged to",
            listOf(
                UidValidity.ImapUndoRoute(TRASH, INBOX, 77L, listOf(100L to "a", 102L to "c")),
                UidValidity.ImapUndoRoute(TRASH, INBOX, 78L, listOf(101L to "b")),
            ),
            routes,
        )
    }

    /** The ordinary split stays what it was: one route per (folder it is in, folder it goes to). */
    @Test fun `messages are routed by where they are and where they go home to`() {
        val landings = mapOf(
            "a" to ImapLoc(TRASH, 100L, 77L),
            "b" to ImapLoc(ARCHIVE, 300L, 77L),
            "c" to ImapLoc(TRASH, 102L, 77L),
        )

        val routes = UidValidity.imapUndoRoutes(listOf("a" to INBOX, "b" to INBOX, "c" to SENT)) { landings[it] }

        assertEquals(
            "same numbering, but three different (current folder, source folder) pairs — the " +
                "numbering widens the key, it must not collapse it",
            listOf(
                UidValidity.ImapUndoRoute(TRASH, INBOX, 77L, listOf(100L to "a")),
                UidValidity.ImapUndoRoute(ARCHIVE, INBOX, 77L, listOf(300L to "b")),
                UidValidity.ImapUndoRoute(TRASH, SENT, 77L, listOf(102L to "c")),
            ),
            routes,
        )
    }

    /** An id with no landing is dropped and takes nothing with it — its neighbours still travel. */
    @Test fun `an id with no landing is dropped without disturbing the others`() {
        val landings = mapOf("a" to ImapLoc(TRASH, 100L, 77L), "c" to ImapLoc(TRASH, 102L, 77L))

        val routes = UidValidity.imapUndoRoutes(listOf("a" to INBOX, "b" to INBOX, "c" to INBOX)) { landings[it] }

        assertEquals(
            listOf(UidValidity.ImapUndoRoute(TRASH, INBOX, 77L, listOf(100L to "a", 102L to "c"))),
            routes,
        )
    }

    /** A landing whose numbering is null still routes — and carries the null, which REFUSES at the
     *  wire. Dropping it here instead would be the same outcome by a quieter road, but it would
     *  hide from the caller that the ids were ever asked for. */
    @Test fun `a landing with no numbering routes, carrying the nothing it has`() {
        val landings = mapOf("a" to ImapLoc(TRASH, 100L, null))

        val routes = UidValidity.imapUndoRoutes(listOf("a" to INBOX)) { landings[it] }

        assertEquals(listOf(UidValidity.ImapUndoRoute(TRASH, INBOX, null, listOf(100L to "a"))), routes)
    }

    // ---- fixtures -----------------------------------------------------------------------------

    /**
     * A server with two folders that state DIFFERENT numberings — the INBOX 42, the Trash 77 —
     */
    private fun twoFolders(): (String, String) -> String = { tag, line ->
        when {
            line.startsWith("LOGIN") -> "$tag OK [CAPABILITY IMAP4rev1 UIDPLUS MOVE] logged in\r\n"
            line.startsWith("CAPABILITY") -> "* CAPABILITY IMAP4rev1 UIDPLUS MOVE\r\n$tag OK done\r\n"
            line.startsWith("SELECT") -> {
                val validity = if (line.contains("\"$TRASH\"")) 77L else 42L
                "* 3 EXISTS\r\n* OK [UIDVALIDITY $validity] ok\r\n* OK [UIDNEXT 300] ok\r\n" +
                    "$tag OK [READ-WRITE] selected\r\n"
            }
            line.startsWith("UID SEARCH") -> "* SEARCH 7 8 9\r\n$tag OK search completed\r\n"
            line.startsWith("UID MOVE") -> "$tag OK [${copyUid(line)}] done\r\n"
            else -> "$tag OK done\r\n"
        }
    }

    /** The same server, minus the one thing under test: it reports no `COPYUID` at all. */
    private fun mute(): (String, String) -> String = { tag, line ->
        when {
            line.startsWith("LOGIN") -> "$tag OK [CAPABILITY IMAP4rev1 UIDPLUS MOVE] logged in\r\n"
            line.startsWith("CAPABILITY") -> "* CAPABILITY IMAP4rev1 UIDPLUS MOVE\r\n$tag OK done\r\n"
            line.startsWith("SELECT") ->
                "* 3 EXISTS\r\n* OK [UIDVALIDITY 42] ok\r\n* OK [UIDNEXT 300] ok\r\n$tag OK [READ-WRITE] selected\r\n"
            line.startsWith("UID SEARCH") -> "* SEARCH 7 8 9\r\n$tag OK search completed\r\n"
            else -> "$tag OK done\r\n"
        }
    }

    /**
     * The `COPYUID` for the set of [line], and the numbering of the folder it is moving INTO:
     */
    private fun copyUid(line: String): String {
        val set = line.removePrefix("UID MOVE ").substringBefore(' ')
        return when (set) {
            "7:9" -> "COPYUID 77 7:9 100:102"
            "100:102" -> "COPYUID 42 100:102 200:202"
            else -> error("unscripted UID set: $set")
        }
    }

    private fun idOf(uid: Long) = ImapMailService.emailId(ACCOUNT, INBOX, uid)

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
        const val TRASH = "Trash"
        const val ARCHIVE = "Archive"
        const val SENT = "Sent"
        val UIDS = listOf(7L, 8L, 9L)

        /** See the twin in [MoveNeedsTheNumberingStatedNowTest] — `ImapMailService` requires an
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
