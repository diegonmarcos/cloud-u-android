package com.diegonmarcos.clouddrive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared-store mount check and the seed's retry budget, on the JVM. The defect: a store that
 * never became reachable (or a clone that could never succeed) made the seed pass return
 * retry for ever, with nothing telling the owner the store was the cause.
 */
class StoreReadyTest {

    @Test fun readyOnTheFirstAnswerAsksOnce() {
        var asked = 0
        assertNull(StoreReady.verify(sleep = {}) { asked++; null })
        assertEquals(1, asked)
    }

    @Test fun aStoreThatComesUpLateIsAcceptedWithinTheBound() {
        var asked = 0
        assertNull(StoreReady.verify(attempts = 3, sleep = {}) { if (++asked < 3) "not yet" else null })
        assertEquals(3, asked)
    }

    /** The probe that never succeeds is asked a bounded number of times and surfaces its sentence. */
    @Test fun aProbeThatNeverSucceedsIsBoundedAndSurfacesTheReason() {
        var asked = 0
        val slept = mutableListOf<Long>()
        val why = StoreReady.verify(attempts = 3, pauseMs = 5, sleep = { slept += it }) { asked++; StoreReady.NOT_REACHABLE }
        assertEquals(3, asked)
        assertEquals(listOf(5L, 5L), slept)
        assertTrue(why!!.contains("open it once, then retry"))
    }

    @Test fun aThrowingProbeIsAReasonNotACrash() {
        val why = StoreReady.verify(attempts = 2, sleep = {}) { error("CLOUD_EXEC did not connect") }
        assertTrue(why!!.contains("CLOUD_EXEC did not connect"))
    }

    private val names = listOf("a", "b", "c")

    /** THE LOOP: a store that never verifies, run pass after pass as WorkManager would. It must end. */
    @Test fun anUnreachableStoreStopsAfterTheBudgetInsteadOfRetryingForever() {
        val report = SeedReport.withoutStore(names, StoreReady.NOT_REACHABLE)
        var pass = 1
        var verdict = SeedPolicy.decide(report, pass)
        while (verdict == SeedPolicy.Next.RETRY && pass < 10_000) { pass++; verdict = SeedPolicy.decide(report, pass) }
        assertEquals(SeedPolicy.Next.GIVE_UP, verdict)
        assertEquals(SeedPolicy.MAX_PASSES, pass)
    }

    @Test fun theStoppedReportNamesTheRepositoriesTheCauseAndTheWayOut() {
        val text = SeedPolicy.stopped(SeedReport.withoutStore(names, StoreReady.NOT_REACHABLE), SeedPolicy.MAX_PASSES)
        assertTrue(text.contains("needs-store a — "))
        assertTrue(text.lines().last().contains("STOPPED after ${SeedPolicy.MAX_PASSES} passes"))
        assertTrue(text.lines().last().contains("a, b, c"))
        assertTrue(text.lines().last().contains("press Retry"))
    }

    @Test fun everyResumableKindIsBoundedNotJustTheStore() {
        for (kind in SeedOutcome.RESUMABLE) {
            val report = SeedReport(1, listOf(SeedOutcome("a", kind, "x")))
            assertEquals(kind, SeedPolicy.Next.RETRY, SeedPolicy.decide(report, 1))
            assertEquals(kind, SeedPolicy.Next.GIVE_UP, SeedPolicy.decide(report, SeedPolicy.MAX_PASSES))
        }
    }

    @Test fun aCompleteOrBlockedPassIsDoneAtAnyPass() {
        assertEquals(SeedPolicy.Next.DONE, SeedPolicy.decide(SeedReport(1, listOf(SeedOutcome("a", SeedOutcome.SEEDED, ""))), 99))
        assertEquals(SeedPolicy.Next.DONE, SeedPolicy.decide(SeedReport(1, listOf(SeedOutcome("a", SeedOutcome.NEEDS_CREDENTIAL, ""))), 1))
    }
}
