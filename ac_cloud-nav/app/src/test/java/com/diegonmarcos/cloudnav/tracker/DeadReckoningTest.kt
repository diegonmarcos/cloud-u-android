package com.diegonmarcos.cloudnav.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadReckoningTest {
    private val origin = LatLon(47.0, 8.0)

    @Test fun displaceNorthMovesLatitudeOnly() {
        val p = DeadReckoning.displace(origin, 0.0, 1000.0)
        assertEquals(1000.0, DeadReckoning.haversineM(origin, p), 0.5)
        assertEquals(8.0, p.lon, 1e-9)
        assertTrue(p.lat > 47.0)
    }
    @Test fun displaceEastMovesLongitude() {
        val p = DeadReckoning.displace(origin, 90.0, 500.0)
        assertEquals(500.0, DeadReckoning.haversineM(origin, p), 0.5)
        assertTrue(p.lon > 8.0); assertEquals(47.0, p.lat, 1e-4)
    }
    @Test fun predictSumsLegsByStepsTimesStride() {
        // 1000 steps north + 1000 steps east at 0.7 m = 700 m each
        val e = DeadReckoning.predict(origin, 10.0, listOf(Leg(1000, 0.0), Leg(1000, 90.0)), 0.7)
        val d = DeadReckoning.haversineM(origin, LatLon(e.lat, e.lon))
        assertEquals(Math.hypot(700.0, 700.0), d, 3.0)
        assertTrue(e.predicted)
    }
    @Test fun noLegsStaysAtTheAnchor() {
        val e = DeadReckoning.predict(origin, 25.0, emptyList(), 0.7)
        assertEquals(47.0, e.lat, 0.0); assertEquals(25.0, e.radiusM, 0.0)
    }
    @Test fun radiusGrowsWithSteps() {
        val a = DeadReckoning.radiusM(20.0, 100, 0.7)
        val b = DeadReckoning.radiusM(20.0, 1000, 0.7)
        assertEquals(20 + 0.15 * 70, a, 1e-9)
        assertTrue(b > a)
    }
    @Test fun measuredStrideNeedsEnoughStepsAndAPlausibleValue() {
        val far = DeadReckoning.displace(origin, 45.0, 1400.0)
        assertEquals(0.7, DeadReckoning.measuredStepM(origin, far, 2000)!!, 0.01)
        assertNull(DeadReckoning.measuredStepM(origin, far, 100))        // too few steps
        assertNull(DeadReckoning.measuredStepM(origin, far, 10_000))     // 0.14 m stride: a loop, not a line
        assertNull(DeadReckoning.measuredStepM(origin, DeadReckoning.displace(origin, 0.0, 5000.0), 2000)) // 2.5 m: a ride
    }
    @Test fun calibrationMovesTowardsTheSampleAndStaysHuman() {
        assertEquals(0.72 * 0.7 + 0.8 * 0.3, DeadReckoning.calibrate(0.72, 0.8), 1e-9)
        assertEquals(0.72, DeadReckoning.calibrate(0.72, null), 0.0)
        assertTrue(DeadReckoning.calibrate(1.05, 1.1) <= 1.1)
        assertNotNull(DeadReckoning.calibrate(0.5, 0.4))
    }
}
