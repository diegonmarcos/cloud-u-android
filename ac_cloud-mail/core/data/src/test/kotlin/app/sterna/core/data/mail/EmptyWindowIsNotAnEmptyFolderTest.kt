package app.sterna.core.data.mail

import app.sterna.core.jmap.WindowWalk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A walk can end WITHOUT throwing and still know nothing about the folder — and the reconcile
 */
class EmptyWindowIsNotAnEmptyFolderTest {

    private fun walk(ids: List<String>, queryCount: Int) =
        WindowWalk(ids = ids, queryState = "q1", emailState = "e1", queryCount = queryCount, resumedByPosition = false)

    // -- the rule itself ---------------------------------------------------------------------------

    @Test fun `a query that listed ids and a get that returned none is a REFUSAL, not an empty folder`() {
        // The case. 500 ids listed, nothing decoded. Answering an empty set here deletes the
        // folder on a refresh in which the app learned precisely nothing.
        assertNull(reconcilableWindowIds(walk(ids = emptyList(), queryCount = 500)))
    }

    @Test fun `a query that listed nothing IS an empty folder, and still clears the cache`() {
        // The other half, and the reason this is not "refuse whenever the walk is empty": a folder
        // emptied on the server has to be able to empty the cache. Refusing here would leave every
        // emptied folder showing its old contents until something else cleaned up, and on IMAP
        // nothing else does.
        assertEquals(emptySet<String>(), reconcilableWindowIds(walk(ids = emptyList(), queryCount = 0)))
    }

    @Test fun `an ordinary walk answers its ids, whole`() {
        val ids = listOf("m1", "m2", "m3")
        assertEquals(ids.toSet(), reconcilableWindowIds(walk(ids, queryCount = 3)))
    }

    @Test fun `a short GET that still brought something back is reconciled on what it brought`() {
        // The already-covered case (a message destroyed between query and get): the walk saw the
        // folder, it merely came back one short. The refusal above must not swallow this one, or a
        // single destroyed message would stop every reconcile.
        assertEquals(setOf("m1", "m2"), reconcilableWindowIds(walk(listOf("m1", "m2"), queryCount = 3)))
    }

    // -- the same rule, through the shipped write-through ------------------------------------------

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

    private fun writeThrough(folder: Folder, answer: WindowWalk) = runBlocking {
        fullQueryWriteThrough<List<String>, WindowWalk>(
            folderIds = { folder.cache.toSet() },
            walk = { answer },
            writePage = { page -> folder.cache += page },
            keepIds = { reconcilableWindowIds(it) },
            spareIds = { emptyList() },
            reconcile = { _, keep, spare, evictable -> folder.reconcile(keep, spare, evictable) },
        )
    }

    @Test fun `a muted GET leaves the folder exactly as it was`() {
        // End to end, with the shipped keepIds: nothing was written, nothing may be deleted.
        val folder = Folder(cached = listOf("kept1", "kept2", "kept3"))

        writeThrough(folder, walk(ids = emptyList(), queryCount = 500))

        assertEquals(
            "the folder was emptied by a refresh that learned nothing from the server",
            setOf("kept1", "kept2", "kept3"), folder.cache,
        )
        assertEquals("the reconcile ran on a walk that saw no message at all", false, folder.reconciled)
    }

    @Test fun `an emptied folder is still cleared — the witness for the test above`() {
        // Without this, "a muted GET changes nothing" could be got by never reconciling at all.
        val folder = Folder(cached = listOf("gone1", "gone2"))

        writeThrough(folder, walk(ids = emptyList(), queryCount = 0))

        assertEquals(emptySet<String>(), folder.cache)
        assertEquals(true, folder.reconciled)
    }

    @Test fun `the refusal does not consume the walk's answer — the caller still gets its ids`() {
        // The return value feeds the retention prune as freshIds (#110). A refusal to reconcile
        // must not turn into a refusal to report, or the prune would delete on the next pass what
        // this walk did write.
        val folder = Folder(cached = listOf("kept"))
        val answer = walk(ids = listOf("n1"), queryCount = 500)
        assertNotNull(writeThrough(folder, answer).ids)
        assertEquals(listOf("n1"), writeThrough(folder, answer).ids)
    }
}
