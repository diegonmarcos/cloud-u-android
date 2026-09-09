package app.sterna.core.data.mail

import app.sterna.core.data.account.SyncWindow
import app.sterna.core.data.account.syncWindowChanged
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Changing "Messages to sync" has to drop that account's sync cursors, because the delta branch of
 */
class SyncWindowDropsCursorsTest {

    /** The persisted half, in a map: survives a [SyncCursors] the way SharedPreferences survives a
     *  process. */
    private class FakeStore : SyncCursorStore {
        val disk = linkedMapOf<String, Pair<String, String>>()
        override fun save(key: String, queryState: String, emailState: String) {
            disk[key] = queryState to emailState
        }
        override fun load(key: String): Pair<String, String>? = disk[key]
        override fun remove(key: String) { disk.remove(key) }
        override fun clear() { disk.clear() }
        override fun keys(): Set<String> = disk.keys.toSet()
    }

    // Real-shaped ids: what the app mints is a UUID (AccountStore.add), and the cursor key is the
    // account id and the mailbox id concatenated with no separator (MailRepository.syncKey).
    private val alice = "6f1a5a0e-3f2b-4f6a-9c11-0f6b7f2a1d31"
    private val bob = "b28c9d77-5a41-4a0e-9e2b-3c6d0a1e4f52"
    private fun key(account: String, mailbox: String) = "$account$mailbox"

    private fun cursors(store: FakeStore) = SyncCursors(store)

    private fun seed(store: FakeStore, account: String, vararg mailboxes: String): SyncCursors {
        val cursors = cursors(store)
        mailboxes.forEach { cursors.put(key(account, it), SyncState("q-$it", "e-$it")) }
        return cursors
    }

    // -- 1. the decision itself -------------------------------------------------------------------

    @Test fun `widening the window is a change`() {
        assertTrue(syncWindowChanged(SyncWindow.COUNT_50, SyncWindow.ALL))
    }

    @Test fun `narrowing the window is a change too`() {
        // Both directions, one rule: the same full re-query applies a narrower window (and evicts
        // the surplus). "Only when it widens" would be a second branch for one semantics.
        assertTrue(syncWindowChanged(SyncWindow.ALL, SyncWindow.COUNT_50))
    }

    @Test fun `two windows that fetch the same number of messages are still a change`() {
        // COUNT_200 and DAYS_90 share limit = 200 and differ only in maxAgeDays. A comparison
        // written on the fetch size would call this "no change" and leave the row inert — which is
        // the whole defect, on a pair of values the picker offers side by side.
        assertEquals(SyncWindow.COUNT_200.limit, SyncWindow.DAYS_90.limit)
        assertTrue(syncWindowChanged(SyncWindow.DAYS_90, SyncWindow.COUNT_200))
    }

    @Test fun `re-choosing the value already in place is not a change`() {
        // A choice row calls onSelect on the current value too. That gesture must not cost a full
        // re-query of the folder.
        SyncWindow.entries.forEach { assertFalse(it.name, syncWindowChanged(it, it)) }
    }

    // -- 2. the drop falls in memory AND on disk --------------------------------------------------

    @Test fun `the dropped cursor is gone from memory`() {
        val store = FakeStore()
        val cursors = seed(store, alice, "inbox")
        cursors.dropAccount(alice)
        assertNull(
            "the cursor is still in memory: this refresh takes the delta branch, which never " +
                "sends the window",
            cursors.load(key(alice, "inbox")),
        )
    }

    @Test fun `the dropped cursor is gone from disk, so a cold start cannot resume it`() {
        // The half that matters at the next process start (issue #17 persisted the cursors on
        // purpose). Dropping only the map leaves the app applying the OLD window for ever after
        // the first restart, and nothing on screen says so.
        val store = FakeStore()
        val cursors = seed(store, alice, "inbox")
        cursors.dropAccount(alice)
        assertEquals(emptySet<String>(), store.keys())
        val afterProcessDeath = cursors(store)
        assertNull(
            "a fresh process read the cursor back off disk — the window change survived nothing",
            afterProcessDeath.load(key(alice, "inbox")),
        )
    }

    @Test fun `a cursor written by a previous process life is dropped too`() {
        // The one a cold start would resume from: on disk, never in this process's map. Reading
        // the keys of the memory half only would walk straight past it.
        val store = FakeStore()
        store.save(key(alice, "inbox"), "q", "e")
        val freshProcess = cursors(store)
        freshProcess.dropAccount(alice)
        assertEquals(emptySet<String>(), store.keys())
        assertNull(freshProcess.load(key(alice, "inbox")))
    }

    @Test fun `every folder of the account falls, not just the one on screen`() {
        val store = FakeStore()
        val cursors = seed(store, alice, "inbox", "archive", "sent")
        cursors.dropAccount(alice)
        listOf("inbox", "archive", "sent").forEach {
            assertNull("$it kept its cursor", cursors.load(key(alice, it)))
        }
    }

    // -- 2b. the other two ways a cursor falls, executed too ---------------------------------------

    @Test fun `dropping one cursor takes it off disk as well`() {
        // drop() is what the full query calls when the server answers without a state to resume
        // from. Left on disk, that stale pair is what the next COLD START reads back and resumes a
        // delta against — nothing shows while the process lives.
        val store = FakeStore()
        val cursors = seed(store, alice, "inbox", "archive")
        cursors.drop(key(alice, "inbox"))
        assertEquals(setOf(key(alice, "archive")), store.keys())
        assertNull(cursors(store).load(key(alice, "inbox")))
        assertNotNull("dropping one key must not take the folder next to it", cursors.load(key(alice, "archive")))
    }

    @Test fun `clearing forgets every cursor on disk, not only in memory`() {
        // clear() is what "Clear account cache" runs behind. A clear that only emptied the map
        // would look right for the rest of the process's life; after a restart the cold start reads
        // a cursor back, takes the delta branch against an EMPTY cache, fetches nothing, and the
        // folder never fills again.
        val store = FakeStore()
        val cursors = cursors(store)
        cursors.put(key(alice, "inbox"), SyncState("qa", "ea"))
        cursors.put(key(bob, "inbox"), SyncState("qb", "eb"))
        cursors.clear()
        assertEquals(emptySet<String>(), store.keys())
        assertNull(cursors.load(key(alice, "inbox")))
        assertNull(cursors(store).load(key(bob, "inbox")))
    }

    @Test fun `a cursor is written through to disk as it is put`() {
        // The write-through the two above depend on: without it a cursor never reaches disk and
        // every process start is a full re-query (issue #17 in reverse).
        val store = FakeStore()
        cursors(store).put(key(alice, "inbox"), SyncState("q", "e"))
        assertEquals("q" to "e", store.disk[key(alice, "inbox")])
    }

    // -- 3. only the account the user touched -----------------------------------------------------

    @Test fun `the other account keeps its cursors, in both halves`() {
        // This is what separates the fix from resetSyncState(): a global reset sends every folder
        // of every account into a full re-query for a setting changed on one of them.
        val store = FakeStore()
        val cursors = cursors(store)
        cursors.put(key(alice, "inbox"), SyncState("qa", "ea"))
        cursors.put(key(bob, "inbox"), SyncState("qb", "eb"))
        cursors.put(key(bob, "archive"), SyncState("qb2", "eb2"))

        cursors.dropAccount(alice, listOf(alice, bob))

        assertNull(cursors.load(key(alice, "inbox")))
        assertEquals(SyncState("qb", "eb"), cursors.load(key(bob, "inbox")))
        assertEquals(SyncState("qb2", "eb2"), cursors.load(key(bob, "archive")))
        assertEquals(setOf(key(bob, "inbox"), key(bob, "archive")), store.keys())
    }

    @Test fun `two accounts on one server share a mailbox id and still fall apart`() {
        // Stalwart numbers mailboxes per account, so "inbox" is a different folder in each (#121);
        // the cursor key is the pair, and the drop must read it as the pair.
        val store = FakeStore()
        val cursors = cursors(store)
        cursors.put(key(alice, "inbox"), SyncState("qa", "ea"))
        cursors.put(key(bob, "inbox"), SyncState("qb", "eb"))
        cursors.dropAccount(bob, listOf(alice, bob))
        assertNotNull(cursors.load(key(alice, "inbox")))
        assertNull(cursors.load(key(bob, "inbox")))
    }

    // -- 4. the key is a prefix, and that has to be exact ------------------------------------------

    @Test fun `a sibling id starting with this one keeps its cursors`() {
        // Shipped ids are UUIDs, so this cannot happen today — which is exactly why a bare
        // startsWith would never be caught if an id of another shape ever appeared. The longest
        // matching account id owns the key.
        val keys = listOf("1inbox", "12inbox")
        assertEquals(listOf("1inbox"), cursorKeysOfAccount(keys, "1", listOf("1", "12")))
        assertEquals(listOf("12inbox"), cursorKeysOfAccount(keys, "12", listOf("1", "12")))
    }

    @Test fun `a blank account id drops nothing rather than everything`() {
        // "" prefixes every key: a blank id would silently turn the per-account drop into the
        // global reset this exists to avoid.
        val thrown = runCatching { cursorKeysOfAccount(listOf("anything"), "") }.exceptionOrNull()
        assertTrue("a blank account id must be refused, not accepted", thrown is IllegalArgumentException)
    }

    @Test fun `an unrelated key is never claimed`() {
        assertEquals(emptyList<String>(), cursorKeysOfAccount(listOf(key(bob, "inbox")), alice))
    }

    // -- 5. an IMAP account: nothing to drop, and no complaint -------------------------------------

    @Test fun `an account with no cursor at all drops quietly`() {
        // IMAP has no cursor: refreshImap re-queries the whole folder every time, so the new window
        // applies on the next refresh by itself. The drop still runs on that account and must be a
        // no-op, not an error.
        val store = FakeStore()
        val cursors = seed(store, bob, "inbox")
        cursors.dropAccount(alice, listOf(alice, bob))
        assertEquals(setOf(key(bob, "inbox")), store.keys())
    }

    @Test fun `dropping without any persisted store at all is harmless`() {
        // The store is null in tests and wherever it is not wired; the memory half must still fall.
        val cursors = SyncCursors()
        cursors.put(key(alice, "inbox"), SyncState("q", "e"))
        cursors.dropAccount(alice)
        assertNull(cursors.load(key(alice, "inbox")))
    }

    // -- 6. what the next refresh then does --------------------------------------------------------

    @Test fun `after the drop the mailbox has no cursor to resume, which is the full-query branch`() {
        // syncMailbox decides on exactly this: `val stored = loadSyncState(key)` then
        // `if (stored != null)`. No cursor means the full query — the only branch that sends the
        // window (`sizing.windowTarget`) to the server. The branch's own text is pinned in
        // [SyncCursorDropWiringTest].
        val store = FakeStore()
        val cursors = seed(store, alice, "inbox")
        assertNotNull("before the change, the delta branch is available", cursors.load(key(alice, "inbox")))
        cursors.dropAccount(alice)
        assertNull("after the change, only the full query is left", cursors.load(key(alice, "inbox")))
    }

    @Test fun `a cursor put back after the drop resumes deltas again`() {
        // The drop is one-shot, not a mode: the full query that follows writes a new cursor and the
        // account is back on cheap deltas.
        val store = FakeStore()
        val cursors = seed(store, alice, "inbox")
        cursors.dropAccount(alice)
        cursors.put(key(alice, "inbox"), SyncState("q2", "e2"))
        assertEquals(SyncState("q2", "e2"), cursors.load(key(alice, "inbox")))
        assertEquals(setOf(key(alice, "inbox")), store.keys())
    }
}
