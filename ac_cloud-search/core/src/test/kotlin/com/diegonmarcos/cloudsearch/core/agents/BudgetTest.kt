package com.diegonmarcos.cloudsearch.core.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class BudgetTest {
    private val price = Pricing(0.000001, 0.000002)

    @Test fun estimateIsTheWorstCase() {
        // 300 chars = 100 prompt tokens at 1e-6, plus 50 completion tokens at 2e-6
        assertEquals(100 * 0.000001 + 50 * 0.000002, BudgetLedger.estimate(300, 50, price), 1e-12)
        // a partial token counts as a whole one
        assertEquals(2 * 0.000001, BudgetLedger.estimate(4, 0, price), 1e-12)
        assertEquals(0.0, BudgetLedger.estimate(0, 0, price), 0.0)
    }

    @Test fun actualCostPrefersTheProvidersFigure() {
        assertEquals(0.0042, BudgetLedger.actual(10, 10, 0.0042, price), 0.0)
        assertEquals(10 * 0.000001 + 20 * 0.000002, BudgetLedger.actual(10, 20, null, price), 1e-12)
        assertEquals(10 * 0.000001 + 20 * 0.000002, BudgetLedger.actual(10, 20, -1.0, price), 1e-12)
        assertEquals(0.0, BudgetLedger.actual(10, 20, 0.0, price), 0.0)
    }

    @Test fun aCallThatFitsIsAllowed() {
        val l = BudgetLedger(Caps(0.05, 0.5), 0.0)
        assertEquals(Spend.Allowed, l.check(0.05))
    }

    @Test fun theRunCapIsEnforcedBeforeTheCall() {
        val l = BudgetLedger(Caps(0.05, 0.5), 0.0)
        val d = l.check(0.0501) as Spend.Denied
        assertEquals("run", d.scope)
        assertTrue(d.why.contains("run"))
        l.record(0.03)
        assertEquals(Spend.Allowed, l.check(0.02))
        assertEquals("run", (l.check(0.0201) as Spend.Denied).scope)
    }

    @Test fun theDayCapCountsEarlierRunsToday() {
        val l = BudgetLedger(Caps(0.05, 0.5), 0.47)
        assertEquals(Spend.Allowed, l.check(0.03))
        val d = l.check(0.0301) as Spend.Denied
        assertEquals("day", d.scope)
        l.record(0.02)
        assertEquals(0.49, l.spentDayUsd, 1e-12)
        assertEquals("day", (l.check(0.0201) as Spend.Denied).scope)
    }

    @Test fun aZeroCapMeansNoCallAtAll() {
        assertEquals("run", (BudgetLedger(Caps(0.0, 0.5), 0.0).check(0.000001) as Spend.Denied).scope)
        assertEquals("day", (BudgetLedger(Caps(0.05, 0.0), 0.0).check(0.000001) as Spend.Denied).scope)
        assertEquals(Spend.Allowed, BudgetLedger(Caps(0.0, 0.0), 0.0).check(0.0))
    }

    @Test fun nonsenseEstimatesAreDenied() {
        val l = BudgetLedger(Caps(1.0, 1.0), 0.0)
        assertTrue(l.check(-0.1) is Spend.Denied)
        assertTrue(l.check(Double.NaN) is Spend.Denied)
    }

    @Test fun recordingAddsToTheRunAndTellsTheStore() {
        val seen = ArrayList<Double>()
        val l = BudgetLedger(Caps(1.0, 1.0), 0.1) { seen += it }
        l.record(0.2); l.record(0.3)
        assertEquals(0.5, l.spentRunUsd, 1e-12)
        assertEquals(listOf(0.2, 0.3), seen)
        l.record(-5.0); l.record(Double.NaN)
        assertEquals(0.5, l.spentRunUsd, 1e-12)
    }

    @Test fun dayKeyFollowsTheZone() {
        val t = java.time.ZonedDateTime.of(2026, 10, 8, 23, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals("2026-10-08", BudgetLedger.dayKey(t, ZoneId.of("UTC")))
        assertEquals("2026-10-09", BudgetLedger.dayKey(t, ZoneId.of("Europe/Berlin")))
    }

    @Test fun usdIsFourDecimals() {
        assertEquals("$0.0500", BudgetLedger.usd(0.05))
        assertEquals("$1.2346", BudgetLedger.usd(1.23456))
    }

    @Test fun pricingFromTheCatalogue() {
        val json = """{"data":[
            {"id":"a/cheap","pricing":{"prompt":"0.00000015","completion":"0.0000006"}},
            {"id":"openrouter/auto","pricing":{"prompt":"-1","completion":"-1"}},
            {"id":"a/free","pricing":{"prompt":"0","completion":"0"}},
            {"id":"a/odd","pricing":{"prompt":"x","completion":"1"}},
            {"id":"a/none"}]}"""
        assertEquals(Pricing(0.00000015, 0.0000006), Pricing.fromCatalog(json, "a/cheap"))
        assertEquals(Pricing(0.0, 0.0), Pricing.fromCatalog(json, "a/free"))
        assertNull(Pricing.fromCatalog(json, "openrouter/auto"))
        assertNull(Pricing.fromCatalog(json, "a/odd"))
        assertNull(Pricing.fromCatalog(json, "a/none"))
        assertNull(Pricing.fromCatalog(json, "a/absent"))
        assertNull(Pricing.fromCatalog("not json", "a/cheap"))
    }
}
