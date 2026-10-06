package com.diegonmarcos.superapp.decisions.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CircuitBreakerTest {
    private val clock = Clock(0L)
    private fun breaker() = CircuitBreaker(3, 60_000, clock)

    @Test fun staysClosedUntilTheThresholdFailures() {
        val b = breaker()
        repeat(2) { b.failure() }
        assertTrue(b.allow())
        assertFalse(b.isOpen())
        b.failure()
        assertTrue(b.isOpen())
        assertFalse(b.allow())
    }

    @Test fun aSuccessResetsTheCount() {
        val b = breaker()
        repeat(2) { b.failure() }
        b.success()
        repeat(2) { b.failure() }
        assertTrue(b.allow())
        assertFalse(b.isOpen())
    }

    @Test fun afterItsPeriodOneTrialPassesAndOnlyOne() {
        val b = breaker()
        repeat(3) { b.failure() }
        clock.now = 59_999
        assertFalse(b.allow())
        assertTrue(b.isOpen())
        clock.now = 60_000
        assertFalse(b.isOpen())
        assertTrue(b.allow())
        assertFalse(b.allow())
    }

    @Test fun aSuccessfulTrialClosesItAndAFailedOneOpensItForAFullPeriod() {
        val b = breaker()
        repeat(3) { b.failure() }
        clock.now = 60_000
        assertTrue(b.allow())
        b.success()
        assertTrue(b.allow())
        assertTrue(b.allow())

        val c = breaker()
        repeat(3) { c.failure() }
        clock.now = 120_000
        assertTrue(c.allow())
        c.failure()
        assertTrue(c.isOpen())
        clock.now = 120_000 + 59_999
        assertFalse(c.allow())
        clock.now = 120_000 + 60_000
        assertTrue(c.allow())
    }
}
