package com.diegonmarcos.cloudsearch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorsTest {
    private val cfg = Fixtures.cfg

    @Test fun annuityIsTheTextbookPayment() {
        assertEquals(1436.94, Calculators.annuity(320_000.0, 0.035 / 12, 360), 0.005)
        assertEquals(833.33, Calculators.annuity(100_000.0, 0.0, 120), 0.005)
        assertEquals(0.0, Calculators.annuity(0.0, 0.01, 120), 0.0)
        assertEquals(0.0, Calculators.annuity(1000.0, 0.01, 0), 0.0)
    }

    /** The declared defaults (Berlin, 400k, 20 % down, 3.5 %, 30 y), golden from an independent Python run of the same model. */
    @Test fun rentVsBuyDefaultsGolden() {
        val v = Calculators.run(cfg, "rent_vs_buy", emptyMap()).values
        assertEquals(1436.943, v.getValue("monthly_payment"), 0.001)
        assertEquals(730225.43, v.getValue("rent_total"), 0.01)
        assertEquals(807333.97, v.getValue("buy_total"), 0.01)
        assertEquals(197299.48, v.getValue("interest_paid"), 0.01)
        assertEquals(724544.63, v.getValue("home_value"), 0.01)
        assertEquals(0.0, v.getValue("remaining_debt"), 0.01)
        assertEquals(813289.87, v.getValue("buyer_net_worth"), 0.01)
        assertEquals(464988.13, v.getValue("renter_net_worth"), 0.01)
        assertEquals(348301.74, v.getValue("advantage_buy"), 0.01)
        assertEquals(22.222, v.getValue("price_to_rent"), 0.001)
    }

    /** A cash purchase with every rate at zero is checkable by hand: the buyer banks the rent it no longer pays. */
    @Test fun cashPurchaseAtZeroRatesByHand() {
        val v = Calculators.rentVsBuy(1200.0, 0.0, 300_000.0, 300_000.0, 0.0, 30, 10, 10.0, 0.0, 0.0, 0.0)
        assertEquals(0.0, v.getValue("monthly_payment"), 0.0)
        assertEquals(144_000.0, v.getValue("rent_total"), 0.001)
        assertEquals(330_000.0, v.getValue("buy_total"), 0.001)
        assertEquals(0.0, v.getValue("interest_paid"), 0.0)
        assertEquals(444_000.0, v.getValue("buyer_net_worth"), 0.001)
        assertEquals(330_000.0, v.getValue("renter_net_worth"), 0.001)
        assertEquals(114_000.0, v.getValue("advantage_buy"), 0.001)
    }

    @Test fun rentRisesOncePerYear() {
        val v = Calculators.rentVsBuy(1000.0, 10.0, 100_000.0, 100_000.0, 0.0, 30, 2, 0.0, 0.0, 0.0, 0.0)
        assertEquals(12 * 1000.0 + 12 * 1100.0, v.getValue("rent_total"), 0.001)
    }

    @Test fun maxRentRatio() {
        val v = Calculators.run(cfg, "max_rent", mapOf("net_income" to 4000.0, "ratio_pct" to 30.0, "rent" to 1500.0)).values
        assertEquals(1200.0, v.getValue("max_rent"), 0.001)
        assertEquals(37.5, v.getValue("rent_share_pct"), 0.001)
        assertEquals(-300.0, v.getValue("headroom"), 0.001)
        assertEquals(0.0, Calculators.maxRent(0.0, 30.0, 100.0).getValue("rent_share_pct"), 0.0)
    }

    @Test fun everyDeclaredOutputIsComputed() {
        for ((id, c) in cfg.calculators) {
            val r = Calculators.run(cfg, id, emptyMap())
            for (o in c.outputs) assertTrue("$id declares output ${o.id} that run() does not produce", o.id in r.values)
            for (w in r.warnings) assertTrue("$id raised warning $w with no declared text", w in c.warnings)
        }
    }

    @Test fun givenValuesOverrideDefaults() {
        val low = Calculators.run(cfg, "payslip", mapOf("gross" to 1500.0)).warnings
        assertEquals(listOf("midijob"), low)
        assertTrue("payslip warnings must be declared", "midijob" in cfg.calculators.getValue("payslip").warnings)
    }

    @Test(expected = IllegalArgumentException::class) fun unknownCalculatorFails() {
        Calculators.run(cfg, "abacus", emptyMap())
    }
}
