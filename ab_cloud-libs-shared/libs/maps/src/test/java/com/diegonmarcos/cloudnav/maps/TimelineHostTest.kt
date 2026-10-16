package com.diegonmarcos.cloudnav.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Daily -> Stops drill-down: a Daily row tap reaches whichever host the page sits in. */
class TimelineHostTest {
    private class Host : TimelineHost {
        val asked = mutableListOf<Long>()
        override fun openStopsForDay(dayMs: Long) { asked += dayMs }
    }

    @Test fun hostIsAskedForTheTappedDay() {
        val h = Host()
        assertTrue(TimelineHost.openStops(h, 1_700_000_000_000L))
        assertEquals(listOf(1_700_000_000_000L), h.asked)
    }
    @Test fun nonHostParentIsReportedNotSilentlyIgnored() {
        assertFalse(TimelineHost.openStops(Any(), 1L))
        assertFalse(TimelineHost.openStops(null, 1L))
    }
}
