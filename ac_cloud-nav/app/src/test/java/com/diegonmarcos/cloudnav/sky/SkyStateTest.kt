package com.diegonmarcos.cloudnav.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The sky screen's pointing rules: drag, pinch, and which points the projection keeps. */
class SkyStateTest {
    @Test fun dragMovesTheViewByTheFieldOfViewShare() {
        val s = SkyState()          // 70 deg over 700 px = 0.1 deg per px
        s.pan(100f, 0f, 700f, 1400f)
        assertEquals(10.0, s.panAzimuthDeg, 1e-6)
        s.pan(0f, -140f, 700f, 1400f)   // scrolling up looks up
        assertEquals(7.0, s.panPitchDeg, 1e-6)
    }

    @Test fun pitchStopsShortOfTheZenith() {
        val s = SkyState()
        s.pan(0f, -1_000_000f, 700f, 1400f)
        assertEquals(89.0, s.panPitchDeg, 0.0)
        s.pan(0f, 5_000_000f, 700f, 1400f)
        assertEquals(-89.0, s.panPitchDeg, 0.0)
    }

    @Test fun pinchNarrowsAndWidensWithinLimits() {
        val s = SkyState()
        s.zoom(2f); assertEquals(35.0, s.fovDeg, 1e-6)
        s.zoom(100f); assertEquals(SkyState.MIN_FOV, s.fovDeg, 0.0)
        s.zoom(0.001f); assertEquals(SkyState.MAX_FOV, s.fovDeg, 0.0)
    }

    @Test fun projectionCentresWhatWeLookAtAndDropsWhatIsOutside() {
        val c = SkyProjection.project(30.0, 100.0, 100.0, 30.0, 70.0, 800f, 1600f)
        assertNotNull(c); assertEquals(400f, c!![0], 1e-3f); assertEquals(800f, c[1], 1e-3f)
        assertNull(SkyProjection.project(30.0, 150.0, 100.0, 30.0, 70.0, 800f, 1600f))   // 50 deg off: beyond +-35
        // The azimuth wraps: 359 deg is 2 deg left of 1 deg.
        val w = SkyProjection.project(0.0, 359.0, 1.0, 0.0, 70.0, 700f, 700f)
        assertNotNull(w); assertEquals(350f - 20f, w!![0], 1e-3f)
    }
}
