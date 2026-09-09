package app.sterna.core.data.mail

import app.sterna.core.jmap.WindowWalk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A full re-query must not delete the mail that arrives WHILE it runs (Codeberg #165).
 */
class ReconcileSparesWhatArrivedDuringTheWalkTest {

    /**
     * One folder as this path leaves it: a cache of ids, a record of the arguments the reconcile
     */
    private class Folder(cached: List<String> = emptyList()) {
        val cache = LinkedHashSet(cached)
        var inventoryReads = 0
        var reconcileRan = false
        var reconciledKeepIds: Set<String>? = null
        var reconciledEvictable: Set<String>? = null
        var evicted: List<String> = emptyList()
        var duringReconcile: (() -> Unit)? = null

        /** What the shipped `folderIds` reads: the ids this folder holds, right now. */
        suspend fun inventory(): Set<String> {
            inventoryReads++
            return cache.toSet()
        }

        suspend fun write(page: List<String>) {
            cache += page
        }

        /** Somebody else writing into this same folder: push, the 30-minute worker, a second
         *  refresh. Nothing locks any of them out of it. */
        fun thirdPartyWrites(id: String) {
            cache += id
        }

        /** …and somebody else removing a row. */
        fun thirdPartyRemoves(id: String) {
            cache -= id
        }

        /** The shipped reconcile, applied: bound the folder's own read, take the complement, evict. */
        suspend fun reconcile(keepIds: Set<String>, spareIds: List<String>, evictableIds: Set<String>?) {
            reconcileRan = true
            reconciledKeepIds = keepIds
            reconciledEvictable = evictableIds
            duringReconcile?.invoke()
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
     * A walk that hands [pages] over one at a time and answers with every id it handed over, or
     */
    private fun walkOf(
        pages: List<List<String>>,
        failAfter: Int? = null,
        failWith: Throwable? = null,
        between: suspend (Int) -> Unit = {},
    ): suspend (suspend (List<String>) -> Unit) -> WindowWalk = { onPage ->
        val handed = mutableListOf<String>()
        pages.forEachIndexed { i, page ->
            if (failAfter != null && i == failAfter) throw failWith!!
            onPage(page)
            handed += page
            between(i)
        }
        WindowWalk(
            ids = handed, queryState = "q1", emailState = "e1",
            queryCount = handed.size, resumedByPosition = false,
        )
    }

    private val threePages = listOf(listOf("n1", "n2"), listOf("mid1", "mid2"), listOf("z1"))
    private val allThree = setOf("n1", "n2", "mid1", "mid2", "z1")

    /** The push/worker write, injected after the SECOND page — mid-walk, above the anchor. */
    private fun arrivesAfterPage2(folder: Folder, id: String): suspend (Int) -> Unit =
        { i -> if (i == 1) folder.thirdPartyWrites(id) }

    private fun writeThrough(
        folder: Folder,
        walk: suspend (suspend (List<String>) -> Unit) -> WindowWalk,
        spare: List<String> = emptyList(),
        folderIds: suspend () -> Set<String>? = { folder.inventory() },
    ): WindowWalk = runBlocking {
        fullQueryWriteThrough(
            folderIds = folderIds,
            walk = walk,
            writePage = folder::write,
            keepIds = { reconcilableWindowIds(it) },
            spareIds = { spare },
            reconcile = { _, keep, spares, evictable -> folder.reconcile(keep, spares, evictable) },
        )
    }

    // -- the volet ------------------------------------------------------------------------------

    @Test fun `mail that lands DURING the walk survives the reconcile at the end`() {
        // THE test of this volet. The push socket files "arrived-1" into this folder while the
        // walk sits between its second and third page. No page names it — the walk pages by anchor
        // and never goes back up — so an unbounded reconcile deletes it, and the cursor stored
        // right afterwards means it never comes back. It is the newest message in the folder.
        val folder = Folder(cached = listOf("older"))

        writeThrough(folder, walkOf(threePages, between = arrivesAfterPage2(folder, "arrived-1")))

        assertTrue(
            "the message that arrived while the walk ran was DELETED by the reconcile (#165). " +
                "The folder now holds: ${folder.cache}",
            "arrived-1" in folder.cache,
        )
        assertEquals(
            "the reconcile evicted the message that arrived during the walk",
            emptyList<String>(),
            folder.evicted.filter { it == "arrived-1" },
        )
    }

    @Test fun `a row that commits between the bound and the reconcile's own read survives too`() {
        // The half a keep set cannot cover, and the reason this fix is a bound. `reconcileMailbox`
        // is @Transaction and `reconcileMailboxRows` re-reads the folder itself (`idsForMailbox`),
        val folder = Folder(cached = listOf("older"))
        folder.duringReconcile = { folder.thirdPartyWrites("commits-during-reconcile") }

        writeThrough(folder, walkOf(threePages))

        assertTrue(
            "a message whose write committed between the bound and the reconcile's own read was " +
                "deleted: the guard is on the wrong side of the transaction. The folder holds: " +
                "${folder.cache}",
            "commits-during-reconcile" in folder.cache,
        )
        assertEquals(emptyList<String>(), folder.evicted.filter { it == "commits-during-reconcile" })
    }

    // -- the reconcile is not disarmed --------------------------------------------------------------

    @Test fun `a row that was there BEFORE the walk and no page names is still deleted`() {
        // The witness. A bound that kept everything would be a reconcile that deletes nothing,
        // ever again: the cache would grow without limit and a message destroyed on the server
        // would stay in the list for good. Bounding is not disarming.
        val folder = Folder(cached = listOf("stale", "mid2"))

        writeThrough(folder, walkOf(threePages, between = arrivesAfterPage2(folder, "arrived-1")))

        assertEquals(
            "the reconcile no longer deletes anything: the bound is no longer the folder as it " +
                "stood before the walk",
            listOf("stale"),
            folder.evicted,
        )
        assertTrue("a row named by a MIDDLE page was evicted", "mid2" in folder.cache)
        assertTrue("the row that arrived during the walk was evicted", "arrived-1" in folder.cache)
        assertEquals(allThree + "arrived-1", folder.cache)
    }

    @Test fun `an unbounded reconcile is exactly the old behaviour — the witness for the bound`() {
        // null means NO BOUND, and this is what that costs: the same fixture as the test above,
        // with the bound withheld, loses the message that arrived during the walk. It is stated
        // here so nobody has to take on trust that the bound is what saves it — and so that
        // `imapWriteThrough` passing null on an unnamed folder is a known cost and not a fix.
        val folder = Folder(cached = listOf("stale"))

        writeThrough(
            folder,
            walkOf(threePages, between = arrivesAfterPage2(folder, "arrived-1")),
            folderIds = { null },
        )

        assertNull("a null inventory reached the reconcile as something other than null", folder.reconciledEvictable)
        assertEquals(listOf("stale", "arrived-1"), folder.evicted)
    }

    @Test fun `an EMPTY bound evicts nothing at all — which is why null is not emptySet()`() {
        // The trap of this shape, and the reason `imapWriteThrough` answers null rather than
        // `emptySet()` for a folder it cannot name. An empty bound is not "nothing to protect", it
        // is "nothing may be deleted": the reconcile stops removing anything, silently.
        val folder = Folder(cached = listOf("stale"))

        writeThrough(folder, walkOf(threePages), folderIds = { emptySet() })

        assertEquals(emptySet<String>(), folder.reconciledEvictable)
        assertEquals("an empty bound evicted something", emptyList<String>(), folder.evicted)
        assertTrue("an empty bound is being read as 'no bound'", "stale" in folder.cache)
    }

    // -- the bound, as an argument ------------------------------------------------------------------

    @Test fun `the reconcile is handed the folder as it stood BEFORE the walk, as its bound`() {
        // Not "the folder is intact" — the ARGUMENT. A folder can end up intact for reasons that
        // have nothing to do with this volet (an empty keep set is refused elsewhere, a spare set
        val folder = Folder(cached = listOf("stale", "mid2"))

        writeThrough(folder, walkOf(threePages, between = arrivesAfterPage2(folder, "arrived-1")))

        assertEquals(
            "the bound handed to the reconcile is not the folder as it stood before the walk",
            setOf("stale", "mid2"),
            folder.reconciledEvictable,
        )
        assertEquals("the keep set is no longer the walk's own ids", allThree, folder.reconciledKeepIds)
    }

    // -- keepIds null: nothing at all ---------------------------------------------------------------

    @Test fun `a keep set refused with null reconciles nothing, and the folder is read exactly once`() {
        // A muted `Email/get`: the walk returned, listed nothing, and the server DID say the query
        // had results — [reconcilableWindowIds] answers null, which means DO NOT RECONCILE AT ALL.
        // One read of the folder and no more: a second one, taken at the reconcile, would be the
        // snapshot this whole volet exists to stop anyone from bounding with.
        val folder = Folder(cached = listOf("kept1", "kept2"))
        val muted: suspend (suspend (List<String>) -> Unit) -> WindowWalk = {
            folder.thirdPartyWrites("arrived-1")
            WindowWalk(
                ids = emptyList(), queryState = "q1", emailState = "e1",
                queryCount = 500, resumedByPosition = false,
            )
        }

        writeThrough(folder, muted)

        assertFalse("the reconcile ran on a walk that saw nothing", folder.reconcileRan)
        assertEquals("something was evicted although the keep set was refused", emptyList<String>(), folder.evicted)
        assertEquals(
            "the folder's inventory is no longer read exactly once, before the walk",
            1,
            folder.inventoryReads,
        )
        assertEquals(setOf("kept1", "kept2", "arrived-1"), folder.cache)
    }

    // -- a walk cut off in the middle ---------------------------------------------------------------

    @Test fun `a walk cut off in the middle deletes nothing, including what arrived during it`() {
        // The red line of this path, with the bound in place: the failure climbs out, no reconcile
        // runs, and the message that landed mid-walk is still there. A `try`/`finally` around
        // the inventory read would break exactly this.
        val folder = Folder(cached = listOf("stale"))
        val boom = java.io.IOException("connection reset")

        val thrown = runCatching {
            writeThrough(folder, walkOf(threePages, failAfter = 2, failWith = boom, between = arrivesAfterPage2(folder, "arrived-1")))
        }.exceptionOrNull()

        assertSame("the failure must reach the caller, not be swallowed", boom, thrown)
        assertFalse("the reconcile ran on a walk that never finished", folder.reconcileRan)
        assertEquals("something was evicted on a failed walk", emptyList<String>(), folder.evicted)
        assertTrue("the cache lost a row the walk never spoke about", "stale" in folder.cache)
        assertTrue("the row that arrived during the interrupted walk was deleted", "arrived-1" in folder.cache)
    }

    // -- the other direction: a row that LEFT during the walk ---------------------------------------

    @Test fun `a row removed by somebody else during the walk is not resurrected`() {
        // The bound is a filter on what the reconcile READS, so it can neither delete a row that is
        // already gone nor bring one back. Stated because "the folder as it stood before the walk"
        // names rows that may no longer exist by the time it is used.
        val folder = Folder(cached = listOf("departed-1", "stale"))

        writeThrough(
            folder,
            walkOf(threePages, between = { i -> if (i == 1) folder.thirdPartyRemoves("departed-1") }),
        )

        assertTrue("the bound resurrected a row that left the folder", "departed-1" !in folder.cache)
        assertEquals("the reconcile stopped deleting the stale row", listOf("stale"), folder.evicted)
    }
}
