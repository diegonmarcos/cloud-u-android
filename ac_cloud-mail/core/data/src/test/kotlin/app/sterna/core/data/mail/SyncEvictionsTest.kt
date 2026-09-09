package app.sterna.core.data.mail

import app.sterna.core.data.db.EmailRetentionRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The eviction decisions of a sync (see SyncEvictions.kt): which cached ids a delta drops, which
 */
class SyncEvictionsTest {

    private val never: (String) -> Boolean = { false }

    // -- deltaEvictions ------------------------------------------------------------------

    @Test fun reorderedRowIsNotEvicted() {
        // Present in both removed and added = a reorder (e.g. favouriting pins to the top).
        val out = deltaEvictions(
            removed = listOf("e1", "e2"),
            added = setOf("e1"),
            destroyed = emptyList(),
            isProtected = never,
        )
        assertEquals(listOf("e2"), out)
    }

    @Test fun destroyedIdsAreEvicted() {
        val out = deltaEvictions(
            removed = emptyList(),
            added = emptySet(),
            destroyed = listOf("e9"),
            isProtected = never,
        )
        assertEquals(listOf("e9"), out)
    }

    @Test fun protectedRemovalIsSpared() {
        // A delta computed from a pre-mutation query state can report a just-flagged row as
        // removed although it only changed a keyword — the spare must keep it (audit C9).
        val out = deltaEvictions(
            removed = listOf("justFlagged", "reallyGone"),
            added = emptySet(),
            destroyed = emptyList(),
            isProtected = { it == "justFlagged" },
        )
        assertEquals(listOf("reallyGone"), out)
    }

    @Test fun protectedDestroyIsSparedByTheDeltaButCaughtByTheSweep() {
        // Documents the residual C9 hazard: a spared id's destroy notice is filtered while
        // the cursors advance past it — the DELTA never prunes it...
        val delta = deltaEvictions(
            removed = listOf("ghost"),
            added = emptySet(),
            destroyed = listOf("ghost"),
            isProtected = { it == "ghost" },
        )
        assertEquals(emptyList<String>(), delta)
        // ...but the ghost sweep does, because Email/get's notFound is an authoritative point
        // lookup that the spare deliberately does not shield. WHETHER that sweep runs in this
        // cycle is a separate question, decided by shouldSweepGhosts — see the #107 probe cases.
        val swept = ghostEvictions(cachedIds = listOf("ghost", "alive"), notFound = setOf("ghost"))
        assertEquals(listOf("ghost"), swept)
    }

    // -- sparedEvictions (what the sync log reports) --------------------------------------

    @Test fun aDestroyArrivingInsideTheSparingWindowIsNamedAsADestroy() {
        // The scenario the class doc admits to and nothing asserted until now: the server says the
        // message is GONE while the id is still inside its 45 s local-mutation window (the user
        // read or flagged it moments before it was destroyed elsewhere). The delta keeps the row…
        val removed = listOf("ghost")
        val destroyed = listOf("ghost")
        val protect: (String) -> Boolean = { it == "ghost" }
        assertEquals(emptyList<String>(), deltaEvictions(removed, setOf(), destroyed, protect))
        // …and the log must say WHICH row and that it was a destroy, not a mere removal — that
        // word is the whole difference between a benign spare and a ghost.
        assertEquals(
            listOf("ghost" to SpareReason.DESTROY),
            sparedEvictions(removed, setOf(), destroyed, protect),
        )
    }

    @Test fun aDestroyOutranksARemovalForTheSameId() {
        // Reported in both deltas at once: "removal" would understate it — the message is gone.
        assertEquals(
            listOf("x" to SpareReason.DESTROY),
            sparedEvictions(listOf("x"), emptySet(), listOf("x"), isProtected = { true }),
        )
    }

    @Test fun aSparedRemovalIsReportedAsSuchAndNotAsADestroy() {
        // The spare's normal, benign job: a just-flagged row reported as removed off a
        // pre-mutation query state. It must NOT be logged as a destroy, or the log cries wolf
        // on every flag change and the real losses drown in it.
        assertEquals(
            listOf("justFlagged" to SpareReason.REMOVAL),
            sparedEvictions(listOf("justFlagged"), emptySet(), emptyList()) { it == "justFlagged" },
        )
    }

    @Test fun anEvictedIdIsNeverAlsoReportedAsSpared() {
        // The two functions partition the delta: what is evicted is not logged as kept, and what
        // is logged as kept is not evicted. A log claiming otherwise would be actively misleading.
        val removed = listOf("kept", "gone")
        val destroyed = listOf("destroyedKept")
        val protect: (String) -> Boolean = { it.startsWith("kept") || it.endsWith("Kept") }
        val evicted = deltaEvictions(removed, emptySet(), destroyed, protect)
        val spared = sparedEvictions(removed, emptySet(), destroyed, protect).map { it.first }
        assertEquals(listOf("gone"), evicted)
        assertEquals(setOf("destroyedKept", "kept"), spared.toSet())
        assertTrue(evicted.none { it in spared })
    }

    @Test fun aReorderedRowIsNotReportedAsSpared() {
        // Present in removed AND added = a reorder. Nothing was skipped, so nothing to log.
        assertEquals(
            emptyList<Pair<String, SpareReason>>(),
            sparedEvictions(listOf("e1"), setOf("e1"), emptyList(), isProtected = { true }),
        )
    }

    // -- deltaFetches: what an incremental sync pulls in, and therefore must protect --------

    @Test fun aDeltaFetchesTheAddedIdsTheCacheNeverHeld() {
        // The ordinary case, and the one that matters downstream: `new` has never been in the
        // cache, so this sync is where it arrives. `alreadyHere` was added by an earlier cycle
        // and is not re-fetched.
        assertEquals(
            listOf("new"),
            deltaFetches(added = setOf("new", "alreadyHere"), cachedIds = setOf("alreadyHere"), updated = emptyList()),
        )
    }

    @Test fun aDeltaRefetchesCachedRowsItReportsChangedButNotUnknownOnes() {
        // `updated` is filtered by the cache on purpose: an id we do not hold has nothing to
        // update, and fetching it would pull a message from outside the folder's window back in.
        assertEquals(
            listOf("mine"),
            deltaFetches(added = emptySet(), cachedIds = setOf("mine"), updated = listOf("mine", "someoneElses")),
        )
    }

    @Test fun anIdBothAddedAndUpdatedIsFetchedOnce() {
        assertEquals(
            listOf("x"),
            deltaFetches(added = setOf("x"), cachedIds = emptySet(), updated = listOf("x")),
        )
    }

    // -- retentionEvictions (the "Messages to sync" window) ------------------------------

    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    /** The default account setting: DAYS_90 — 90 days of mail, 200 messages a page. */
    private val cutoff90 = now - 90 * day
    private val floor200 = 200

    /** A cached row [daysAgo] days old (0 days = now); [daysAgo] < 0 means "no parsable date". */
    private fun row(id: String, daysAgo: Long) =
        EmailRetentionRow(id, if (daysAgo < 0) 0L else now - daysAgo * day)

    @Test fun theFreshPageIsNeverPrunedByTheAgeWindow() {
        // Codeberg #110 in the reporter's own numbers: a ten-message folder, eight of them from
        // December, on the default 90-day window. The refresh re-queried the folder and the
        //
        // keepNewest is 0 here ON PURPOSE, so the floor cannot be what saves them: this asserts
        // the fresh-ids clause alone, on the deep-folder case where the floor does not reach.
        val cached = (1..8).map { row("dec$it", 220) } + row("jul17", 13) + row("jul29", 1)
        val fresh = cached.mapTo(HashSet()) { it.id }
        assertEquals(
            emptyList<String>(),
            retentionEvictions(cached, cutoff90, freshIds = fresh, spareIds = emptySet(), keepNewest = 0),
        )
    }

    @Test fun anOldRowTheFreshPageDidNotCarryIsStillPruned() {
        // The negative witness, and the reason the case above proves anything: sparing what the
        // sync fetched must not amount to switching the window off. A row that has fallen off the
        // server's newest page AND out of the window is exactly what retention is for.
        val cached = listOf(row("stillThere", 220), row("scrolledInLongAgo", 400), row("recent", 1))
        assertEquals(
            listOf("scrolledInLongAgo"),
            retentionEvictions(
                cached,
                cutoff90,
                freshIds = setOf("stillThere", "recent"),
                spareIds = emptySet(),
                keepNewest = 0,
            ),
        )
    }

    @Test fun whatAnIncrementalDeltaJustFetchedIsSparedLikeAFullPage() {
        // The delta branch's half of #110, composed from the two shipped functions rather than
        // asserted on hand-made sets. A Sieve rule files a 2019 message into a deep folder: the
        val cachedIds = (1..250).mapTo(HashSet()) { "m$it" }
        val fetched = deltaFetches(added = setOf("sieveFiled2019"), cachedIds = cachedIds, updated = emptyList())
        val cache = (1..250).map { row("m$it", 200) } + row("sieveFiled2019", 2500)
        assertEquals(
            "the row this very sync fetched must not be in the evictions",
            emptyList<String>(),
            retentionEvictions(
                cache,
                cutoff90,
                freshIds = fetched.toSet(),
                spareIds = emptySet(),
                keepNewest = floor200,
            ).filter { it == "sieveFiled2019" },
        )
        // Control: had the delta reported nothing — the defect this replaced — the same row goes.
        assertTrue(
            "with no fetched ids reported, the prune deletes the message the sync just delivered",
            "sieveFiled2019" in retentionEvictions(
                cache,
                cutoff90,
                freshIds = emptySet(),
                spareIds = emptySet(),
                keepNewest = floor200,
            ),
        )
    }

    @Test fun aSyncThatCannotSayWhatItFetchedPrunesNothing() {
        // The fail-safe direction, and the distinction the whole fix rests on: NULL is "I do not
        // know what this sync brought in", not "it brought in nothing". The two must not behave
        // alike, or a future path that forgets to report its fetches silently starts deleting the
        // mail it just downloaded. Same contract as ghostEvictions' notFound, for the same reason.
        val cache = listOf(row("old", 400), row("recent", 1))
        assertEquals(
            emptyList<String>(),
            retentionEvictions(cache, cutoff90, freshIds = null, spareIds = emptySet(), keepNewest = 0),
        )
        // The empty set, on the same input, DOES prune — otherwise the case above would pass for
        // the wrong reason and null would be indistinguishable from empty.
        assertEquals(
            listOf("old"),
            retentionEvictions(cache, cutoff90, freshIds = emptySet(), spareIds = emptySet(), keepNewest = 0),
        )
    }

    @Test fun theRecentlyMutatedSpareIsNotUndoneByTheWindow() {
        // The reconcile spares a locally-restored row from the fresh page's reconcile
        // (EmailDao.deleteNotInSparing); the prune runs a few lines later on the same folder and
        val cache = (1..200).map { row("page$it", 5) } + row("undone2019", 2500)
        assertEquals(
            emptyList<String>(),
            retentionEvictions(
                cache,
                cutoff90,
                freshIds = (1..200).mapTo(HashSet()) { "page$it" },
                spareIds = setOf("undone2019"),
                keepNewest = floor200,
            ),
        )
        // Without the spare set the same refresh eats it — this is what the clause buys.
        assertEquals(
            listOf("undone2019"),
            retentionEvictions(
                cache,
                cutoff90,
                freshIds = (1..200).mapTo(HashSet()) { "page$it" },
                spareIds = emptySet(),
                keepNewest = floor200,
            ),
        )
    }

    @Test fun undatedRowsAreNotAncient() {
        // sortKey 0 = the message carried no date we could parse. The SQL prune this replaced
        // spared them (`sortKey > 0`) and so must this: undated is not old, and treating 0 as
        // epoch would delete every such row on the first refresh. The genuinely old row next to
        // it is the control: this asserts "undated is spared", not "nothing is pruned".
        assertEquals(
            listOf("old"),
            retentionEvictions(
                listOf(row("noDate", -1), row("old", 400)),
                cutoff90,
                freshIds = emptySet(),
                spareIds = emptySet(),
                keepNewest = 0,
            ),
        )
    }

    // -- the floor: the window's count keeps mail the age would drop ----------------------

    @Test fun aFolderSmallerThanTheFloorLosesNothingAtAll() {
        // The reporter's folder on a sync with nothing to spare. Ten rows against a floor of 200
        // takes the short-circuit — no row can possibly be outside the newest 200 — which is
        // exactly the shape of every small mailbox and worth pinning as such. The case below is
        // the one that exercises the floor's actual computation.
        val cached = (1..8).map { row("dec$it", 220) } + row("jul17", 13) + row("jul29", 1)
        assertEquals(
            emptyList<String>(),
            retentionEvictions(cached, cutoff90, freshIds = emptySet(), spareIds = emptySet(), keepNewest = floor200),
        )
    }

    @Test fun theFloorKeepsTheNewestNWhenItHasToBeComputed() {
        // Same ten-message folder plus one older row scrolled in long ago, against a floor of 10:
        // the cache is now bigger than the floor, so the ranking really runs. The folder's ten
        // survive and only the eleventh, oldest, row goes.
        val cached = (1..8).map { row("dec$it", 220) } + row("jul17", 13) + row("jul29", 1) +
            row("scrolledIn2015", 4000)
        assertEquals(
            listOf("scrolledIn2015"),
            retentionEvictions(cached, cutoff90, freshIds = emptySet(), spareIds = emptySet(), keepNewest = 10),
        )
    }

    @Test fun pastTheFloorTheAgeWindowStillApplies() {
        // The floor is a floor, not an off switch: on a folder deeper than the setting's count,
        // the rows below the newest N and outside the age window are still evicted. A busy inbox
        // keeps its ninety days, not its whole history.
        val cached = (1..5).map { row("old$it", 200) } + (1..3).map { row("recent$it", 2) }
        assertEquals(
            // Newest 4 = the three recent + the newest of the old five (ties broken on id, so
            // that is "old1"); the remaining four old rows fall outside both floor and window.
            listOf("old2", "old3", "old4", "old5"),
            retentionEvictions(cached, cutoff90, freshIds = emptySet(), spareIds = emptySet(), keepNewest = 4),
        )
    }

    @Test fun theFloorCountsTheNEWESTRowsAndNotJustAnyN() {
        // Which N the floor keeps is the whole decision: keeping an arbitrary N would leave the
        // list showing December while July sat pruned. All four rows here are outside the 90-day
        val cached = listOf(row("b", 300), row("d", 100), row("a", 400), row("c", 200))
        assertEquals(
            listOf("a", "b"),
            retentionEvictions(cached, cutoff90, freshIds = emptySet(), spareIds = emptySet(), keepNewest = 2)
                .sorted(),
        )
    }

    // -- ghostEvictions ------------------------------------------------------------------

    @Test fun sweepPrunesExactlyTheCachedNotFoundIds() {
        val out = ghostEvictions(
            cachedIds = listOf("e1", "e2", "e3"),
            notFound = setOf("e2", "elsewhere"),
        )
        // Only ids we actually cache are pruned; a notFound id we never held is ignored.
        assertEquals(listOf("e2"), out)
    }

    @Test fun sweepWithNoNotFoundPrunesNothing() {
        assertEquals(
            emptyList<String>(),
            ghostEvictions(cachedIds = listOf("e1", "e2"), notFound = emptySet()),
        )
    }

    @Test fun aSweepThatDidNotGetAnAnswerPrunesNothing() {
        // The transport-failure contract, and the reason it is a null rather than an empty set:
        // "the server did not answer" must not read as "the server found none of them". A sweep
        assertEquals(
            emptyList<String>(),
            ghostEvictions(cachedIds = listOf("e1", "e2", "e3"), notFound = null),
        )
    }

    // -- shouldSweepGhosts ---------------------------------------------------------------

    private val floor = 5 * 60_000L

    @Test fun firstSyncOfTheSessionAlwaysSweeps() {
        // A ghost that predates this run has no delta left to announce it: the once-per-mailbox
        // sweep is what kills it, so it must not depend on any state having advanced.
        assertTrue(
            shouldSweepGhosts(
                firstThisSession = true, stateAdvanced = false, vanishedFromMailbox = false,
                millisSinceLastSweep = 0, minIntervalMs = floor,
            ),
        )
    }

    @Test fun accountWideActivityAloneDoesNotResweep() {
        // The regression this gate exists for: Email/changes' state is account-wide, so it
        // advances on activity in ANY folder. Sweeping on that alone re-verified the whole
        // cache of every watched mailbox on nearly every incremental sync.
        assertFalse(
            shouldSweepGhosts(
                firstThisSession = false, stateAdvanced = true, vanishedFromMailbox = false,
                millisSinceLastSweep = 30_000, minIntervalMs = floor,
            ),
        )
    }

    @Test fun aRemovalInThisMailboxSweepsAtOnce() {
        // Something genuinely left THIS folder — the shape of a destroy the recently-mutated
        // spare can swallow — so verify immediately rather than waiting out the floor.
        assertTrue(
            shouldSweepGhosts(
                firstThisSession = false, stateAdvanced = true, vanishedFromMailbox = true,
                millisSinceLastSweep = 0, minIntervalMs = floor,
            ),
        )
    }

    @Test fun theFloorLetsTheSilentDestroyCaseThroughAgain() {
        // Some servers report a destroy in NEITHER delta (verified against Stalwart on a
        // delegated view), so the recurring sweep still has to run — once per interval.
        assertTrue(
            shouldSweepGhosts(
                firstThisSession = false, stateAdvanced = true, vanishedFromMailbox = false,
                millisSinceLastSweep = floor, minIntervalMs = floor,
            ),
        )
    }

    @Test fun anIdleAccountIsThrottledButNeverBlocked() {
        // FAILS ON THE PRE-FIX TREE (the second assertion).
        // Was `anIdleAccountNeverResweeps`, and its old name was the defect (Codeberg #107): "no
        assertFalse(
            shouldSweepGhosts(
                firstThisSession = false, stateAdvanced = false, vanishedFromMailbox = false,
                millisSinceLastSweep = floor - 1, minIntervalMs = floor,
            ),
        )
        assertTrue(
            shouldSweepGhosts(
                firstThisSession = false, stateAdvanced = false, vanishedFromMailbox = false,
                millisSinceLastSweep = 10 * floor, minIntervalMs = floor,
            ),
        )
    }

    // -- what happens AFTER a lost destroy notice (Codeberg #107) -------------------------
    //
    // These pinned the hole while it was open (they were written as characterisation cases against
    // the shipped gate). The gate has since changed, so they have been re-read and re-aimed rather

    @Test fun aDestroyReportedOnlyInTheChangesDeltaDoesNotTripTheImmediateSweep() {
        // vanishedFromMailbox is computed from queryChanges.removed ALONE (MailRepository), so a
        // destroy the server reports only in Email/changes.destroyed does not count as "something
        assertFalse(
            shouldSweepGhosts(
                firstThisSession = false, stateAdvanced = true, vanishedFromMailbox = false,
                millisSinceLastSweep = 30_000, minIntervalMs = floor,
            ),
        )
        assertEquals(
            "skip/throttled",
            sweepReason(
                firstThisSession = false, stateAdvanced = true, vanishedFromMailbox = false,
                millisSinceLastSweep = 30_000, minIntervalMs = floor,
            ),
        )
    }

    @Test fun aQuietAccountRefreshingForEverIsSweptWhenTheIntervalElapses() {
        // FAILS ON THE PRE-FIX TREE — it is the fix. The sync that lost the destroy notice also
        // stored the cursors it came with, so every later sync of a quiet account compares equal:
        assertFalse(
            "a refresh inside the interval must stay free",
            shouldSweepGhosts(
                firstThisSession = false, stateAdvanced = false, vanishedFromMailbox = false,
                millisSinceLastSweep = floor - 1, minIntervalMs = floor,
            ),
        )
        assertEquals(
            "skip/idle",
            sweepReason(
                firstThisSession = false, stateAdvanced = false, vanishedFromMailbox = false,
                millisSinceLastSweep = floor - 1, minIntervalMs = floor,
            ),
        )
        listOf(floor, 2 * floor, 100 * floor).forEach { elapsed ->
            assertTrue(
                "the sweep must run ${elapsed}ms after the last one, quiet account or not",
                shouldSweepGhosts(
                    firstThisSession = false, stateAdvanced = false, vanishedFromMailbox = false,
                    millisSinceLastSweep = elapsed, minIntervalMs = floor,
                ),
            )
            // The token is what the bench reads back: `floor/idle` is an existence check on an
            // account whose deltas said nothing — the line that could not be printed before.
            assertEquals(
                "floor/idle",
                sweepReason(
                    firstThisSession = false, stateAdvanced = false, vanishedFromMailbox = false,
                    millisSinceLastSweep = elapsed, minIntervalMs = floor,
                ),
            )
        }
    }

    @Test fun freshAccountActivityStillHealsItAndSaysSo() {
        // The other half, unchanged by the fix: when the account HAS moved, the elapsed floor
        // sweeps and logs `floor`. Two tokens for the same decision because the difference tells
        // the bench which of the two paths healed a row.
        assertTrue(
            shouldSweepGhosts(
                firstThisSession = false, stateAdvanced = true, vanishedFromMailbox = false,
                millisSinceLastSweep = floor, minIntervalMs = floor,
            ),
        )
        assertEquals(
            "floor",
            sweepReason(
                firstThisSession = false, stateAdvanced = true, vanishedFromMailbox = false,
                millisSinceLastSweep = floor, minIntervalMs = floor,
            ),
        )
    }

    // -- the full-query reconcile: what it drops, and in what size of statement ------------

    @Test fun noEvictionBatchCanOverrunTheSqliteBound() {
        // A folder scrolled deep (the mediator writes past the window) then re-queried whole: the
        // reconcile has thousands of rows to drop. One statement per id list means one statement
        // with thousands of bound variables, and SQLite below Android 12 accepts 999 — it throws,
        // before the sync cursor is stored, so the next refresh re-runs the same full query.
        val cached = (1..5000).map { "e$it" }
        val keep = (1..1000).map { "e$it" }.toSet()

        val batches = reconcileEvictions(cached, keep, spareIds = emptySet())

        assertTrue(
            "a batch of ${batches.maxOf { it.size }} ids goes into one IN (...)",
            batches.all { it.size <= MAX_CHANGES },
        )
        assertEquals(4000, batches.sumOf { it.size })
        assertEquals((1001..5000).map { "e$it" }, batches.flatten())
    }

    @Test fun theSetToDropIsDecidedAgainstTheWHOLEKeptListBeforeItIsCutUp() {
        // The trap of the obvious fix: chunking a `NOT IN (:keepIds)` into several statements
        // deletes everything outside each chunk in turn — the whole folder, one batch at a time.
        // Here nothing was dropped from the page, so nothing may be evicted, however it is cut.
        val cached = (1..1000).map { "e$it" }

        val batches = reconcileEvictions(cached, keepIds = cached.toSet(), spareIds = emptySet())

        assertEquals(emptyList<List<String>>(), batches)
    }

    @Test fun theRecentlyMutatedSpareSurvivesTheReconcile() {
        // Same protection deleteNotInSparing gave: a stale page must not clobber an optimistic
        // Undo the server has not caught up on yet.
        val cached = listOf("kept", "gone", "restored")

        val batches = reconcileEvictions(cached, keepIds = setOf("kept"), spareIds = setOf("restored"))

        assertEquals(listOf(listOf("gone")), batches)
    }

    // -- sweepReason must never contradict the gate ---------------------------------------

    @Test fun theLoggedReasonAgreesWithTheGateOverTheWholeInputGrid() {
        // The log is only worth reading if "skip/..." means exactly "no sweep ran". Pin it over
        // every combination rather than trusting two hand-kept `when` branches to stay in step.
        val bools = listOf(false, true)
        for (first in bools) for (advanced in bools) for (vanished in bools) {
            for (elapsed in listOf(0L, floor - 1, floor, 10 * floor)) {
                val swept = shouldSweepGhosts(first, advanced, vanished, elapsed, floor)
                val reason = sweepReason(first, advanced, vanished, elapsed, floor)
                assertTrue(
                    "first=$first advanced=$advanced vanished=$vanished elapsed=$elapsed → $reason",
                    swept == !reason.startsWith("skip"),
                )
            }
        }
    }
}
