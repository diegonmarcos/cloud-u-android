package com.diegonmarcos.cloudnav.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The engine asks for high accuracy (GPS) only while moving; a still phone gets network / cell fixes or none. */
class BatteryPolicyTest {
    private val both = EngineConfig(enabled = true, useGps = true, useCell = true)
    private val gpsOnly = EngineConfig(enabled = true, useGps = true, useCell = false)

    @Test fun `still means fewer steps than the threshold`() {
        assertFalse(BatteryPolicy.isMoving(0)); assertFalse(BatteryPolicy.isMoving(BatteryPolicy.MOVING_STEPS - 1))
        assertTrue(BatteryPolicy.isMoving(BatteryPolicy.MOVING_STEPS))
    }

    @Test fun `no GPS while still, whatever the sources`() {
        assertEquals(Provider.NETWORK, BatteryPolicy.accurateProvider(both, moving = false))
        assertNull(BatteryPolicy.accurateProvider(gpsOnly, moving = false))
        assertFalse(BatteryPolicy.allowGps(false))
    }

    @Test fun `GPS is allowed while moving`() {
        assertEquals(Provider.GPS, BatteryPolicy.accurateProvider(both, moving = true))
        assertTrue(BatteryPolicy.allowGps(true))
    }

    @Test fun `no accurate request while still ever names GPS`() {
        for (g in listOf(true, false)) for (c in listOf(true, false)) {
            val cfg = EngineConfig(enabled = true, useGps = g, useCell = c)
            assertTrue(BatteryPolicy.accurateProvider(cfg, moving = false) != Provider.GPS)
        }
    }

    @Test fun `the stop locator never requests GPS when the policy forbids it`() {
        val requested = mutableListOf<String>()
        val probe = object : StopLocator.Probe {
            override fun enabled(provider: String) = true
            override fun lastKnown(provider: String): StopLocator.Fix? = null
            override fun request(provider: String, timeoutMs: Long): StopLocator.Fix? { requested += provider; return null }
        }
        StopLocator.resolve(1_000_000L, StopLocator.Env(coarse = true, fine = true), probe, allowGps = false)
        assertEquals(listOf(StopLocator.NETWORK), requested)
        requested.clear()
        StopLocator.resolve(1_000_000L, StopLocator.Env(coarse = true, fine = true), probe, allowGps = true)
        assertEquals(listOf(StopLocator.NETWORK, StopLocator.GPS), requested)
    }
}
