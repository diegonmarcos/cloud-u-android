package com.diegonmarcos.superapp.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The GPS icon's dots and the popup's GPS lines. */
class GpsFixTest {

    private fun gps(acc: Float?, age: Long = 1_000, sats: Int? = null) = GpsFix.Fix("gps", acc, age, sats)

    @Test fun `GNSS accuracy maps to 4 3 2 1 dots`() {
        assertEquals(4, GpsFix.dots(true, gps(3f)))
        assertEquals(4, GpsFix.dots(true, gps(5f)))
        assertEquals(3, GpsFix.dots(true, gps(5.1f)))
        assertEquals(3, GpsFix.dots(true, gps(15f)))
        assertEquals(2, GpsFix.dots(true, gps(50f)))
        assertEquals(1, GpsFix.dots(true, gps(51f)))
    }

    @Test fun `no fix or location off is 0 dots`() {
        assertEquals(0, GpsFix.dots(true, null))
        assertEquals(0, GpsFix.dots(false, gps(3f)))
    }

    @Test fun `a network-only fix is at most 2 dots`() {
        assertEquals(2, GpsFix.dots(true, GpsFix.Fix("network", 20f, 1_000)))
        assertEquals(1, GpsFix.dots(true, GpsFix.Fix("network", 800f, 1_000)))
        assertEquals(2, GpsFix.dots(true, GpsFix.Fix("fused", 30f, 1_000)))
        assertEquals(4, GpsFix.dots(true, GpsFix.Fix("fused", 4f, 1_000)))   // that sharp only comes from satellites
    }

    @Test fun `few satellites cap the dots, an old fix fades`() {
        assertEquals(2, GpsFix.dots(true, gps(4f, sats = 3)))
        assertEquals(4, GpsFix.dots(true, gps(4f, sats = 9)))
        assertEquals(1, GpsFix.dots(true, gps(4f, age = 5 * 60_000L)))
        assertEquals(0, GpsFix.dots(true, gps(4f, age = 11 * 60_000L)))
    }

    @Test fun `constellation names`() {
        assertEquals(listOf("GPS", "GLONASS", "Galileo", "BeiDou", "QZSS", "NavIC", "SBAS"),
            GpsFix.CONSTELLATIONS.map(GpsFix::constellationName))
        assertEquals("Other", GpsFix.constellationName(0))
    }

    @Test fun `carrier frequency names the band, L5 makes it dual frequency`() {
        assertEquals("L1", GpsFix.band(GpsFix.GPS, 1575.42e6)); assertEquals("L5", GpsFix.band(GpsFix.GPS, 1176.45e6))
        assertEquals("E1", GpsFix.band(GpsFix.GALILEO, 1575.42e6)); assertEquals("E5a", GpsFix.band(GpsFix.GALILEO, 1176.45e6))
        assertEquals("B1I", GpsFix.band(GpsFix.BEIDOU, 1561.098e6)); assertEquals("B2a", GpsFix.band(GpsFix.BEIDOU, 1176.45e6))
        assertEquals("G1", GpsFix.band(GpsFix.GLONASS, 1602.5625e6))
        assertEquals(null, GpsFix.band(GpsFix.GPS, null))
        val sats = listOf(GpsFix.Sat(GpsFix.GPS, true, 1575.42e6), GpsFix.Sat(GpsFix.GPS, true, 1176.45e6),
            GpsFix.Sat(GpsFix.GALILEO, false, 1575.42e6), GpsFix.Sat(GpsFix.GLONASS, true))
        assertTrue(GpsFix.dualFrequencySeen(sats))
        assertFalse(GpsFix.dualFrequencySeen(sats.drop(1).drop(1)))
        assertEquals(listOf("GPS 2 in view / 2 used · L1+L5", "GLONASS 1 in view / 1 used", "Galileo 1 in view / 0 used · E1"),
            GpsFix.byConstellation(sats).map(GpsFix::constellationLine))
    }

    @Test fun `network-only says so, and the fix line carries source accuracy and age`() {
        val net = GpsFix.Fix("network", 35f, 12_000)
        assertEquals(listOf("Using cell towers / Wi-Fi (no satellites)"), GpsFix.satelliteLines(emptyList(), net, true))
        assertEquals(listOf("Satellites: searching…"), GpsFix.satelliteLines(emptyList(), null, true))
        assertEquals("Fix: network (cell towers / Wi-Fi) · ±35 m · 12 s old", GpsFix.fixLine(net))
        assertEquals("Fix: GNSS (satellites, 8 used) · ±3.5 m · 2 s old", GpsFix.fixLine(gps(3.5f, 2_000, 8)))
        assertEquals("Fix: none yet", GpsFix.fixLine(null))
    }
}
