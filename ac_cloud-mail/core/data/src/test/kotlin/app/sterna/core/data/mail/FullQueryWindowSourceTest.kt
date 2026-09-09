package app.sterna.core.data.mail

import app.sterna.core.data.account.SyncWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Dropping an account's cursors opens the full-query branch — and TWO of the four paths that can
 */
class FullQueryWindowSourceTest {

    /** What `refreshAccountFolders` and `refreshAllInboxes` pass when nobody tells them otherwise —
     *  pinned as a line of the shipped source by [SyncCursorDropWiringTest]. */
    private val backgroundLimit = 50

    /**
     * One folder, as the full-query branch leaves it: `queryEmailsWindow` walks back [windowTarget]
     * messages, `replaceMailbox` makes the cache exactly that page, `putSyncState` re-arms.
     */
    private class Folder(val onServer: List<String>) {
        var cache: List<String> = emptyList()
        var cursor: String? = null

        fun fullQuery(windowTarget: Int) {
            val page = onServer.take(windowTarget)
            cache = page
            cursor = "state@${page.size}"
        }
    }

    private fun folderOf(size: Int) = Folder((1..size).map { "m$it" })

    // -- the enchaînement this reprise exists for --------------------------------------------------

    @Test fun `a background pass arriving before the pull applies the window, not its own 50`() {
        // 50 → 500, the cursor falls (the fix of this volet), and a push lands FIRST.
        val folder = folderOf(size = 800)
        folder.cache = folder.onServer.take(SyncWindow.COUNT_50.limit)
        folder.cursor = "before"
        folder.cursor = null

        folder.fullQuery(
            fullQueryWindowTarget(
                accountWindow = SyncWindow.COUNT_500.limit,
                requestedByCaller = backgroundLimit,
            ),
        )

        assertEquals(
            "the push pass re-queried with its own 50: the cache is truncated to 50 rows for a " +
                "window set to 500, and the cursor below is re-armed on that truncation — the " +
                "deltas resume and never fetch backwards, so the setting is inert again and the " +
                "user has LOST cache as well",
            SyncWindow.COUNT_500.limit, folder.cache.size,
        )
        assertNotNull("the full query re-arms the cursor either way", folder.cursor)
    }

    @Test fun `the same for the unified inbox arriving first`() {
        // Same 50, other caller: the unified refresh writes the same inbox as the folder refresh
        // and the last writer wins. Nothing about this is specific to push.
        //
        // Rewritten by the volet that unbounded `ALL`, and for one reason only: a folder of
        // `ALL.limit + 200` cannot be built any more — the addition overflows and `(1..size)` is
        val bounded = folderOf(size = SyncWindow.COUNT_500.limit + 200)
        bounded.cursor = null
        bounded.fullQuery(fullQueryWindowTarget(SyncWindow.COUNT_500.limit, backgroundLimit))
        assertEquals(SyncWindow.COUNT_500.limit, bounded.cache.size)

        val everything = folderOf(size = 1200)
        everything.cursor = null
        everything.fullQuery(fullQueryWindowTarget(SyncWindow.ALL.limit, backgroundLimit))
        assertEquals(
            "the unified pass re-queried with its own 50 instead of the account's window",
            1200, everything.cache.size,
        )
    }

    @Test fun `a folder smaller than the window comes back whole`() {
        val folder = folderOf(size = 12)
        folder.cursor = null
        folder.fullQuery(fullQueryWindowTarget(SyncWindow.COUNT_500.limit, backgroundLimit))
        assertEquals(12, folder.cache.size)
    }

    @Test fun `after the drop and one full query the folder holds the window it was set to`() {
        // The end-to-end shape of the volet: cursor falls, whichever path arrives first re-queries
        // at the account's window, the cursor comes back on a cache that matches the setting.
        val cursors = SyncCursors()
        val key = "acc" + "inbox"
        cursors.put(key, SyncState("q", "e"))
        cursors.dropAccount("acc")
        assertNull(cursors.load(key))

        val folder = folderOf(size = 800)
        folder.fullQuery(fullQueryWindowTarget(SyncWindow.COUNT_500.limit, backgroundLimit))
        cursors.put(key, SyncState(folder.cursor!!, "e2"))

        assertEquals(500, folder.cache.size)
        assertNotNull(cursors.load(key))
    }

    // -- the rule itself ---------------------------------------------------------------------------

    @Test fun `the account's window wins over the caller's number`() {
        assertEquals(500, fullQueryWindowTarget(accountWindow = 500, requestedByCaller = 50))
    }

    @Test fun `it wins when it is SMALLER too`() {
        // Not "whichever is bigger": the window is the user's answer to how much to keep, in both
        // directions, and a caller asking for more than it has no more right to it than one asking
        // for less.
        assertEquals(50, fullQueryWindowTarget(accountWindow = 50, requestedByCaller = 500))
    }

    @Test fun `with no account to read, the caller's number stands`() {
        // The account row is gone (removed mid-refresh): the branch still has to size itself, and
        // the caller's number is the only one left. Never zero — that would replace the folder
        // with an empty page.
        assertEquals(50, fullQueryWindowTarget(accountWindow = null, requestedByCaller = 50))
    }

    @Test fun `every window the picker offers is carried through unchanged`() {
        SyncWindow.entries.forEach {
            assertEquals(it.name, it.limit, fullQueryWindowTarget(it.limit, backgroundLimit))
        }
    }

    // -- the whole sizing of the branch, not only its target ---------------------------------------

    @Test fun `a background pass asks the SERVER's page size, not its own fifty`() {
        // The second half of the same defect. The target was read off the account and the PAGE
        // was not, so a push or unified pass carrying 50 asked for fifty messages per request on a
        val sizing = fullQuerySizing(
            accountWindow = SyncWindow.ALL.limit,
            requestedByCaller = backgroundLimit,
            serverCapacity = 500,
        )
        assertEquals("the walk aims at the account's window", SyncWindow.ALL.limit, sizing.windowTarget)
        assertEquals("one request asks for what the server admits", 500, sizing.pageSize)
        assertEquals("and the retention floor is still the window, never a request size", SyncWindow.ALL.limit, sizing.retentionFloor)
    }

    @Test fun `the server still has the last word on one request`() {
        // The account's window feeds `requestPageSize`; it does not bypass it. A server admitting
        // 120 gets 120, whatever the user set — asking for more is refused whole (RFC 8620 §5.1),
        // which is the failure that made "All" stop syncing folders in 1.4.7.
        val sizing = fullQuerySizing(accountWindow = 500, requestedByCaller = 50, serverCapacity = 120)
        assertEquals(500, sizing.windowTarget)
        assertEquals(120, sizing.pageSize)
        assertEquals(500, sizing.retentionFloor)
    }

    @Test fun `a caller's own number still sizes the branch when there is no account row`() {
        val sizing = fullQuerySizing(accountWindow = null, requestedByCaller = 50, serverCapacity = 500)
        assertEquals(50, sizing.windowTarget)
        assertEquals(50, sizing.pageSize)
    }

    @Test fun `a small account window is not inflated to the server's page`() {
        // "50 messages" must stay one request of fifty: the page is `min(wanted, capacity)`, and
        // the wanted is the window.
        val sizing = fullQuerySizing(accountWindow = 50, requestedByCaller = 500, serverCapacity = 500)
        assertEquals(50, sizing.windowTarget)
        assertEquals(50, sizing.pageSize)
    }

    // -- the IMAP half of the same rule --------------------------------------------------------------

    @Test fun `the unified inbox must not shrink an IMAP folder to its own fifty`() {
        // The IMAP twin of the first test in this file, and it is reachable in three gestures:
        // an IMAP account set to "Everything", pull on the folder, tap "All inboxes", come back.
        // `refreshAllInboxes` reaches `imapWriteThrough` — which ends in a reconcile — with its
        // hard-coded 50. The reconcile DELETES every cached row it is not given.
        //
        // The decision is the shipped one, executed; that `imapWriteThrough` really asks it is
        // pinned line for line by ImapWindowWiringTest (MailRepository cannot be built here).
        val folder = folderOf(size = 1200)
        folder.cache = folder.onServer // the folder refresh had cached it whole

        folder.fullQuery(fullQueryWindowTarget(SyncWindow.ALL.limit, backgroundLimit))

        assertEquals(
            "the unified refresh re-walked the IMAP folder with its own 50 and the reconcile " +
                "deleted the rest: 1150 cached messages gone, on an account set to \"Everything\"",
            1200, folder.cache.size,
        )
    }

    @Test fun `and it must not inflate one either, when the account asked for fifty`() {
        // The rule is not "take the bigger": an account set to "50 messages" gets 50 from the
        // unified pass too, exactly as before.
        val folder = folderOf(size = 1200)
        folder.fullQuery(fullQueryWindowTarget(SyncWindow.COUNT_50.limit, backgroundLimit))
        assertEquals(50, folder.cache.size)
    }

    // -- what this must NOT change -----------------------------------------------------------------

    @Test fun `the per-request page size is still the one negotiated with the server`() {
        // Only the number of messages aimed at changes hands. The request size stays capped by
        // maxObjectsInGet, and the retention floor stays the window and never a request size —
        // capping that one is Codeberg #110 reopened.
        val sizing = folderSyncSizing(windowLimit = 500, serverCapacity = 120)
        assertEquals(500, sizing.windowTarget)
        assertEquals(120, sizing.pageSize)
        assertEquals(500, sizing.retentionFloor)
        assertEquals(500, fullQueryWindowTarget(sizing.retentionFloor, sizing.pageSize))
    }
}
