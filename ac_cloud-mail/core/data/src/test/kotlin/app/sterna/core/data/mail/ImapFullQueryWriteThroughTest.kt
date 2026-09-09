package app.sterna.core.data.mail

import app.sterna.core.data.db.EmailEntity
import app.sterna.core.data.db.EmailRetentionRow
import app.sterna.core.imap.ImapFolderWalk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The red line on the IMAP side, EXECUTED: pages written as they land, ONE reconcile at the end,
 */
class ImapFullQueryWriteThroughTest {

    private val account = "acc-1"
    private val inbox = "INBOX"

    private fun id(uid: Long) = "imap:$account:$inbox:$uid"

    /** One cached row, as the walk writes it — only the fields these rules read carry meaning. */
    private fun row(uid: Long, sortKey: Long = 1_000L) = EmailEntity(
        id = id(uid),
        accountId = account,
        mailboxId = inbox,
        threadId = null,
        subject = "Message $uid",
        preview = null,
        receivedAt = null,
        fromName = null,
        fromEmail = null,
        seen = true,
        flagged = false,
        hasAttachment = false,
        sortKey = sortKey,
    )

    /**
     * One folder as this path leaves it — a cache of ids, a recently-mutated spare set that MOVES
     */
    private class Folder(cached: List<String> = emptyList()) {
        val cache = LinkedHashSet(cached)
        val spare = LinkedHashSet<String>()
        var reconciledMailbox: String? = null
        var reconciledKeepIds: Set<String>? = null
        var reconciledSpare: List<String>? = null
        var evicted: List<String> = emptyList()

        suspend fun write(page: List<EmailEntity>) {
            cache += page.map { it.id }
        }

        /** The shipped reconcile, applied: complement against the WHOLE keep set, then evict. */
        suspend fun reconcile(mailboxId: String, keepIds: Set<String>, spareIds: List<String>, evictableIds: Set<String>?) {
            reconciledMailbox = mailboxId
            reconciledKeepIds = keepIds
            reconciledSpare = spareIds
            val gone = reconcileEvictions(
                cachedIds = evictableCachedIds(cache.toList(), evictableIds),
                keepIds = keepIds,
                spareIds = spareIds.toHashSet(),
            ).flatten()
            evicted = gone
            cache -= gone.toSet()
        }
    }

    /**
     * A walk that hands [pages] over one at a time and then answers with every UID it handed over
     */
    private fun walkOf(
        pages: List<List<Long>>,
        moved: Boolean = false,
        failAfter: Int? = null,
        failWith: Throwable? = null,
        between: suspend (Int) -> Unit = {},
    ): suspend (suspend (List<EmailEntity>) -> Unit) -> ImapFolderLoad = { onPage ->
        val handed = mutableListOf<Long>()
        pages.forEachIndexed { i, page ->
            if (failAfter != null && i == failAfter) throw failWith!!
            onPage(page.map { row(it) })
            handed += page
            between(i)
        }
        if (failAfter != null && failAfter >= pages.size) throw failWith!!
        ImapFolderLoad(
            mailboxes = emptyList(),
            targetMailboxId = inbox,
            targetName = "Inbox",
            unread = 0,
            accountName = "tester",
            walk = ImapFolderWalk(uids = handed, moved = moved),
        )
    }

    /** Three pages, so a MIDDLE one exists: a one-page fixture cannot tell "the whole walk" from
     *  "the last page" and would prove nothing. */
    private val threePages = listOf(listOf(9L, 8L), listOf(7L, 6L), listOf(5L))
    private val allThree = listOf(9L, 8L, 7L, 6L, 5L).map(::id)

    /** The shipped call, with the shipped arguments — the shape [ImapWindowWiringTest] pins. */
    private fun run(
        folder: Folder,
        walk: suspend (suspend (List<EmailEntity>) -> Unit) -> ImapFolderLoad,
    ): ImapFolderLoad = runBlocking {
        fullQueryWriteThrough<List<EmailEntity>, ImapFolderLoad>(
            folderIds = { folder.cache.toSet() },
            walk = walk,
            writePage = folder::write,
            keepIds = { load -> reconcilableIds(load, account) },
            spareIds = { folder.spare.toList() },
            reconcile = { load, keepIds, spare, evictable -> folder.reconcile(load.targetMailboxId, keepIds, spare, evictable) },
        )
    }

    // -- the walk that was cut off ----------------------------------------------------------------

    @Test fun `a walk cut off in the middle deletes NOTHING, and keeps the pages it did write`() {
        // The network drops after two of three pages. Everything the cache held must still be
        // there — including "stale", which no page names — and the two pages received too.
        val folder = Folder(cached = listOf(id(999), id(6)))
        val boom = java.io.IOException("connection reset")

        val thrown = runCatching { run(folder, walkOf(threePages, failAfter = 2, failWith = boom)) }.exceptionOrNull()

        assertSame("the failure must reach the caller, not be swallowed", boom, thrown)
        assertNull(
            "the reconcile RAN on a walk that never finished: it was handed two pages out of " +
                "three and deletes everything else in the folder",
            folder.reconciledKeepIds,
        )
        assertEquals("something was evicted on a failed walk", emptyList<String>(), folder.evicted)
        assertTrue("the cache lost a row the walk never spoke about", id(999) in folder.cache)
        assertTrue(
            "the pages already received were not kept — the walk wrote nothing through",
            folder.cache.containsAll(listOf(id(9), id(8), id(7), id(6))),
        )
    }

    @Test fun `a cancellation deletes nothing either, and propagates`() {
        // A back gesture during a pull-to-refresh. Catching this to "clean up" would mean
        // deleting mail on the one path where the app knows least about the folder.
        val folder = Folder(cached = listOf(id(999)))
        val cancelled = CancellationException("the screen went away")

        val thrown = runCatching { run(folder, walkOf(threePages, failAfter = 1, failWith = cancelled)) }.exceptionOrNull()

        assertSame(cancelled, thrown)
        assertNull("the reconcile ran on a cancelled walk", folder.reconciledKeepIds)
        assertTrue("the cache lost rows on a cancelled walk", id(999) in folder.cache)
        assertTrue("the page already received was dropped", folder.cache.containsAll(listOf(id(9), id(8))))
    }

    // -- the walk the folder moved under -----------------------------------------------------------

    @Test fun `a walk whose folder moved finishes, writes every page, and reconciles NOTHING`() {
        // THE test of this volet, and the one thing IMAP has that JMAP does not. An EXPUNGE
        // between two pages renumbers everything above it, so a page may have skipped a message —
        // and a skipped message is not in the walk's UIDs. Reconciling here would delete it from
        // the cache while the server still holds it. "stale" stands for that message.
        val folder = Folder(cached = listOf(id(999)))

        val load = run(folder, walkOf(threePages, moved = true))

        assertTrue("the fixture did not actually report a moved folder", load.walk.moved)
        assertNull(
            "the reconcile ran against a walk over a renumbered folder: the ids it was handed " +
                "are missing whatever the shifted pages skipped, and it DELETES everything else",
            folder.reconciledKeepIds,
        )
        assertEquals(emptyList<String>(), folder.evicted)
        assertTrue("a row the server still holds was evicted", id(999) in folder.cache)
        // The mail still landed: not reconciling costs a cache that holds MORE than the window,
        // never a cache that holds less.
        assertTrue(folder.cache.containsAll(allThree))
    }

    @Test fun `the same fixture on a folder that held still DOES reconcile — the witness`() {
        // Without this, "never reconcile" would pass every assertion above, and the IMAP cache
        // would grow without bound and never drop what left the folder.
        val folder = Folder(cached = listOf(id(999)))

        run(folder, walkOf(threePages, moved = false))

        assertEquals(allThree.toSet(), folder.reconciledKeepIds)
        assertEquals(listOf(id(999)), folder.evicted)
    }

    @Test fun `a page that fails to WRITE stops the walk and deletes nothing`() {
        // The other direction of the same rule: the failure is the database's, not the network's.
        val folder = Folder(cached = listOf(id(999)))
        val boom = IllegalStateException("disk full")
        var written = 0

        val thrown = runCatching {
            runBlocking {
                fullQueryWriteThrough<List<EmailEntity>, ImapFolderLoad>(
                    folderIds = { folder.cache.toSet() },
                    walk = walkOf(threePages),
                    writePage = { page -> if (written++ == 1) throw boom else folder.write(page) },
                    keepIds = { load -> reconcilableIds(load, account) },
                    spareIds = { folder.spare.toList() },
                    reconcile = { load, keepIds, spare, evictable -> folder.reconcile(load.targetMailboxId, keepIds, spare, evictable) },
                )
            }
        }.exceptionOrNull()

        assertSame(boom, thrown)
        assertNull("the reconcile ran although a page was never written", folder.reconciledKeepIds)
        assertTrue(id(999) in folder.cache)
    }

    // -- the reconcile, when the walk DID finish -------------------------------------------------------

    @Test fun `a finished walk reconciles once, against every page, not the last one`() {
        // uid 6 is cached AND named by the MIDDLE page. Reconciling against the last page alone
        // would evict it; against the whole walk it stays. uid 999 is named by no page and goes —
        // which is what makes this a reconcile and not a no-op.
        val folder = Folder(cached = listOf(id(6), id(999)))

        run(folder, walkOf(threePages))

        assertEquals(allThree.toSet(), folder.reconciledKeepIds)
        assertEquals(listOf(id(999)), folder.evicted)
        assertTrue(
            "a row named by a MIDDLE page was evicted: the reconcile did not see the whole walk",
            id(6) in folder.cache,
        )
        assertEquals(allThree.toSet(), folder.cache)
    }

    @Test fun `the reconcile is told the folder the WALK landed on`() {
        // The caller may have asked for null ("the inbox, whatever the server calls it"): only the
        // walk knows which folder its UIDs belong to, and reconciling the wrong one would delete
        // another folder's cache.
        val folder = Folder()

        run(folder, walkOf(threePages))

        assertEquals(inbox, folder.reconciledMailbox)
    }

    @Test fun `the recently-mutated spare is read at the reconcile, not before the walk`() {
        // The 45 s protection window against a walk that takes minutes. uid 42 is a row the user
        // restored WHILE the walk ran: it is in the cache, in no page, and in the spare set only
        // from page 2 onwards. A spare set read at the entry is empty, and the reconcile deletes
        // the restored row — undoing, one function later, the Undo.
        val folder = Folder(cached = listOf(id(42)))

        run(folder, walkOf(threePages, between = { i -> if (i == 1) folder.spare += id(42) }))

        assertEquals(listOf(id(42)), folder.reconciledSpare)
        assertEquals(
            "the spare set was read before the walk, when it was still empty",
            emptyList<String>(),
            folder.evicted,
        )
        assertTrue("a locally restored row was deleted by the reconcile", id(42) in folder.cache)
    }

    @Test fun `the spare set is what protects it — the same fixture without it loses the row`() {
        val folder = Folder(cached = listOf(id(42)))

        runBlocking {
            fullQueryWriteThrough<List<EmailEntity>, ImapFolderLoad>(
                folderIds = { folder.cache.toSet() },
                walk = walkOf(threePages),
                writePage = folder::write,
                keepIds = { load -> reconcilableIds(load, account) },
                spareIds = { emptyList() },
                reconcile = { load, keepIds, spare, evictable -> folder.reconcile(load.targetMailboxId, keepIds, spare, evictable) },
            )
        }

        assertEquals(listOf(id(42)), folder.evicted)
    }

    // -- the decision itself ---------------------------------------------------------------------------

    @Test fun `reconcilableIds refuses a moved folder and never answers an empty set instead`() {
        // Null is a refusal; an empty set is "keep nothing", i.e. delete the folder. The two
        // must never be confused, so the refusal is stated on a NON-EMPTY walk.
        val load = ImapFolderLoad(emptyList(), inbox, "Inbox", 0, "tester", ImapFolderWalk(listOf(9L, 8L), moved = true))

        assertNull(reconcilableIds(load, account))
    }

    @Test fun `reconcilableIds names every UID of the walk, under this account and this folder`() {
        val load = ImapFolderLoad(emptyList(), inbox, "Inbox", 0, "tester", ImapFolderWalk(listOf(9L, 8L, 5L), moved = false))

        // The cache id format spelled out: an id built from another account or another folder
        // would name nothing in the cache, and the reconcile would then evict the whole folder
        // (#31/#121 — ids collide between accounts on the same server).
        assertEquals(setOf("imap:acc-1:INBOX:9", "imap:acc-1:INBOX:8", "imap:acc-1:INBOX:5"), reconcilableIds(load, account))
        assertEquals(setOf("imap:other:INBOX:9"), reconcilableIds(load.copy(walk = ImapFolderWalk(listOf(9L), false)), "other"))
    }

    // -- what the retention prune is told --------------------------------------------------------------

    @Test fun `a moved folder prunes nothing either — the same refusal, reused`() {
        // The prune deletes on the strength of the same set (`freshIds`), so it may not be more
        // confident about it than the reconcile was. `retentionEvictions` prunes nothing for a
        // caller that answers null, and that null is `reconcilableIds` refusing.
        val load = ImapFolderLoad(emptyList(), inbox, "Inbox", 0, "tester", ImapFolderWalk(listOf(9L, 8L), moved = true))
        val cached = listOf(9L, 8L, 999L).map { EmailRetentionRow(id = id(it), sortKey = 1_000L) }

        val pruned = retentionEvictions(
            cached = cached,
            cutoffMillis = 9_000_000L,
            freshIds = reconcilableIds(load, account),
            spareIds = emptySet(),
            keepNewest = 0,
        )

        assertEquals("mail was pruned on the strength of a walk that could not vouch for itself", emptyList<String>(), pruned)
    }

    @Test fun `a folder that held still prunes what the walk did not bring back — the witness`() {
        val load = ImapFolderLoad(emptyList(), inbox, "Inbox", 0, "tester", ImapFolderWalk(listOf(9L, 8L), moved = false))
        val cached = listOf(9L, 8L, 999L).map { EmailRetentionRow(id = id(it), sortKey = 1_000L) }

        val pruned = retentionEvictions(
            cached = cached,
            cutoffMillis = 9_000_000L,
            freshIds = reconcilableIds(load, account),
            spareIds = emptySet(),
            keepNewest = 0,
        )

        assertEquals(listOf(id(999)), pruned)
    }
}
