package app.sterna.core.data.mail

import app.sterna.core.data.db.EmailRetentionRow
import app.sterna.core.jmap.WindowWalk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The red line of the full-query sync, EXECUTED: pages are written as they land, and the DELETE
 */
class FullQueryWriteThroughTest {

    /**
     * One folder as this path leaves it — a cache of ids, a recently-mutated spare set that MOVES
     */
    private class Folder(cached: List<String> = emptyList()) {
        val cache = LinkedHashSet(cached)
        val spare = LinkedHashSet<String>()
        var reconciledKeepIds: Set<String>? = null
        var reconciledSpare: List<String>? = null
        var evicted: List<String> = emptyList()

        suspend fun write(page: List<String>) {
            cache += page
        }

        /** The shipped reconcile, applied: complement against the WHOLE keep set, then evict. */
        suspend fun reconcile(keepIds: Set<String>, spareIds: List<String>, evictableIds: Set<String>?) {
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
     * A walk that hands [pages] over one at a time and then answers with every id it handed over,
     */
    private fun walkOf(
        pages: List<List<String>>,
        failAfter: Int? = null,
        failWith: Throwable? = null,
        queryState: String? = "q1",
        emailState: String? = "e1",
        between: suspend (Int) -> Unit = {},
    ): suspend (suspend (List<String>) -> Unit) -> WindowWalk = { onPage ->
        val handed = mutableListOf<String>()
        pages.forEachIndexed { i, page ->
            if (failAfter != null && i == failAfter) throw failWith!!
            onPage(page)
            handed += page
            between(i)
        }
        if (failAfter != null && failAfter >= pages.size) throw failWith!!
        WindowWalk(
            ids = handed, queryState = queryState, emailState = emailState,
            queryCount = handed.size, resumedByPosition = false,
        )
    }

    /** Three pages, so a MIDDLE one exists — a one-page fixture cannot tell "the whole walk" from
     *  "the last page" and would prove nothing. */
    private val threePages = listOf(listOf("n1", "n2"), listOf("mid1", "mid2"), listOf("z1"))
    private val allThree = listOf("n1", "n2", "mid1", "mid2", "z1")

    // -- the red line ---------------------------------------------------------------------------

    @Test fun `a walk cut off in the middle deletes NOTHING, and keeps the pages it did write`() {
        // THE test of this volet. The network drops after two of three pages. Everything the
        // cache held before must still be there — including "stale", which no page names and
        // which a reconcile would have deleted — and the two pages received must be there too.
        val folder = Folder(cached = listOf("stale", "older"))
        val boom = java.io.IOException("connection reset")

        val thrown = runBlocking {
            runCatching {
                fullQueryWriteThrough(
                    folderIds = { folder.cache.toSet() },
                    walk = walkOf(threePages, failAfter = 2, failWith = boom),
                    writePage = folder::write,
                    keepIds = { it.ids.toHashSet() },
                    spareIds = { folder.spare.toList() },
                    reconcile = { _, keep, spare, evictable -> folder.reconcile(keep, spare, evictable) },
                )
            }.exceptionOrNull()
        }

        assertSame("the failure must reach the caller, not be swallowed", boom, thrown)
        assertNull(
            "the reconcile RAN on a walk that never finished: it was handed the ids of two pages " +
                "out of three and deletes everything else in the folder",
            folder.reconciledKeepIds,
        )
        assertEquals("something was evicted on a failed walk", emptyList<String>(), folder.evicted)
        assertTrue(
            "the cache lost rows the walk never spoke about: $folder.cache",
            folder.cache.containsAll(listOf("stale", "older")),
        )
        assertTrue(
            "the pages already received were not kept — the walk wrote nothing through",
            folder.cache.containsAll(listOf("n1", "n2", "mid1", "mid2")),
        )
    }

    @Test fun `a cancellation deletes nothing either, and propagates`() {
        // A back gesture during a pull-to-refresh. Catching this to "clean up" would mean
        // deleting mail on the one path where the app knows least about the folder.
        val folder = Folder(cached = listOf("stale"))
        val cancelled = CancellationException("the screen went away")

        val thrown = runBlocking {
            runCatching {
                fullQueryWriteThrough(
                    folderIds = { folder.cache.toSet() },
                    walk = walkOf(threePages, failAfter = 1, failWith = cancelled),
                    writePage = folder::write,
                    keepIds = { it.ids.toHashSet() },
                    spareIds = { folder.spare.toList() },
                    reconcile = { _, keep, spare, evictable -> folder.reconcile(keep, spare, evictable) },
                )
            }.exceptionOrNull()
        }

        assertSame(cancelled, thrown)
        assertNull("the reconcile ran on a cancelled walk", folder.reconciledKeepIds)
        assertTrue("the cache lost rows on a cancelled walk", "stale" in folder.cache)
        assertTrue("the page already received was dropped", folder.cache.containsAll(listOf("n1", "n2")))
    }

    @Test fun `a page that fails to WRITE stops the walk and deletes nothing`() {
        // The other direction of the same rule: the failure is the database's, not the network's.
        // It has to be as fatal to the reconcile as a dropped connection, because the cache it
        // would reconcile against is missing a page.
        val folder = Folder(cached = listOf("stale"))
        val boom = IllegalStateException("disk full")
        var written = 0

        val thrown = runBlocking {
            runCatching {
                fullQueryWriteThrough(
                    folderIds = { folder.cache.toSet() },
                    walk = walkOf(threePages),
                    writePage = { page ->
                        if (written++ == 1) throw boom
                        folder.write(page)
                    },
                    keepIds = { it.ids.toHashSet() },
                    spareIds = { folder.spare.toList() },
                    reconcile = { _, keep, spare, evictable -> folder.reconcile(keep, spare, evictable) },
                )
            }.exceptionOrNull()
        }

        assertSame(boom, thrown)
        assertNull("the reconcile ran although a page was never written", folder.reconciledKeepIds)
        assertTrue("stale" in folder.cache)
    }

    // -- the reconcile, when the walk DID finish ---------------------------------------------------

    @Test fun `a finished walk reconciles once, against every page, not the last one`() {
        // "mid2" is cached AND named by the middle page. Reconciling against the last page alone
        // would evict it; against the whole walk it stays. "stale" is named by no page and goes —
        // which is what makes this a reconcile and not a no-op.
        val folder = Folder(cached = listOf("mid2", "stale"))

        val walked = runBlocking {
            fullQueryWriteThrough(
                folderIds = { folder.cache.toSet() },
                walk = walkOf(threePages),
                writePage = folder::write,
                keepIds = { it.ids.toHashSet() },
                spareIds = { folder.spare.toList() },
                reconcile = { _, keep, spare, evictable -> folder.reconcile(keep, spare, evictable) },
            )
        }

        assertEquals(allThree.toSet(), folder.reconciledKeepIds)
        assertEquals(listOf("stale"), folder.evicted)
        assertTrue(
            "a row named by a MIDDLE page was evicted: the reconcile did not see the whole walk",
            "mid2" in folder.cache,
        )
        assertEquals(allThree.toSet() + "mid2", folder.cache)
        assertEquals("q1", walked.queryState)
        assertEquals("e1", walked.emailState)
    }

    @Test fun `the recently-mutated spare is read at the reconcile, not before the walk`() {
        // The 45 s protection window against a walk that takes minutes. "undone" is a row the
        // user restored WHILE the walk was running: it is in the cache, in no page, and in the
        // spare set only from page 2 onwards. A spare set read at the entry is empty, and the
        // reconcile deletes the restored row — undoing, one function later, the Undo.
        val folder = Folder(cached = listOf("undone"))

        runBlocking {
            fullQueryWriteThrough(
                folderIds = { folder.cache.toSet() },
                walk = walkOf(threePages, between = { i -> if (i == 1) folder.spare += "undone" }),
                writePage = folder::write,
                keepIds = { it.ids.toHashSet() },
                spareIds = { folder.spare.toList() },
                reconcile = { _, keep, spare, evictable -> folder.reconcile(keep, spare, evictable) },
            )
        }

        assertEquals(listOf("undone"), folder.reconciledSpare)
        assertEquals("the spare set was read before the walk, when it was still empty", emptyList<String>(), folder.evicted)
        assertTrue("a locally restored row was deleted by the reconcile", "undone" in folder.cache)
    }

    @Test fun `the spare set really is what protects it — the same fixture without it loses the row`() {
        // The witness for the test above: with the spare handed over empty, "undone" goes. So the
        // assertion up there is about the spare and not about some other reason the row survived.
        val folder = Folder(cached = listOf("undone"))

        runBlocking {
            fullQueryWriteThrough(
                folderIds = { folder.cache.toSet() },
                walk = walkOf(threePages),
                writePage = folder::write,
                keepIds = { it.ids.toHashSet() },
                spareIds = { emptyList() },
                reconcile = { _, keep, spare, evictable -> folder.reconcile(keep, spare, evictable) },
            )
        }

        assertEquals(listOf("undone"), folder.evicted)
    }

    // -- what comes back: freshIds for the retention prune (#110) -----------------------------------

    @Test fun `the ids returned are every id written, not the last page's`() {
        val folder = Folder()

        val walked = runBlocking {
            fullQueryWriteThrough(
                folderIds = { folder.cache.toSet() },
                walk = walkOf(threePages),
                writePage = folder::write,
                keepIds = { it.ids.toHashSet() },
                spareIds = { folder.spare.toList() },
                reconcile = { _, keep, spare, evictable -> folder.reconcile(keep, spare, evictable) },
            )
        }

        assertEquals(allThree, walked.ids)
        assertEquals("every id returned must be an id written", folder.cache, walked.ids.toSet())
    }

    @Test fun `the retention prune spares every page of the walk, not just the last (#110)`() {
        // The consequence, executed against the shipped prune. Every row is older than the cutoff
        // and the floor keeps none, so the ONLY thing between this folder and an empty list is
        // freshIds — and freshIds is the value this function returns. Answering with the last
        // page would delete, in the same refresh, four of the five messages the server just sent.
        val folder = Folder()
        val walked = runBlocking {
            fullQueryWriteThrough(
                folderIds = { folder.cache.toSet() },
                walk = walkOf(threePages),
                writePage = folder::write,
                keepIds = { it.ids.toHashSet() },
                spareIds = { folder.spare.toList() },
                reconcile = { _, keep, spare, evictable -> folder.reconcile(keep, spare, evictable) },
            )
        }

        // The rows the PRUNE sees are the five the walk WROTE, stated independently of what this
        // function answered. Deriving them from `walked.ids` would shrink both sides of the
        // comparison together and the assertion below would hold whatever came back.
        assertEquals("the fixture did not write what it says it wrote", allThree.toSet(), folder.cache)
        val pruned = retentionEvictions(
            cached = allThree.map { EmailRetentionRow(id = it, sortKey = 1_000L) },
            cutoffMillis = 9_000_000L,
            freshIds = walked.ids.toSet(),
            spareIds = emptySet(),
            keepNewest = 0,
        )

        assertEquals("the prune deleted mail this very sync had just fetched", emptyList<String>(), pruned)
    }
}
