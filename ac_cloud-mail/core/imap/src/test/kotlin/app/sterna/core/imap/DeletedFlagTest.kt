package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * A message flagged `\Deleted` must not appear in any list, driven against a scripted server on
 */
class DeletedFlagTest {

    private val folderSize = 5
    private val deletedUids = setOf(2L, 4L)

    /**
     * A folder whose [deleted] uids carry `\Deleted`. The set is mutable so a test can have
     * another client clear a flag mid-session, which is the only way a hidden message comes back.
     */
    private fun folderServer(deleted: MutableSet<Long>) = FakeImapServer { tag, line ->
        val flagsOf = { uid: Long -> if (uid in deleted) "\\Seen \\Deleted" else "\\Seen" }
        when {
            line.startsWith("SELECT") -> selectResponse(tag, exists = folderSize)
            line.startsWith("UID SEARCH ALL") -> searchResponse(tag, (1L..folderSize).toList())
            line.startsWith("SEARCH UNSEEN") -> searchResponse(tag, listOf(1L, 3L))
            line.startsWith("UID FETCH") ->
                fetchResponse(tag, uidsOf(line), flags = flagsOf) { false }
            line.startsWith("FETCH") ->
                fetchResponse(tag, (1L..folderSize).toList(), flags = flagsOf) { false }
            else -> ok(tag)
        }
    }

    private fun mutableDeleted(): MutableSet<Long> =
        Collections.synchronizedSet(mutableSetOf<Long>().apply { addAll(deletedUids) })

    /** The folder list: what the server says is on its way out is not shown. */
    @Test
    fun `a message flagged deleted is left out of the folder page`() {
        folderServer(mutableDeleted()).use { server ->
            val page = server.session().use { session ->
                val status = session.select("Trash")
                session.fetchPage(status.exists, offset = 0, limit = 50)
            }

            assertEquals(listOf(5L, 3L, 1L), page.map { it.uid })
        }
    }

    /**
     * The whole point of the exercise: fetching the folder again does not bring them back. Not a
     * cache — the filter is at the fetch, so every refresh takes the same decision on fresh data.
     */
    @Test
    fun `they stay out on the next refresh`() {
        folderServer(mutableDeleted()).use { server ->
            val (first, second) = server.session().use { session ->
                val status = session.select("Trash")
                session.fetchPage(status.exists, 0, 50).map { it.uid } to
                    session.fetchPage(status.exists, 0, 50).map { it.uid }
            }

            assertEquals(listOf(5L, 3L, 1L), first)
            assertEquals(first, second)
        }
    }

    /** And they come back the moment another client clears the flag — nothing was cached. */
    @Test
    fun `clearing the flag elsewhere brings the message back`() {
        val deleted = mutableDeleted()
        folderServer(deleted).use { server ->
            val (hidden, restored) = server.session().use { session ->
                val status = session.select("Trash")
                val hidden = session.fetchPage(status.exists, 0, 50).map { it.uid }
                deleted.remove(4L) // Thunderbird, on the same account, un-deletes it.
                hidden to session.fetchPage(status.exists, 0, 50).map { it.uid }
            }

            assertEquals(listOf(5L, 3L, 1L), hidden)
            assertEquals(listOf(5L, 4L, 3L, 1L), restored)
        }
    }

    /**
     * HIDING IS NOT DELETING. The Empty-trash snapshot enumerates with `UID SEARCH ALL`, which is
     */
    @Test
    fun `the purge still enumerates what the list hides`() {
        val allFlagged: MutableSet<Long> = Collections.synchronizedSet((1L..folderSize).toMutableSet())
        folderServer(allFlagged).use { server ->
            val (page, snapshot) = server.session().use { session ->
                val status = session.select("Trash")
                session.fetchPage(status.exists, 0, 50) to session.allUids(cap = 10_000)
            }

            assertEquals(emptyList<Long>(), page.map { it.uid })
            assertEquals((1L..folderSize).toList(), snapshot)
        }
    }

    /** The search and restore path (`UID FETCH <set>`) funnels through the same rule. */
    @Test
    fun `fetching by uid drops the ones flagged deleted`() {
        folderServer(mutableDeleted()).use { server ->
            val fetched = server.session().use { session ->
                session.select("Trash")
                session.fetchUids((1L..folderSize).toList())
            }

            assertEquals(listOf(1L, 3L, 5L), fetched.map { it.uid })
        }
    }

    /** A single fetch of a flagged message is "not there", which is what its callers mean. */
    @Test
    fun `fetching one flagged message by uid finds nothing`() {
        folderServer(mutableDeleted()).use { server ->
            val (gone, present) = server.session().use { session ->
                session.select("Trash")
                session.fetchByUid(4L) to session.fetchByUid(3L)
            }

            assertNull(gone)
            assertEquals(3L, present?.uid)
        }
    }

    /**
     * The badge must agree with the list it labels. An unread message that is hidden would
     * otherwise leave an unread count over a folder with nothing in it to open.
     */
    @Test
    fun `the unread count asks the server to leave deleted messages out`() {
        folderServer(mutableDeleted()).use { server ->
            val unread = server.session().use { session ->
                session.select("Trash")
                session.unseenCount()
            }

            assertEquals(listOf("SEARCH UNSEEN UNDELETED"), server.issued().filter { it.startsWith("SEARCH") })
            assertEquals(2, unread)
        }
    }

    /** The other flags are untouched: this adds a rule, it does not rewrite how FLAGS is read. */
    @Test
    fun `the seen and flagged state of a visible message is unchanged`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 2)
                line.startsWith("FETCH") -> fetchResponse(
                    tag,
                    listOf(1L, 2L),
                    flags = { uid -> if (uid == 1L) "\\Seen \\Flagged \\Answered" else "\\Deleted" },
                ) { false }
                else -> ok(tag)
            }
        }.use { server ->
            val page = server.session().use { session ->
                val status = session.select("INBOX")
                session.fetchPage(status.exists, 0, 50)
            }

            assertEquals(1, page.size)
            val kept = page.single()
            assertTrue("seen was lost", kept.seen)
            assertTrue("flagged was lost", kept.flagged)
            assertTrue("answered was lost", kept.answered)
        }
    }
}
