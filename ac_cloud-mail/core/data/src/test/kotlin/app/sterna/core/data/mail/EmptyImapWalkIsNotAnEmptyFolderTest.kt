package app.sterna.core.data.mail

import app.sterna.core.imap.ImapFolderWalk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The IMAP twin of [EmptyWindowIsNotAnEmptyFolderTest]: a walk that read NOTHING is not a walk
 */
class EmptyImapWalkIsNotAnEmptyFolderTest {

    private val account = "acc-1"
    private val inbox = "INBOX"

    private fun id(uid: Long) = "imap:$account:$inbox:$uid"

    private fun load(walk: ImapFolderWalk) =
        ImapFolderLoad(emptyList(), inbox, "Inbox", 0, "tester", walk)

    // -- the decision itself ---------------------------------------------------------------------------

    @Test fun `a walk with no UID whose SELECT never stated a count is a REFUSAL, not an empty folder`() {
        // The case, and the value under test is the one the walk could not vouch for: the field is
        // named false here because that is what `walkFolder` builds from a SELECT without EXISTS.
        assertNull(
            reconcilableIds(load(ImapFolderWalk(uids = emptyList(), moved = false, folderStatedEmpty = false)), account),
        )
    }

    @Test fun `a walk built without naming the field refuses too — the default is UNKNOWN`() {
        // The dangerous default, executed. "Stated empty" by default would mean every fixture and
        // every future construction site licenses a delete by saying nothing.
        assertNull(reconcilableIds(load(ImapFolderWalk(uids = emptyList(), moved = false)), account))
    }

    @Test fun `a walk over a folder the server SAID holds nothing clears the cache — the witness`() {
        // Without this, "never reconcile an empty walk" passes everything above, and no IMAP
        // folder is ever trimmed again.
        assertEquals(
            emptySet<String>(),
            reconcilableIds(load(ImapFolderWalk(uids = emptyList(), moved = false, folderStatedEmpty = true)), account),
        )
    }

    @Test fun `a moved folder is refused even when it stated itself empty — that check stays first`() {
        // The order of the two refusals: a folder renumbered under the walk may have hidden messages
        // from it whatever the SELECT said at the start.
        assertNull(
            reconcilableIds(load(ImapFolderWalk(uids = emptyList(), moved = true, folderStatedEmpty = true)), account),
        )
    }

    @Test fun `a walk that DID bring UIDs back is reconciled on them, stated-empty or not`() {
        // The ordinary path must not be touched by any of this: UIDs came back, so the walk saw the
        // folder, whatever the SELECT line looked like.
        assertEquals(
            setOf(id(9), id(8)),
            reconcilableIds(load(ImapFolderWalk(uids = listOf(9L, 8L), moved = false)), account),
        )
    }

    // -- the same rule, through the shipped write-through ---------------------------------------------

    /** A cache of ids, reconciled by the shipped complement rule. */
    private class Folder(cached: List<String>) {
        val cache = LinkedHashSet(cached)
        var reconciled = false

        suspend fun reconcile(keepIds: Set<String>, spareIds: List<String>, evictableIds: Set<String>?) {
            reconciled = true
            cache -= reconcileEvictions(evictableCachedIds(cache.toList(), evictableIds), keepIds, spareIds.toHashSet())
                .flatten().toSet()
        }
    }

    private fun writeThrough(folder: Folder, walk: ImapFolderWalk) = runBlocking {
        fullQueryWriteThrough<List<String>, ImapFolderLoad>(
            folderIds = { folder.cache.toSet() },
            walk = { load(walk) },
            writePage = { page -> folder.cache += page },
            keepIds = { reconcilableIds(it, account) },
            spareIds = { emptyList() },
            reconcile = { _, keep, spare, evictable -> folder.reconcile(keep, spare, evictable) },
        )
    }

    @Test fun `a refresh that read nothing leaves the cached folder exactly as it was`() {
        // End to end, with the shipped keepIds: nothing was read, so nothing may be deleted. This
        // is the 300 lines the user still has after pulling to refresh on a server that answered a
        // SELECT without EXISTS.
        val folder = Folder(cached = listOf(id(9), id(8), id(7)))

        writeThrough(folder, ImapFolderWalk(uids = emptyList(), moved = false))

        assertEquals(
            "the folder was emptied by a refresh that learned nothing about it",
            setOf(id(9), id(8), id(7)), folder.cache,
        )
        assertEquals("the reconcile ran on a walk that saw no message at all", false, folder.reconciled)
    }

    @Test fun `an emptied folder is still cleared end to end — the witness`() {
        val folder = Folder(cached = listOf(id(9), id(8)))

        writeThrough(folder, ImapFolderWalk(uids = emptyList(), moved = false, folderStatedEmpty = true))

        assertEquals(emptySet<String>(), folder.cache)
        assertEquals(true, folder.reconciled)
    }

    // -- what the retention prune is told --------------------------------------------------------------

    @Test fun `the prune inherits the same refusal, and deletes nothing either`() {
        // `refreshImap` calls this decision a second time as the prune's `freshIds` (#110). A prune
        // that took an empty set here would delete, in the same pass, the rows the reconcile spared.
        val cached = listOf(9L, 8L).map { app.sterna.core.data.db.EmailRetentionRow(id = id(it), sortKey = 1_000L) }

        val pruned = retentionEvictions(
            cached = cached,
            cutoffMillis = 9_000_000L,
            freshIds = reconcilableIds(load(ImapFolderWalk(uids = emptyList(), moved = false)), account),
            spareIds = emptySet(),
            keepNewest = 0,
        )

        assertEquals("mail was pruned on the strength of a walk that read nothing", emptyList<String>(), pruned)
    }
}
