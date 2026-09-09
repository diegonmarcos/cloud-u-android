package app.sterna.core.data.mail

import app.sterna.core.data.account.SyncWindow
import app.sterna.core.data.account.syncWindowChoices
import app.sterna.core.data.db.EmailRetentionRow
import app.sterna.core.jmap.WalkedPage
import app.sterna.core.jmap.nextWindowPageLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The count scale the "Messages to sync" row offers — 100 / 1 000 / 10 000 — and what each of those
 */
class SyncWindowScaleTest {

    /** A server that admits 500 objects per `Email/get` — what Stalwart advertises on the bench. */
    private val serverPage = 500

    // -- what the picker offers, executed ----------------------------------------------------------

    @Test fun `the offered count windows are 100, 1000 and 10000`() {
        assertEquals(
            "the scale the settings row offers changed — the labels, nine locales of them, are " +
                "written against these three numbers",
            listOf(100, 1_000, 10_000),
            syncWindowChoices().filter { it.maxAgeDays == null }.map { it.limit },
        )
        assertEquals(
            "the three age windows are part of the same row and are not touched by this scale",
            listOf(30, 90, 365),
            syncWindowChoices().mapNotNull { it.maxAgeDays },
        )
    }

    @Test fun `Everything is not on offer any more, and neither are the old three`() {
        // Retired, not deleted: the names stay decodable for ever (SyncWindowStoredNameTest).
        listOf(SyncWindow.ALL, SyncWindow.COUNT_50, SyncWindow.COUNT_200, SyncWindow.COUNT_500)
            .forEach {
                assertTrue(
                    "${it.name} is back on the settings picker — it is kept only so existing " +
                        "account records keep decoding, and it must not be choosable",
                    it !in syncWindowChoices(),
                )
            }
    }

    @Test fun `an account still on Everything gets the largest offered window, not a third number`() {
        assertEquals(
            "\"Everything\" now has a number of its own: the picker would show 10 000 as the " +
                "biggest choice while an untouched account quietly cached something else",
            10_000, SyncWindow.ALL.limit,
        )
        assertEquals(SyncWindow.COUNT_10000.limit, SyncWindow.ALL.limit)
    }

    @Test fun `the count windows are counts, so the age prune is never even reached`() {
        // `maxAgeDays == null` is what makes `InboxViewModel.refreshFolder` pass a null cutoff,
        // which is what makes MailRepository skip pruneRetention entirely (both guards are pinned
        // by SyncWindowReachesBothProtocolsTest). The witness keeps this from passing on a
        // SyncWindow that had lost all its ages.
        listOf(SyncWindow.COUNT_100, SyncWindow.COUNT_1000, SyncWindow.COUNT_10000, SyncWindow.ALL)
            .forEach { assertNull("${it.name} is an age window now — the retention prune would start running for it", it.maxAgeDays) }
        assertNotNull("no window carries an age any more; the witness is dead", SyncWindow.DAYS_30.maxAgeDays)
    }

    // -- role 1: the window to fetch -------------------------------------------------------------

    @Test fun `the request is capped to what the server admits, and only the request`() {
        val sizing = folderSyncSizing(windowLimit = SyncWindow.COUNT_10000.limit, serverCapacity = serverPage)
        assertEquals("one request may not ask for more than the server takes", serverPage, sizing.pageSize)
        assertEquals("the window itself must not be clamped to one request", 10_000, sizing.windowTarget)
        assertEquals("the retention floor is the window, whole", 10_000, sizing.retentionFloor)

        // A window SMALLER than one server page is its own page: nothing rounds it up.
        val small = folderSyncSizing(windowLimit = SyncWindow.COUNT_100.limit, serverCapacity = serverPage)
        assertEquals(100, small.pageSize)
        assertEquals(100, small.windowTarget)
        assertEquals(100, small.retentionFloor)
    }

    @Test fun `each offered window fetches exactly its own number, and stops there`() {
        // The folder is deeper than every offered window, so what stops each walk IS the window.
        // The numbers on the right are the labels, written out: a member whose limit no longer
        // matches the label it is shown under fails here.
        assertEquals(100, walk(folder = 12_000, target = SyncWindow.COUNT_100.limit).fetched)
        assertEquals(1_000, walk(folder = 12_000, target = SyncWindow.COUNT_1000.limit).fetched)
        assertEquals(10_000, walk(folder = 12_000, target = SyncWindow.COUNT_10000.limit).fetched)
        assertEquals(
            "an account left on \"Everything\" must walk the largest offered window",
            10_000, walk(folder = 12_000, target = SyncWindow.ALL.limit).fetched,
        )
    }

    @Test fun `a window of ten thousand is twenty requests of five hundred, not one of ten thousand`() {
        // The plumbing this volet relies on: the window is walked one SERVER page at a time and the
        // pages are written as they land. Without it, 10 000 would have the memory profile of the
        // unbounded window that was just withdrawn.
        val sizing = folderSyncSizing(windowLimit = SyncWindow.COUNT_10000.limit, serverCapacity = serverPage)
        val walked = walk(folder = 12_000, target = sizing.windowTarget, pageSize = sizing.pageSize)
        assertEquals(20, walked.requested.size)
        assertEquals("every request is one server page", setOf(500), walked.requested.toSet())
        assertEquals(10_000, walked.fetched)
    }

    @Test fun `the last request of a walk asks for the remainder, not for a whole page`() {
        // 1 000 on a server admitting 500 is two full pages; 100 is one short one. The arithmetic
        // is `minOf(target - fetched, pageSize)` and it may never ask for more than is left.
        assertEquals(listOf(500, 500), walk(folder = 12_000, target = SyncWindow.COUNT_1000.limit).requested)
        assertEquals(listOf(100), walk(folder = 12_000, target = SyncWindow.COUNT_100.limit).requested)
    }

    @Test fun `a folder shallower than the window ends the walk itself`() {
        val walked = walk(folder = 300, target = SyncWindow.COUNT_10000.limit)
        assertEquals(300, walked.fetched)
        assertEquals("one request, and no second round trip per refresh for ever after", 1, walked.requested.size)

        // The stop condition, called directly: `Email/query` returned fewer ids than it was asked
        // for, so there is nothing behind them.
        assertNull(
            nextWindowPageLimit(
                fetched = 300,
                target = SyncWindow.COUNT_10000.limit,
                pageSize = serverPage,
                last = WalkedPage(requested = serverPage, queryCount = 300, added = 300),
            ),
        )
    }

    @Test fun `a page that adds nothing ends it too`() {
        // The anti-spin guard: an anchor that stops advancing, or a server answering the same
        // slice. Under the biggest window that is 19 requests this saves, per refresh.
        assertNull(
            nextWindowPageLimit(
                fetched = 500,
                target = SyncWindow.COUNT_10000.limit,
                pageSize = serverPage,
                last = WalkedPage(requested = serverPage, queryCount = serverPage, added = 0),
            ),
        )
    }

    @Test fun `a background pass walks a deep folder in the server's pages, not in fifties`() {
        // The push/worker and unified passes carry a hard-coded 50, and until the sizing of the
        // full-query branch was derived from the account they made every REQUEST ask for fifty. A
        // 10 000-message window is then 200 sequential round trips instead of 20 — on the pass that
        // runs FIRST after the setting changed, because the cursor has just fallen.
        val sizing = fullQuerySizing(
            accountWindow = SyncWindow.COUNT_10000.limit,
            requestedByCaller = 50,
            serverCapacity = serverPage,
        )
        assertEquals("the caller's 50 must not size the window", 10_000, sizing.windowTarget)
        val walked = walk(folder = 20_000, target = sizing.windowTarget, pageSize = sizing.pageSize)
        assertEquals(10_000, walked.fetched)
        assertEquals("the background pass is walking the folder fifty messages at a time", 20, walked.requested.size)
        // The witness: this is what the same walk costs at the caller's 50, i.e. what was shipped
        // before that reprise. Without it the number above is just a number.
        assertEquals(200, walk(folder = 20_000, target = sizing.windowTarget, pageSize = 50).requested.size)
    }

    @Test fun `a server advertising nothing usable still walks, one message at a time`() {
        // pageSize floored at 1: the walk advances instead of asking for zero for ever.
        val sizing = folderSyncSizing(windowLimit = SyncWindow.COUNT_100.limit, serverCapacity = 0)
        assertEquals(1, sizing.pageSize)
        val walked = walk(folder = 7, target = sizing.windowTarget, pageSize = sizing.pageSize)
        assertEquals(7, walked.fetched)
        assertEquals(8, walked.requested.size)
    }

    // -- role 2: the retention floor --------------------------------------------------------------

    @Test fun `the floor of each offered window is that window, in rows kept`() {
        // Every row is older than the cutoff and nothing is fresh or spared, so the ONLY thing
        // standing between this folder and the prune is the floor. 1 200 rows: the numbers on the
        // right are 1200 - the window, written out.
        val folder = agedRows(1200)
        assertEquals(1_100, evicted(folder, SyncWindow.COUNT_100.limit))
        assertEquals(200, evicted(folder, SyncWindow.COUNT_1000.limit))
        assertEquals(
            "the biggest window prunes a folder smaller than itself",
            0, evicted(folder, SyncWindow.COUNT_10000.limit),
        )
        assertEquals(
            "an account left on \"Everything\" must keep what the biggest offered window keeps",
            0, evicted(folder, SyncWindow.ALL.limit),
        )
    }

    @Test fun `the floor really is a floor - the newest rows are the ones kept`() {
        // Not just how many, WHICH: the floor keeps the newest N and the prune takes the rest.
        val evictions = retentionEvictions(agedRows(1200), CUTOFF, emptySet(), emptySet(), SyncWindow.COUNT_1000.limit)
        assertEquals(200, evictions.size)
        assertEquals(
            "the prune evicted something other than the 200 oldest rows",
            (1..200).map { "m$it" }.toSet(), evictions.toSet(),
        )
    }

    @Test fun `the floor is the window and NEVER the size of a request`() {
        // Codeberg #110, and the trap folderSyncSizing exists for: the two numbers are handed
        // out by the same function and one of them is capped. Capping the other makes the prune
        // delete, seconds after the refresh, mail the user asked to keep — under the 10 000 window
        // it would keep the server's 500 and evict the other 9 500.
        val sizing = folderSyncSizing(windowLimit = SyncWindow.COUNT_10000.limit, serverCapacity = serverPage)
        val folder = agedRows(1200)
        assertEquals(
            "the retention floor is being capped by the server's per-request limit: the prune " +
                "keeps ${sizing.pageSize} rows of a window the user set to 10 000",
            0, evicted(folder, sizing.retentionFloor),
        )
        assertEquals(
            "the witness: passing the PAGE size as the floor is what that mutation looks like, " +
                "and it costs 700 messages on this folder",
            700, evicted(folder, sizing.pageSize),
        )
    }

    // -- what the biggest window hands the reconcile ------------------------------------------------

    @Test fun `the reconcile still cuts its deletes into batches SQLite will bind`() {
        // The keep set of a full query is now up to 10 000 ids, and the complement is computed once
        // against all of it before being cut up (`reconcileEvictions`). 999 bound variables is the
        // ceiling below Android 12 — Codeberg #29, the bug where a big window threw before the sync
        // cursor was stored and the folder never synced again.
        val cached = (1..12_000).map { "c$it" }
        val batches = reconcileEvictions(cached, keepIds = emptySet(), spareIds = emptySet())
        assertEquals("nothing may be dropped by the chunking", 12_000, batches.sumOf { it.size })
        assertEquals("no batch may exceed the file's bound", emptyList<Int>(), batches.map { it.size }.filter { it > MAX_CHANGES })
        assertTrue("the bound must stay clear of SQLite's 999", MAX_CHANGES < 999)
    }

    // -- the harness ------------------------------------------------------------------------------

    /** How many of [folder] the prune takes with [keepNewest] as its floor. */
    private fun evicted(folder: List<EmailRetentionRow>, keepNewest: Int): Int =
        retentionEvictions(folder, CUTOFF, emptySet(), emptySet(), keepNewest).size

    /** What a walk driven by [nextWindowPageLimit] did: what it asked for, what came back, and the
     *  total it accumulated. Decides nothing — the limits come from the shipped function. */
    private data class Walked(val requested: List<Int>, val returned: List<Int>, val fetched: Int)

    /**
     * Walk a folder of [folder] messages with a window of [target], one page at a time, exactly as
     */
    private fun walk(folder: Int, target: Int, pageSize: Int = serverPage): Walked {
        val requested = mutableListOf<Int>()
        val returned = mutableListOf<Int>()
        var fetched = 0
        var limit = nextWindowPageLimit(fetched = 0, target = target, pageSize = pageSize, last = null)
        while (limit != null) {
            assertTrue("the walk asked for $limit messages", limit > 0)
            requested += limit
            // The server answers with what is left of the folder, capped by what was asked for.
            val answered = minOf(limit, folder - fetched)
            returned += answered
            fetched += answered
            assertTrue(
                "this walk has made ${requested.size} requests over a folder of $folder — it is " +
                    "not terminating",
                requested.size < 10_000,
            )
            limit = nextWindowPageLimit(
                fetched = fetched,
                target = target,
                pageSize = pageSize,
                last = WalkedPage(requested = limit, queryCount = answered, added = answered),
            )
        }
        return Walked(requested, returned, fetched)
    }

    /** [count] cached rows, all dated well before [CUTOFF] — nothing but the floor can save them.
     *  `m1` is the oldest, `m$count` the newest. */
    private fun agedRows(count: Int): List<EmailRetentionRow> =
        (1..count).map { EmailRetentionRow(id = "m$it", sortKey = it.toLong()) }

    private companion object {
        /** Newer than every row [agedRows] makes, so the age clause is true for all of them. */
        const val CUTOFF = 1_000_000L
    }
}
