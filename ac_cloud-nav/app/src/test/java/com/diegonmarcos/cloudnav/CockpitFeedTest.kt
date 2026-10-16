package com.diegonmarcos.cloudnav

import com.diegonmarcos.cloudnav.cockpit.CockpitFeed
import com.diegonmarcos.cloudnav.cockpit.CockpitGauges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The cockpit's numbers: what the feed keeps (wrapping, clamping, reset) and the unit maths the dials use. */
class CockpitFeedTest {
    private fun mode(id: String, unit: String) = CockpitMode(id, id, "", 0, "dark", 17.0, 0.0, true, unit, "m", emptyList())

    @Test fun headingWrapsIntoZeroTo360() {
        val f = CockpitFeed()
        f.onHeading(370f); assertEquals(10f, f.heading, 1e-4f)
        f.onHeading(-10f); assertEquals(350f, f.heading, 1e-4f)
    }

    @Test fun pitchIsClampedAndSpeedNeverNegative() {
        val f = CockpitFeed()
        f.onAttitude(120f, 15f); assertEquals(90f, f.pitch, 0f); assertEquals(15f, f.roll, 0f)
        f.onSpeed(-3.0, "kn"); assertEquals(0.0, f.speed, 0.0); assertEquals("kn", f.speedUnit)
    }

    @Test fun metricsKeepTheirTwoLinesAndResetClearsEverything() {
        val f = CockpitFeed()
        f.onMetric("position", "40.0000°N", "3.0000°W"); f.onMetric("eta", "12:30")
        assertEquals("3.0000°W", f.metrics["position"]?.line2); assertNull(f.metrics["eta"]?.line2)
        f.onAltitude(1200.0); f.onVerticalMps(2.5); f.reset()
        assertEquals(0.0, f.altitude, 0.0); assertEquals(0.0, f.verticalMps, 0.0); assertEquals(0, f.metrics.size)
    }

    @Test fun dialScaleAndLabels() {
        assertEquals(500, CockpitGauges.speedMax(mode("airplane", "kn")))
        assertEquals(60, CockpitGauges.speedMax(mode("boat", "kn")))
        assertEquals(120, CockpitGauges.speedMax(mode("car", "mph")))
        assertEquals(180, CockpitGauges.speedMax(mode("bike", "kmh")))
        assertEquals("POSITION", CockpitGauges.metricLabel("position")); assertEquals("FUEL", CockpitGauges.metricLabel("fuel"))
        assertEquals("kn", CockpitGauges.speedUnitLabel("kn")); assertEquals("km/h", CockpitGauges.speedUnitLabel("kmh"))
        assertEquals(100.0 / 1.609344, CockpitGauges.kmhTo("mph", 100.0), 1e-9)
    }
}
