package com.diegonmarcos.superapp.decisions.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerTest {
    private val rules = BudgetRules(dailyUsdCap = 0.01, perAppDailyCalls = 3, estCallUsd = 0.004)
    private val clock = Clock(1_800_000_000_000L)   // 2027-01-15 08:00:00 UTC
    private val store = MemoryStore()
    private fun ledger(s: KvStore = store) = Ledger(s, rules, clock)

    @Test fun anAdmittedCallIsCountedAgainstItsAppAndUse() {
        val l = ledger()
        assertNull(l.admit("a", "u", 10))
        assertNull(l.admit("a", "u", 10))
        assertEquals(2, l.callsToday("a"))
        assertEquals(0, l.callsToday("b"))
    }

    @Test fun theFleetCapStopsEveryoneOnceSpendReachesIt() {
        val l = ledger()
        l.charge(0.005)
        assertNull(l.admit("a", "u", 10))
        l.charge(0.005)
        assertEquals(Refusal.OVER_BUDGET, l.admit("a", "u", 10))
        assertEquals(Refusal.OVER_BUDGET, l.admit("b", "other", 10))
        assertEquals(0.01, l.spentToday(), 1e-12)
    }

    @Test fun spendJustUnderTheCapStillAdmits() {
        val l = ledger()
        l.charge(0.0099)
        assertNull(l.admit("a", "u", 10))
    }

    @Test fun anAppCannotSpendMoreCallsThanItsDailyQuota() {
        val l = ledger()
        repeat(3) { assertNull(l.admit("a", "u", 10)) }
        assertEquals(Refusal.APP_QUOTA, l.admit("a", "u", 10))
        assertNull(l.admit("b", "u", 10))
    }

    @Test fun aUseIsLimitedPerHourOnASlidingWindow() {
        val l = ledger()
        assertNull(l.admit("a", "u", 2))
        clock.now += 1_000
        assertNull(l.admit("a", "u", 2))
        assertEquals(Refusal.RATE_LIMITED, l.admit("a", "u", 2))
        clock.now += 3_600_000 - 1_000 - 1
        assertEquals(Refusal.RATE_LIMITED, l.admit("b", "u", 2))     // the first call is 1 ms inside the hour
        clock.now += 1
        assertNull(l.admit("b", "u", 2))                              // exactly an hour old: out of the window
    }

    @Test fun otherUsesDoNotShareAnHourlyLimit() {
        val l = ledger()
        assertNull(l.admit("a", "u", 1))
        assertNull(l.admit("a", "v", 1))
        assertEquals(Refusal.RATE_LIMITED, l.admit("b", "u", 1))
    }

    @Test fun aRefusedCallIsNotCounted() {
        val l = ledger()
        l.charge(0.02)
        l.admit("a", "u", 10)
        assertEquals(0, l.callsToday("a"))
    }

    @Test fun aRateLimitedCallDoesNotUseUpTheAppsQuota() {
        val l = ledger()
        assertNull(l.admit("a", "u", 1))
        assertEquals(Refusal.RATE_LIMITED, l.admit("a", "u", 1))
        assertEquals(1, l.callsToday("a"))
    }

    @Test fun aChargeIsTheAnswersCostOrTheEstimateWhenItHasNone() {
        val l = ledger()
        l.charge(0.001)
        assertEquals(0.001, l.spentToday(), 1e-12)
        l.charge(null)
        assertEquals(0.005, l.spentToday(), 1e-12)
        l.charge(-1.0)
        assertEquals(0.009, l.spentToday(), 1e-12)
        l.charge(Double.NaN)
        assertEquals(0.013, l.spentToday(), 1e-12)
        l.charge(0.0)
        assertEquals(0.013, l.spentToday(), 1e-12)
    }

    @Test fun theUtcDayRollsSpendAndQuotaButNotTheHourlyWindow() {
        val l = ledger()
        repeat(3) { l.admit("a", "u", 100) }
        l.charge(0.02)
        assertEquals(Refusal.OVER_BUDGET, l.admit("a", "u", 100))
        // 2027-01-15 08:00 UTC + 15h59m59.999s is still the same day; +16h is the next
        clock.now += 16 * 3_600_000L - 1
        assertEquals(Refusal.OVER_BUDGET, l.admit("a", "u", 100))
        clock.now += 1
        assertEquals(0.0, l.spentToday(), 0.0)
        assertEquals(0, l.callsToday("a"))
        assertNull(l.admit("a", "u", 3))                              // the three calls are 16 h old: out of the hour
    }

    @Test fun theDayIsTheUtcDayBeforeTheEpochToo() {
        val c = Clock(-1L)
        val l = Ledger(MemoryStore(), rules, c)
        l.charge(0.005)
        assertEquals(0.005, l.spentToday(), 1e-12)
        c.now = 0L
        assertEquals(0.0, l.spentToday(), 0.0)
    }

    @Test fun aRestartKeepsTodaysSpendAndCounts() {
        val a = ledger()
        a.admit("a", "u", 2)
        a.charge(0.006)
        val b = ledger()
        assertEquals(0.006, b.spentToday(), 1e-12)
        assertEquals(1, b.callsToday("a"))
        assertNull(b.admit("a", "u", 2))
        assertEquals(Refusal.RATE_LIMITED, b.admit("a", "u", 2))
    }

    @Test fun aChargeAloneSurvivesARestart() {
        ledger().charge(0.007)
        assertEquals(0.007, ledger().spentToday(), 1e-12)
    }

    @Test fun aStoreThatHoldsGarbageStartsEmpty() {
        val s = MemoryStore(org.json.JSONObject().put("day", "x").put("spent", "nope"))
        val l = Ledger(s, rules, clock)
        assertEquals(0.0, l.spentToday(), 0.0)
        assertTrue(l.admit("a", "u", 1) == null)
    }
}
