package com.diegonmarcos.cloudnav.routes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Routes list's rules: moving a stop, what the router is handed, and the idle summary. */
class RouteStopsTest {
    private val me = Stop(RouteStops.YOUR_LOCATION, null)
    private val a = Stop("A", 1.0 to 2.0)
    private val b = Stop("B", 3.0 to 4.0)

    @Test fun moveSwapsNeighboursAndStopsAtTheEnds() {
        assertEquals(listOf(me, b, a), RouteStops.moved(listOf(me, a, b), 1, 1))
        assertEquals(listOf(a, me, b), RouteStops.moved(listOf(me, a, b), 1, -1))
        assertEquals(listOf(me, a, b), RouteStops.moved(listOf(me, a, b), 0, -1))
        assertEquals(listOf(me, a, b), RouteStops.moved(listOf(me, a, b), 2, 1))
        assertEquals(listOf(me, a, b), RouteStops.moved(listOf(me, a, b), 7, 1))
    }

    @Test fun yourLocationIsOnlyUsedOnceItIsKnown() {
        assertEquals(1, RouteStops.coords(listOf(me, a), null).size)
        val c = RouteStops.coords(listOf(me, a, b), 9.0 to 8.0)
        assertEquals(3, c.size)
        assertArrayEquals(doubleArrayOf(9.0, 8.0), c[0], 0.0)
        assertArrayEquals(doubleArrayOf(3.0, 4.0), c[2], 0.0)
        // A stop the user renamed to something else with no place is not "Your location".
        assertEquals(0, RouteStops.coords(listOf(Stop("Somewhere", null)), 9.0 to 8.0).size)
    }

    @Test fun idleSummaryAsksForTheMissingHalf() {
        assertEquals("Add a destination to see the route", RouteStops.idleSummary(listOf(me)))
        assertEquals("Tap the locate button to set your start", RouteStops.idleSummary(listOf(me, a)))
        assertEquals("Dropped pin (1.2346, -3.0000)", RouteStops.droppedPinLabel(1.23456, -3.0))
    }
}
