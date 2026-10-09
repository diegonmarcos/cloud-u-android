package com.diegonmarcos.superapp.network

import org.junit.Assert.assertEquals
import org.junit.Test

/** The strip's 4-dot levels: one mapping per radio, the edges pinned. */
class SignalLevelsTest {

    @Test fun `cellular level passes through 0 to 4 and unknown is none`() {
        for (l in 0..4) assertEquals(l, SignalLevels.cell(l))
        assertEquals(4, SignalLevels.cell(7))
        assertEquals(SignalLevels.NONE, SignalLevels.cell(null))
        assertEquals(SignalLevels.NONE, SignalLevels.cell(-1))
    }

    @Test fun `wifi follows the platform 5-level formula`() {
        assertEquals(0, SignalLevels.wifi(-100))
        assertEquals(0, SignalLevels.wifi(-110))
        assertEquals(0, SignalLevels.wifi(-89))   // (11 * 4) / 45 = 0
        assertEquals(1, SignalLevels.wifi(-88))   // (12 * 4) / 45 = 1
        assertEquals(2, SignalLevels.wifi(-77))
        assertEquals(3, SignalLevels.wifi(-66))
        assertEquals(3, SignalLevels.wifi(-56))
        assertEquals(4, SignalLevels.wifi(-55))
        assertEquals(4, SignalLevels.wifi(-30))
    }

    @Test fun `wifi with no rssi shows no dots`() {
        assertEquals(SignalLevels.NONE, SignalLevels.wifi(null))
        assertEquals(SignalLevels.NONE, SignalLevels.wifi(-127))
        assertEquals(SignalLevels.NONE, SignalLevels.wifi(0))
    }

    @Test fun `wg ages from 4 to 1 with the handshake and is 0 without one`() {
        assertEquals(4, SignalLevels.wg(true, 5_000))
        assertEquals(4, SignalLevels.wg(true, 119_999))
        assertEquals(3, SignalLevels.wg(true, 120_000))
        assertEquals(2, SignalLevels.wg(true, 180_000))
        assertEquals(1, SignalLevels.wg(true, 300_000))
        assertEquals(1, SignalLevels.wg(true, 3_600_000))
        assertEquals(0, SignalLevels.wg(true, null))
        assertEquals(SignalLevels.NONE, SignalLevels.wg(false, 5_000))
    }

    @Test fun `a known latency caps the wg level`() {
        assertEquals(4, SignalLevels.wg(true, 5_000, latencyMs = 40))
        assertEquals(3, SignalLevels.wg(true, 5_000, latencyMs = 150))
        assertEquals(2, SignalLevels.wg(true, 5_000, latencyMs = 400))
        assertEquals(1, SignalLevels.wg(true, 400_000, latencyMs = 400))
    }

    @Test fun `the popup prints the same number`() {
        assertEquals("3/4", SignalLevels.label(3))
        assertEquals("0/4", SignalLevels.label(0))
        assertEquals("—", SignalLevels.label(SignalLevels.NONE))
    }
}
