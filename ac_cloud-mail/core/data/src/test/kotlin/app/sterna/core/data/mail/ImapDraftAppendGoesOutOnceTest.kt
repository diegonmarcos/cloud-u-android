package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.OutgoingMessage
import app.sterna.core.imap.SmtpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **What a REAL [ImapMailService] puts on the wire for a draft** (#95), executed against a
 */
class ImapDraftAppendGoesOutOnceTest {

    // ---- 1. the APPEND of a draft is not replayed inside the call ----------------------------------

    @Test(timeout = 30_000)
    fun `an APPEND the server refuses leaves the bytes on the wire exactly once`() {
        // The server TAKES the message and then refuses — the reachable shape of the defect. A
        // tagged NO, a link that dies after the payload, a read timeout: the client cannot tell
        // them apart, and in all three the server may well be holding the draft already.
        ScriptedImapServer { tag, line ->
            when {
                line.startsWith("LOGIN") -> "$tag OK logged in\r\n"
                line.startsWith("APPEND") -> "$tag NO [UNAVAILABLE] server busy, try later\r\n"
                else -> "$tag OK done\r\n"
            }
        }.use { server ->
            assertThrows(Exception::class.java) {
                runBlocking { service().appendDraft(credentials(server), DRAFTS, message()) }
            }
            assertEquals(
                "⛔ one save gesture, one APPEND. A second one inside the same call is a second " +
                    "draft on the server that no retry and no search ever saw, and the phone has " +
                    "no id for either. Commands were: ${server.issued()}",
                1, server.issued("APPEND").size,
            )
            assertEquals("and one MIME payload with it", 1, server.literals.size)
            assertTrue(
                "the payload is the draft's own, named by the row's Message-ID",
                server.literals.single().contains("<$MESSAGE_ID>"),
            )
        }
    }

    @Test(timeout = 30_000)
    fun `every other caller keeps the one reconnect it has always had`() {
        // The witness. The fix is per-CALL, not a change of the reconnect policy: a stale pooled
        // connection that dies on the first command must still cost a reconnect and not an error.
        ScriptedImapServer { tag, line ->
            when {
                line.startsWith("LOGIN") -> "$tag OK logged in\r\n"
                line.startsWith("LIST") -> "$tag NO temporary failure\r\n"
                else -> "$tag OK done\r\n"
            }
        }.use { server ->
            assertThrows(Exception::class.java) {
                runBlocking { service().testConnection(credentials(server)) }
            }
            assertEquals(
                "a read that fails is still tried once more on a fresh connection",
                2, server.issued("LIST").size,
            )
        }
    }

    // ---- 2. the lookup, with its arguments, as the server sees them ---------------------------------

    @Test(timeout = 30_000)
    fun `the draft is found by enumerating the folder, and no HEADER key is ever sent`() {
        // THE test of this volet. The bench measurement behind it: Stalwart answers an EMPTY
        // `* SEARCH` to every shape of `HEADER "Message-ID"` — naked, bracketed, substring, on a
        ScriptedImapServer { tag, line ->
            when {
                line.startsWith("LOGIN") -> "$tag OK logged in\r\n"
                line.startsWith("SELECT") ->
                    "* 3 EXISTS\r\n* OK [UIDVALIDITY 9] ok\r\n$tag OK [READ-WRITE] selected\r\n"
                line.startsWith("UID SEARCH ALL") -> "* SEARCH 4 9 11\r\n$tag OK search completed\r\n"
                line.startsWith("UID FETCH") ->
                    envelope(4, "<older@example.org>") +
                        envelope(9, "<other@example.org>") +
                        envelope(11, "<$MESSAGE_ID>") +
                        "$tag OK fetch completed\r\n"
                else -> "$tag OK done\r\n"
            }
        }.use { server ->
            val there = runBlocking {
                service().draftIsAlreadyThere(credentials(server), DRAFTS, MESSAGE_ID)
            }
            assertTrue(
                "⛔ the draft IS in the folder (UID 11 carries <$MESSAGE_ID>) and the guard must " +
                    "say so from the envelopes alone. Commands were: ${server.issued()}",
                there,
            )
            assertEquals(
                "and it gets there by enumerating the folder and reading the envelopes back — " +
                    "`ALL` is the one search key every server implements",
                listOf(
                    // Every session opens by asking whether the server knows the RFC 2971 ID
                    // command (#173); this fixture answers without it, so nothing is named.
                    "CAPABILITY",
                    "SELECT \"$DRAFTS\"",
                    "UID SEARCH ALL",
                    "UID FETCH 4,9,11 (UID FLAGS INTERNALDATE ENVELOPE BODYSTRUCTURE BODY.PEEK[HEADER.FIELDS (REFERENCES)])",
                ),
                server.issued(),
            )
        }
    }

    @Test(timeout = 30_000)
    fun `a folder holding other drafts than ours answers not there`() {
        // The witness the one above cannot be without: a folder read in full, envelopes and all,
        // whose ids are simply not ours. "Already there" here would skip the append, the caller
        // would consume the row, and the text would exist nowhere.
        ScriptedImapServer { tag, line ->
            when {
                line.startsWith("LOGIN") -> "$tag OK logged in\r\n"
                line.startsWith("SELECT") ->
                    "* 3 EXISTS\r\n* OK [UIDVALIDITY 9] ok\r\n$tag OK [READ-WRITE] selected\r\n"
                line.startsWith("UID SEARCH ALL") -> "* SEARCH 4 9\r\n$tag OK search completed\r\n"
                line.startsWith("UID FETCH") ->
                    // The second one CONTAINS our id and is a different message all the same.
                    envelope(4, "<older@example.org>") +
                        envelope(9, "<x-$MESSAGE_ID>") +
                        "$tag OK fetch completed\r\n"
                else -> "$tag OK done\r\n"
            }
        }.use { server ->
            assertFalse(
                "neither <older@example.org> nor <x-$MESSAGE_ID> is <$MESSAGE_ID>",
                runBlocking { service().draftIsAlreadyThere(credentials(server), DRAFTS, MESSAGE_ID) },
            )
        }
    }

    @Test(timeout = 30_000)
    fun `an empty Drafts folder answers not there and fetches nothing`() {
        ScriptedImapServer { tag, line ->
            when {
                line.startsWith("LOGIN") -> "$tag OK logged in\r\n"
                line.startsWith("SELECT") ->
                    "* 0 EXISTS\r\n* OK [UIDVALIDITY 9] ok\r\n$tag OK [READ-WRITE] selected\r\n"
                line.startsWith("UID SEARCH ALL") -> "* SEARCH\r\n$tag OK search completed\r\n"
                else -> "$tag OK done\r\n"
            }
        }.use { server ->
            val there = runBlocking {
                service().draftIsAlreadyThere(credentials(server), DRAFTS, MESSAGE_ID)
            }
            assertFalse("an empty folder holds no draft of ours — append it", there)
            assertEquals(
                "and nothing is fetched to find that out",
                // Every session opens by asking whether the server knows the RFC 2971 ID
                // command (#173); this fixture answers without it, so nothing is named.
                listOf("CAPABILITY", "SELECT \"$DRAFTS\"", "UID SEARCH ALL"),
                server.issued(),
            )
        }
    }

    @Test(timeout = 30_000)
    fun `a folder that cannot be read fails, instead of claiming the draft is there`() {
        // The error has to reach the caller: `appendDraftUnlessAlreadyThere` reads a throw as
        // "append it", and that is the only safe reading of "I could not look". A `runCatching`
        // anywhere on this path would turn an unreadable folder into "already there".
        ScriptedImapServer { tag, line ->
            when {
                line.startsWith("LOGIN") -> "$tag OK logged in\r\n"
                line.startsWith("SELECT") ->
                    "* 3 EXISTS\r\n* OK [UIDVALIDITY 9] ok\r\n$tag OK [READ-WRITE] selected\r\n"
                line.startsWith("UID SEARCH ALL") -> "$tag NO [SERVERBUG] search unavailable\r\n"
                else -> "$tag OK done\r\n"
            }
        }.use { server ->
            assertThrows(Exception::class.java) {
                runBlocking { service().draftIsAlreadyThere(credentials(server), DRAFTS, MESSAGE_ID) }
            }
        }
    }

    // ---- 3. a Drafts folder DEEPER than the lookup reads --------------------------------------------

    @Test(timeout = 60_000)
    fun `a draft a few from the newest is found in a folder deeper than the lookup cap`() {
        // THE cap, executed rather than described. Every other fixture here scripts a folder of
        // two or three UIDs, i.e. one no cap ≥ 3 can ever truncate: the constant could be set to
        val total = ImapMailService.DRAFT_LOOKUP_SCAN + DEEPER_BY
        val ours = (total - OURS_FROM_NEWEST + 1).toLong()
        deepDraftsFolder(total, ours).use { server ->
            val there = runBlocking {
                service().draftIsAlreadyThere(credentials(server), DRAFTS, MESSAGE_ID)
            }
            assertTrue(
                "⛔ UID $ours of a $total-message Drafts folder carries <$MESSAGE_ID> and is the " +
                    "${OURS_FROM_NEWEST}th newest, so the lookup MUST see it: reading the head of " +
                    "the folder, or a cap under $OURS_FROM_NEWEST, looks where the copy is not, " +
                    "answers 'not there' and appends the second copy this volet exists to stop. " +
                    "Commands were: ${server.issued()}",
                there,
            )
        }
    }

    @Test(timeout = 60_000)
    fun `a draft older than the lookup cap is not found, so the caller appends`() {
        // The other side of the same limit, and the safe one: past the cap the honest answer is
        // "I did not look", whose only safe reading is "append it" (a duplicate is visible and
        // deletable; text abstained over is gone). Until now that lived in the KDoc only.
        val total = ImapMailService.DRAFT_LOOKUP_SCAN + DEEPER_BY
        deepDraftsFolder(total, ours = 1L).use { server ->
            val there = runBlocking {
                service().draftIsAlreadyThere(credentials(server), DRAFTS, MESSAGE_ID)
            }
            assertFalse(
                "UID 1 is the OLDEST of $total and carries <$MESSAGE_ID>; the lookup does not go " +
                    "that deep, so it must answer 'not there' rather than pretend. Commands were: " +
                    "${server.issued()}",
                there,
            )
            // The ARGUMENT the answer rests on: what the client actually asked the server for.
            val fetched = server.issued("UID FETCH").single().let(::uidsNamedBy)
            assertEquals(
                "the fetch names as many UIDs as the lookup is allowed to read, no more and no " +
                    "fewer, out of a folder of $total: ${server.issued("UID FETCH")}",
                ImapMailService.DRAFT_LOOKUP_SCAN, fetched.size,
            )
            assertEquals(
                "and ours, the oldest, is not among them — which is WHY the answer above is 'no'",
                emptyList<String>(), fetched.filter { it == "1" },
            )
        }
    }

    // ---- the harness --------------------------------------------------------------------------------

    /**
     * A Drafts folder of [total] messages, UID 1..[total], of which only UID [ours] carries our
     */
    private fun deepDraftsFolder(total: Int, ours: Long) = ScriptedImapServer { tag, line ->
        when {
            line.startsWith("LOGIN") -> "$tag OK logged in\r\n"
            line.startsWith("SELECT") ->
                "* $total EXISTS\r\n* OK [UIDVALIDITY 9] ok\r\n$tag OK [READ-WRITE] selected\r\n"
            line.startsWith("UID SEARCH ALL") ->
                "* SEARCH ${(1..total).joinToString(" ")}\r\n$tag OK search completed\r\n"
            line.startsWith("UID FETCH") ->
                uidsNamedBy(line).joinToString("") { uid ->
                    val id = if (uid.toLong() == ours) "<$MESSAGE_ID>" else "<draft-$uid@example.org>"
                    envelope(uid.toLong(), id)
                } + "$tag OK fetch completed\r\n"
            else -> "$tag OK done\r\n"
        }
    }

    /** The UIDs a `UID FETCH 8,9,10 (…)` line names, as written. */
    private fun uidsNamedBy(line: String): List<String> =
        line.substringAfter("UID FETCH ").substringBefore(" (").split(",")

    /** One `UID FETCH` row: the ten-slot ENVELOPE, whose last slot is the `Message-ID` verbatim. */
    private fun envelope(uid: Long, messageId: String) =
        "* $uid FETCH (UID $uid FLAGS () INTERNALDATE \"01-Jun-2026 10:00:00 +0000\" " +
            "ENVELOPE (\"Mon, 1 Jun 2026 10:00:00 +0000\" \"Half written\" " +
            "((\"Alex\" NIL \"alex\" \"example.org\")) NIL NIL " +
            "((\"Bob\" NIL \"bob\" \"example.org\")) NIL NIL NIL \"$messageId\") " +
            "BODYSTRUCTURE (\"text\" \"plain\" (\"charset\" \"utf-8\") NIL NIL \"7bit\" 12 1))\r\n"

    private fun service() = ImapMailService(ImapClient(), SmtpClient(), unusedTokenRefresher())

    private fun credentials(server: ScriptedImapServer) = AccountCredentials(
        server = "scripted",
        username = "alex@example.org",
        password = "secret",
        id = "acc",
        protocol = MailProtocol.IMAP,
        imap = MailEndpoint(server.host, server.port, ConnectionSecurity.NONE),
    )

    private fun message() = OutgoingMessage(
        from = "alex@example.org",
        to = listOf("bob@example.org"),
        subject = "Half written",
        body = "I will be there at six",
        messageId = MESSAGE_ID,
        dateMillis = 1_760_000_000_000L,
    )

    private companion object {
        const val DRAFTS = "Drafts"
        const val MESSAGE_ID = "abc-123@example.org"

        /**
         * How much deeper than [ImapMailService.DRAFT_LOOKUP_SCAN] the deep fixture's folder goes,
         */
        const val DEEPER_BY = 7
        const val OURS_FROM_NEWEST = 7

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
