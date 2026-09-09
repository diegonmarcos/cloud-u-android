package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stateful half of the ghost sweep's gate (Codeberg #107): the bookkeeping that decides when a
 */
class GhostSweepScheduleTest {

    private val floor = 5 * 60_000L

    /** A schedule whose clock this test drives by hand. */
    private class Bench(floorMs: Long) {
        var now = 1_000_000L
        val schedule = GhostSweepSchedule(minIntervalMs = floorMs, clock = { now })
        fun elapse(ms: Long) { now += ms }
    }

    private fun bench() = Bench(floor)

    /** A sync of [mailbox] that reports nothing at all — a quiet account's pull-to-refresh. */
    private fun Bench.quietSync(account: String = "acc", mailbox: String = "a") =
        schedule.claim(account, mailbox, stateAdvanced = false, vanishedFromMailbox = false)

    /** A sync whose account-wide state moved but which removed nothing from [mailbox]. */
    private fun Bench.busySync(account: String = "acc", mailbox: String = "a") =
        schedule.claim(account, mailbox, stateAdvanced = true, vanishedFromMailbox = false)

    // -- the fix: a quiet mailbox heals on its own, inside one process --------------------

    @Test fun theFirstSyncOfAProcessSweeps() {
        // A ghost inherited from a previous run has no delta left to announce it, so the very
        // first sync of each mailbox must check regardless of what the server says.
        val b = bench()
        val claim = b.quietSync()
        assertTrue(claim.sweep)
        assertEquals("session", claim.reason)
    }

    @Test fun aQuietMailboxIsSweptAgainOnceTheFloorElapsesWithoutRelaunchingTheApp() {
        // THE #107 fix, over the sequence that produced the bug report: the destroy is lost, the
        // account then says nothing ever again, and the user pulls to refresh. Before the fix
        // every one of these refreshes was a `skip/idle` and the row only died on a cold start.
        val b = bench()
        assertTrue(b.quietSync().sweep) // the session's first sweep consumes the floor
        repeat(4) {
            b.elapse(60_000)
            val claim = b.quietSync()
            assertFalse("a refresh inside the interval must not sweep", claim.sweep)
            assertEquals("skip/idle", claim.reason)
        }
        b.elapse(60_000) // five minutes since the last sweep
        val healing = b.quietSync()
        assertTrue("the quiet mailbox must be swept again without a relaunch", healing.sweep)
        assertEquals("floor/idle", healing.reason)
    }

    @Test fun refusedClaimsDoNotPushTheNextSweepAway() {
        // The trap the shape must avoid: if a REFUSED claim restamped the clock, a user pulling to
        // refresh every ten seconds would postpone the sweep for ever and the ghost would outlive
        // the fix. The floor is measured from the last sweep that actually ran.
        val b = bench()
        assertTrue(b.quietSync().sweep)
        repeat(29) { // 29 refreshes over the next 290 s, none of which may move the clock
            b.elapse(10_000)
            assertFalse(b.quietSync().sweep)
        }
        b.elapse(10_000)
        assertTrue("300 s after the last real sweep, whatever happened in between", b.quietSync().sweep)
    }

    // -- and it stays rare ---------------------------------------------------------------

    @Test fun aBusyMailboxIsNotSweptMoreOftenThanTheFloorAllows() {
        // Ten accounts pay this bill, so the cost has to be bounded: one existence check per
        // mailbox per interval, however many syncs land in between. Sixty syncs, one every ten
        // seconds, over ten minutes = three sweeps (the session's, then one per five minutes).
        val b = bench()
        var sweeps = 0
        repeat(61) { i ->
            if (i > 0) b.elapse(10_000)
            if (b.busySync().sweep) sweeps++
        }
        assertEquals(3, sweeps)
    }

    @Test fun aRemovalFromThisMailboxSweepsWithoutWaitingForTheFloor() {
        // The one bypass: something genuinely left THIS folder, which is the shape of a destroy
        // the recently-mutated spare can swallow. Check at once rather than a floor later.
        val b = bench()
        assertTrue(b.quietSync().sweep)
        b.elapse(10_000)
        val claim = b.schedule.claim("acc", "a", stateAdvanced = true, vanishedFromMailbox = true)
        assertTrue(claim.sweep)
        assertEquals("removal", claim.reason)
    }

    // -- scoping: ten accounts on one server share mailbox ids (#31/#92) ------------------

    @Test fun twoAccountsSharingAMailboxIdKeepSeparateSweepClocks() {
        // Stalwart numbers mailboxes per account, so every account's Inbox is "a". A clock keyed by
        // the mailbox id alone would let account A's sweep silence account B's for five minutes —
        // and with ten accounts, nine of them would go unswept behind the busiest one.
        val b = bench()
        assertTrue("A's own first sweep", b.quietSync(account = "A", mailbox = "a").sweep)
        val bFirst = b.quietSync(account = "B", mailbox = "a")
        assertTrue("B must not inherit A's sweep", bFirst.sweep)
        assertEquals("session", bFirst.reason)

        b.elapse(floor)
        assertTrue(b.quietSync(account = "A", mailbox = "a").sweep)
        // …and A's fresh sweep must not restamp B's clock either.
        assertTrue(b.quietSync(account = "B", mailbox = "a").sweep)
    }

    @Test fun oneAccountsTwoMailboxesKeepSeparateSweepClocks() {
        val b = bench()
        assertTrue(b.quietSync(mailbox = "inbox").sweep)
        assertTrue(b.quietSync(mailbox = "archive").sweep)
        b.elapse(60_000)
        assertFalse(b.quietSync(mailbox = "inbox").sweep)
        assertFalse(b.quietSync(mailbox = "archive").sweep)
    }

    @Test fun anAccountIdEndingWhereAMailboxIdBeginsIsNotTheSameKey() {
        // "$account$mailbox" would make ("ab", "1") and ("ab1", "") — or ("a", "b1") — one key.
        // The pair is what identifies a sweep target, not the concatenation.
        val b = bench()
        assertTrue(b.quietSync(account = "ab", mailbox = "1").sweep)
        assertTrue(b.quietSync(account = "a", mailbox = "b1").sweep)
        b.elapse(60_000)
        assertFalse(b.quietSync(account = "ab", mailbox = "1").sweep)
        assertFalse(b.quietSync(account = "a", mailbox = "b1").sweep)
    }

    // -- a sweep that never landed must not count as one ----------------------------------

    @Test fun aFailedSweepIsRetriedOnTheNextSyncInsteadOfWaitingOutTheFloor() {
        // A transport failure prunes nothing (ghostEvictions sees no answer), so the ghost is
        // still there. Releasing the claim hands back the once-per-process credit and the next
        // sync tries again, rather than the row surviving a whole interval on a lost packet.
        val b = bench()
        val failed = b.quietSync()
        assertTrue(failed.sweep)
        b.schedule.releaseFailed("acc", "a", failed)
        b.elapse(10_000)
        val retry = b.quietSync()
        assertTrue("the failed sweep must be retried at once", retry.sweep)
        assertEquals("session", retry.reason)
    }

    @Test fun aFailedSweepThatWasNotTheSessionsFirstWaitsOutAWholeFloor() {
        // The documented other half of releaseFailed, and a deliberate choice rather than an
        // oversight: only the once-per-process credit is handed back, so a sweep that fails on the
        val b = bench()
        assertTrue(b.quietSync().sweep) // spends the session credit
        b.elapse(floor)
        val recurring = b.quietSync()
        assertTrue(recurring.sweep)
        assertEquals("floor/idle", recurring.reason)
        b.schedule.releaseFailed("acc", "a", recurring) // it failed in transport: no-op by design
        b.elapse(10_000)
        assertFalse("a failed recurring sweep must not retry on the next sync", b.quietSync().sweep)
        b.elapse(floor - 10_000)
        assertTrue("…but it is retried one interval later", b.quietSync().sweep)
    }

    @Test fun onlyOneOfManySimultaneousSyncsIsGrantedTheSameWindow() {
        // Push, the fallback worker and a pull-to-refresh can sync one mailbox at the same instant.
        // Deciding and stamping atomically is what stops them all being granted the same floor:
        val b = bench()
        assertTrue(b.quietSync().sweep)
        b.elapse(floor)
        val granted = java.util.concurrent.atomic.AtomicInteger()
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = (1..16).map {
            Thread {
                start.await()
                if (b.quietSync().sweep) granted.incrementAndGet()
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(1, granted.get())
    }

    @Test fun aSweepThatSucceededIsNotRepeatedByAReleaseOfSomeoneElsesClaim() {
        // releaseFailed only ever gives back a credit the SAME claim took: a successful sweep
        // followed by a throttled (never-swept) claim must not open the gate.
        val b = bench()
        assertTrue(b.quietSync().sweep)
        b.elapse(10_000)
        val throttled = b.quietSync()
        assertFalse(throttled.sweep)
        b.schedule.releaseFailed("acc", "a", throttled)
        b.elapse(10_000)
        assertFalse("a refused claim carries no credit to give back", b.quietSync().sweep)
    }
}
