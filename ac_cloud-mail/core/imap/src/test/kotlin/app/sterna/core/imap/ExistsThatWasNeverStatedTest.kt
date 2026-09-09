package app.sterna.core.imap

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A folder walk that read NOTHING is not a walk over an empty folder — on the wire, where the
 */
class ExistsThatWasNeverStatedTest {

    /** A SELECT answer whose untagged lines are exactly [untagged] — EXISTS included, or not. */
    private fun selecting(untagged: String, onFetch: (String, String) -> String = { tag, _ -> ok(tag) }) =
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> untagged + "$tag OK [READ-WRITE] selected\r\n"
                line.startsWith("FETCH") -> onFetch(tag, line)
                else -> ok(tag)
            }
        }

    private fun walked(server: FakeImapServer, limit: Int = 1000): Pair<ImapMailboxStatus, ImapFolderWalk> =
        server.session().use { session ->
            val status = session.select("INBOX")
            status to runBlocking { session.walkFolder(status, limit, pageSize = 200) {} }
        }

    // -- forme 1: the SELECT never said how big the folder is -----------------------------------------

    @Test fun `a SELECT with no EXISTS line at all leaves the folder's size UNKNOWN, not zero`() {
        // The case. Nothing failed, nothing was fetched, and the count reads exactly like an
        // empty folder. The walk must say so: `folderStatedEmpty` false is what stops the caller
        // deleting the whole cached folder.
        selecting("* OK [UIDVALIDITY 1] ok\r\n* OK [UIDNEXT 9] ok\r\n").use { server ->
            val (status, walk) = walked(server)

            assertFalse("the server never stated a count; select() claims it did", status.existsObserved)
            assertEquals(0, status.exists)
            assertEquals(emptyList<Long>(), walk.uids)
            assertFalse(
                "a folder whose size the server never stated is reported as empty: the reconcile " +
                    "that follows DELETES every cached row of it",
                walk.folderStatedEmpty,
            )
        }
    }

    @Test fun `an EXISTS whose count will not parse says nothing either`() {
        // `* NIL EXISTS`, and any other unparseable n. `folderMoved` already treats such a line as
        // movement rather than as a number; select() used to treat it as zero.
        selecting("* NIL EXISTS\r\n* OK [UIDVALIDITY 1] ok\r\n").use { server ->
            val (status, walk) = walked(server)

            assertFalse("an unparseable EXISTS was taken as a stated count", status.existsObserved)
            assertFalse("an unparseable EXISTS was taken as an empty folder", walk.folderStatedEmpty)
        }
    }

    // -- forme 2: the count was stated, and every FETCH came back unreadable ---------------------------

    @Test fun `a thousand messages and no readable FETCH is still not an empty folder`() {
        // The shape a fix that only looks at EXISTS misses. The count is stated and large; the
        // responses carry no UID, so `messages()` drops all of them and the walk ends with nothing.
        // Empty UIDs, and NOT an empty folder.
        val server = selecting("* 1000 EXISTS\r\n* OK [UIDVALIDITY 1] ok\r\n") { tag, _ ->
            "* 12 FETCH (FLAGS (\\Seen))\r\n$tag OK fetch completed\r\n"
        }
        server.use {
            val (status, walk) = walked(server)

            assertTrue("this fixture does not state a count at all", status.existsObserved)
            assertEquals(1000, status.exists)
            assertEquals("the fixture returned readable messages; it must return none", emptyList<Long>(), walk.uids)
            assertEquals(
                "the fixture did not walk the five pages of the window it claims to hold",
                5, server.issued().count { it.startsWith("FETCH") },
            )
            assertFalse(
                "a folder of a thousand messages whose pages were all unreadable is reported empty",
                walk.folderStatedEmpty,
            )
        }
    }

    // -- the witness: a folder that really is empty ------------------------------------------------

    @Test fun `a SELECT that really said zero DOES state an empty folder — the witness`() {
        // Without this, "never say the folder is empty" would pass every assertion above, the
        // cache would never be cleared again, and an emptied folder would show its old contents for
        // ever. `* 0 EXISTS` RECEIVED is a fact, and it licenses the delete.
        selecting("* 0 EXISTS\r\n* OK [UIDVALIDITY 1] ok\r\n").use { server ->
            val (status, walk) = walked(server)

            assertTrue("a stated `* 0 EXISTS` was not recorded as stated", status.existsObserved)
            assertEquals(emptyList<Long>(), walk.uids)
            assertTrue(
                "a folder the server said holds nothing cannot clear its stale cache any more",
                walk.folderStatedEmpty,
            )
        }
    }

    @Test fun `a folder with messages in it is walked as before, and states nothing empty`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                line.startsWith("FETCH") -> fetchResponse(tag, listOf(1L, 2L, 3L)) { false }
                else -> ok(tag)
            }
        }.use { server ->
            val (status, walk) = walked(server)

            assertTrue(status.existsObserved)
            assertEquals(listOf(3L, 2L, 1L), walk.uids)
            assertFalse("a folder holding mail was reported as stated-empty", walk.folderStatedEmpty)
        }
    }

    // -- the default of both new fields ---------------------------------------------------------------

    @Test fun `a status and a walk built without naming the field mean UNKNOWN, never empty`() {
        // The dangerous default, executed rather than read: "observed" by default would let any
        // future fixture, or any future construction site, license a delete by omission.
        assertFalse(ImapMailboxStatus(exists = 0, uidValidity = 1L, uidNext = 1L).existsObserved)
        assertFalse(ImapFolderWalk(uids = emptyList(), moved = false).folderStatedEmpty)
    }
}
