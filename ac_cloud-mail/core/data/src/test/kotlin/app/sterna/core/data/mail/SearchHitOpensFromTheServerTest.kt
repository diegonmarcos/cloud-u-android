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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A hit of a SERVER-side search opens** (#159), which until now it did not: the folder it lives
 */
class SearchHitOpensFromTheServerTest {

    // ---- 1. the decision, executed: what an id alone says to ask the server for -----------------

    /**
     * Literal couples, never a recomputation. A test that split the id itself and compared
     */
    @Test fun anOrdinaryIdNamesItsFolderAndItsUid() {
        assertEquals(
            "the folder AND the uid, both — a target that keeps only one of the two sends the " +
                "fetch to the wrong place or asks for the wrong message",
            "Archive/2024" to 4242L,
            ImapMailService.targetOf("imap:acc1:Archive/2024:4242"),
        )
    }

    @Test fun aFolderWhoseNameHoldsColonsIsNotTruncated() {
        // The id's own separator inside the folder name. Splitting on the FIRST ':' after the
        // account, or on the last two, gives "INBOX" or "2024" here — a SELECT of a folder that
        // either does not exist or is somebody else's.
        assertEquals(
            "everything between the account and the trailing uid is the path, colons included",
            "INBOX:2024:Archive" to 42L,
            ImapMailService.targetOf("imap:acc:INBOX:2024:Archive:42"),
        )
    }

    @Test fun aLocalDraftIdIsNoTargetAtAll() {
        // The one copy of a text that exists nowhere else. Nothing about it may go on the wire.
        assertNull(ImapMailService.targetOf("local-draft:9f1c-2b7e"))
    }

    @Test fun aTruncatedIdIsNoTargetEither() {
        assertNull("no folder, no uid: 'imap:acc'", ImapMailService.targetOf("imap:acc"))
        assertNull("a uid that is not a number", ImapMailService.targetOf("imap:acc:INBOX:seven"))
        assertNull("and an empty id names nothing", ImapMailService.targetOf(""))
    }

    /**
     * The segment [ImapMailService.targetOf] throws away, pinned on its own: a folder and a UID
     */
    @Test fun anIdAlsoNamesTheAccountItWasBuiltFor() {
        assertEquals("acc1", ImapMailService.accountOf("imap:acc1:Archive/2024:4242"))
        assertEquals(
            "the account is the segment BEFORE the path, colons in the path or not",
            "acc", ImapMailService.accountOf("imap:acc:INBOX:2024:Archive:42"),
        )
        assertNull("a local draft belongs to no IMAP account", ImapMailService.accountOf("local-draft:9f1c-2b7e"))
        assertNull("and a truncated id names none either", ImapMailService.accountOf("imap:acc"))
        assertNull(ImapMailService.accountOf(""))
    }

    // ---- 2. what actually goes on the wire ------------------------------------------------------

    @Test(timeout = 30_000)
    fun `the fetch selects the folder the id names and asks for the uid the id names`() {
        // Not "a SELECT happened": WHICH folder and WHICH uid. Swap the two arguments at the
        // declaration of the fetch and it still compiles — the SELECT would then name "4242" and
        // the FETCH the folder — so the command lines the server RECEIVED are what is compared.
        folderHolding(uid = UID).use { server ->
            val entity = runBlocking { service().fetchById(credentials(server), ID) }
                ?: error("the folder holds UID $UID; the fetch must bring its envelope back")
            assertEquals(
                "the commands are a SELECT of THIS folder and a UID FETCH of THIS uid — nothing " +
                    "else, and neither of them naming the other's value. Commands were: " +
                    "${server.issued()}",
                listOf(
                    // Every session opens by asking whether the server knows the RFC 2971 ID
                    // command (#173); this fixture answers without it, so nothing is named.
                    "CAPABILITY",
                    "SELECT \"$FOLDER\"",
                    "UID FETCH $UID (UID FLAGS INTERNALDATE ENVELOPE BODYSTRUCTURE BODY.PEEK[HEADER.FIELDS (REFERENCES)])",
                ),
                server.issued(),
            )
            assertEquals("the row is keyed by the id the reader opened", ID, entity.id)
            assertEquals("and carries the folder it was found in", FOLDER, entity.mailboxId)
            assertEquals("the account it was fetched for", ACCOUNT, entity.accountId)
            assertEquals("the scripted subject", "Quarterly figures", entity.subject)
            assertEquals("and the scripted sender", "dana@example.org", entity.fromEmail)
        }
    }

    @Test(timeout = 30_000)
    fun `an id that is not an IMAP id costs neither a connection nor a command`() {
        // The witness of part 1, executed one level up: a local draft must not even cost a
        // connection, let alone a SELECT of a folder called "local-draft".
        folderHolding(uid = UID).use { server ->
            val entity = runBlocking { service().fetchById(credentials(server), "local-draft:9f1c") }
            assertNull("nothing to fetch for an id the server knows nothing about", entity)
            assertEquals("no socket was opened", 0, server.connections.get())
            assertEquals(
                "and nothing was sent asking. Commands were: ${server.issued()}",
                emptyList<String>(), server.issued(),
            )
        }
    }

    @Test(timeout = 30_000)
    fun `an id belonging to another account is refused before a single packet`() {
        // THE partition, and it is load-bearing precisely because the cache read this fetch
        // stands in for carried it: `emailsByIds(credentials.id, …)` finds nothing for another
        //
        // Reachable without contrivance: MessageViewModel opens with the CURRENT account whenever
        // the intent carries no account id, which is what a notification without
        // EXTRA_OPEN_ACCOUNT_ID gives it.
        folderHolding(uid = UID).use { server ->
            val entity = runBlocking {
                service().fetchById(credentials(server), "imap:acc2:$FOLDER:$UID")
            }
            assertNull("$ACCOUNT's connection must not answer for acc2's id", entity)
            assertEquals(
                "and the refusal costs nothing: no socket, hence no LOGIN either — which " +
                    "`issued()` could not have told us, it filters LOGIN out",
                0, server.connections.get(),
            )
            assertEquals(
                "no command at all. Commands were: ${server.issued()}",
                emptyList<String>(), server.issued(),
            )
        }
    }

    @Test(timeout = 30_000)
    fun `a uid the folder no longer holds comes back as no message, not as some other one`() {
        // The server answers the FETCH with no message at all: destroyed since the search. The
        // caller turns that into "unavailable"; what must never happen is a neighbouring message.
        ScriptedImapServer { tag, line ->
            when {
                line.startsWith("LOGIN") -> "$tag OK logged in\r\n"
                line.startsWith("SELECT") -> selected(uidValidity = 9)(tag)
                else -> "$tag OK done\r\n"
            }
        }.use { server ->
            assertNull(runBlocking { service().fetchById(credentials(server), ID) })
        }
    }

    // ---- 3. the UIDVALIDITY guard, executed ------------------------------------------------------

    @Test(timeout = 30_000)
    fun `a folder the server has renumbered refuses the fetch instead of answering a message`() {
        // THE guard of #99, which is exactly why this fetch goes through `fetchByUid` and its
        // `onMailbox` rather than a bare session + SELECT. A UID belongs to a numbering: under a
        // new UIDVALIDITY the same number names a DIFFERENT message, and handing it to the reader
        // shows one person's mail under another's header.
        //
        // What this proves, exactly: the fixture PRELOADS the numbering (9), so the guard has
        // something to compare against. #159's own case does NOT reach that state — the folder of
        val numbering = RememberedNumbering().apply { numbers["$ACCOUNT/$FOLDER"] = 9L }
        ScriptedImapServer { tag, line ->
            when {
                line.startsWith("LOGIN") -> "$tag OK logged in\r\n"
                // The folder was recreated: same name, new numbering.
                line.startsWith("SELECT") -> selected(uidValidity = 77)(tag)
                line.startsWith("UID FETCH") -> envelope(UID) + "$tag OK fetch completed\r\n"
                else -> "$tag OK done\r\n"
            }
        }.use { server ->
            assertThrows(
                ImapUidValidityChanged::class.java,
            ) { runBlocking { service(numbering).fetchById(credentials(server), ID) } }
            assertEquals(
                "and it refuses BEFORE asking for the message — a fetch issued here has already " +
                    "read somebody else's mail off the wire. Commands were: ${server.issued()}",
                emptyList<String>(), server.issued("UID FETCH"),
            )
            assertTrue(
                "the renumbering is recorded as an invalidation, so the caches keyed by the old " +
                    "UIDs go",
                "$ACCOUNT/$FOLDER" in numbering.invalidated,
            )
        }
    }

    // ---- 4. the shape of openEmailImap — source text, the last resort ----------------------------

    /**
     * THIS PART READS SOURCE TEXT, for [DraftReceiptWiringTest]'s reason and with its rules:
     */
    private fun openBody(): String = DaoQuerySource.mailFunctionBody("MailRepository", "openEmailImap")

    /** The code lines of [body], comments and braces dropped, in source order. */
    private fun codeLines(body: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot {
                it.isEmpty() || it == "{" || it == "}" ||
                    it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
            }

    private fun codeLinesNaming(body: String, needle: String): List<String> =
        codeLines(body).filter { needle in it }

    @Test fun theFirstThingTheOpenDoesIsRefuseAnIdThatIsNotAnImapMessage() {
        // THE half that matters is the POSITION. This guard used to be third, and what stopped a
        // `local-draft:` id from reaching the server was the cache miss above it — which now goes
        assertEquals(
            "the uid guard must be the FIRST code line of openEmailImap: " +
                codeLines(openBody()).take(3),
            "val uid = ImapMailService.uidOf(emailId) ?: error(\"Not an IMAP message.\")",
            codeLines(openBody()).first(),
        )
    }

    @Test fun theCacheMissIsNoLongerTheEndOfTheOpen() {
        val body = openBody()
        assertEquals(
            "the cache read must be able to answer null — an `?: error(...)` on it IS #159",
            listOf(
                "val cached = emailDao.emailsByIds(credentials.id, listOf(emailId)).firstOrNull()?.toEmail()",
            ),
            codeLinesNaming(body, "emailsByIds"),
        )
        assertEquals(
            "and no line of the open may still turn a cache miss into that message",
            emptyList<String>(),
            codeLinesNaming(body, "Message is not in the cache."),
        )
    }

    @Test fun theEnvelopeOfAMessageTheCacheLacksComesFromTheServer() {
        assertEquals(
            "the fetch is for THIS account and THIS id, and it is mapped to an Email — a literal " +
                "or a neighbouring id here opens somebody else's message",
            listOf("imap.fetchById(credentials, emailId)?.toEmail()"),
            codeLinesNaming(openBody(), "fetchById("),
        )
    }

    @Test fun theServerIsAskedWhenTheCacheHasNothingAndOnlyThen() {
        // The CONDITION, which the three assertions above cannot see: they pin the call, the
        // cache read and the absence of the old message, and every one of them survives
        assertEquals(
            "the server is the fallback for a cache MISS, never the first stop nor the answer to " +
                "a hit",
            listOf("val fetched = if (cached != null) null else try {"),
            codeLinesNaming(openBody(), "val fetched ="),
        )
    }

    @Test fun openingAMessageFilesNoRowInTheEmailsTable() {
        // We go and GET the envelope to show it; we do not file it. A row of a folder that was
        // never synced would be exposed to the next `reconcileMailbox` (which deletes what its walk
        val body = openBody()
        assertEquals(
            "no write of the fetched envelope may appear in the open",
            emptyList<String>(),
            listOf("upsertAll", "markRecentlyMutated", "insert")
                .filter { codeLinesNaming(body, it).isNotEmpty() },
        )
    }

    // ---- the harness -----------------------------------------------------------------------------

    /** A server whose [FOLDER] holds exactly [uid], under UIDVALIDITY 9. */
    private fun folderHolding(uid: Long) = ScriptedImapServer { tag, line ->
        when {
            line.startsWith("LOGIN") -> "$tag OK logged in\r\n"
            line.startsWith("SELECT") -> selected(uidValidity = 9)(tag)
            line.startsWith("UID FETCH $uid ") -> envelope(uid) + "$tag OK fetch completed\r\n"
            else -> "$tag OK done\r\n"
        }
    }

    private fun selected(uidValidity: Long): (String) -> String = { tag ->
        "* 12 EXISTS\r\n* OK [UIDVALIDITY $uidValidity] ok\r\n$tag OK [READ-WRITE] selected\r\n"
    }

    /** One `UID FETCH` row: the ten-slot ENVELOPE, subject and From in their own slots. */
    private fun envelope(uid: Long) =
        "* 7 FETCH (UID $uid FLAGS () INTERNALDATE \"01-Jun-2026 10:00:00 +0000\" " +
            "ENVELOPE (\"Mon, 1 Jun 2026 10:00:00 +0000\" \"Quarterly figures\" " +
            "((\"Dana\" NIL \"dana\" \"example.org\")) NIL NIL " +
            "((\"Alex\" NIL \"alex\" \"example.org\")) NIL NIL NIL \"<q3@example.org>\") " +
            "BODYSTRUCTURE (\"text\" \"plain\" (\"charset\" \"utf-8\") NIL NIL \"7bit\" 12 1))\r\n"

    private fun service(store: UidValidityStore = UidValidityStore.None) =
        ImapMailService(ImapClient(), SmtpClient(), unusedTokenRefresher(), store)

    private fun credentials(server: ScriptedImapServer) = AccountCredentials(
        server = "scripted",
        username = "alex@example.org",
        password = "secret",
        id = ACCOUNT,
        protocol = MailProtocol.IMAP,
        imap = MailEndpoint(server.host, server.port, ConnectionSecurity.NONE),
    )

    /** [DestroyNeedsTheNumberingStatedNowTest]'s store, minus Room. */
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

        /** A folder a server-side search can hit and a sync has never walked — #159's whole case. */
        const val FOLDER = "Archive/2024"
        const val UID = 4242L
        const val ID = "imap:acc1:Archive/2024:4242"

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
