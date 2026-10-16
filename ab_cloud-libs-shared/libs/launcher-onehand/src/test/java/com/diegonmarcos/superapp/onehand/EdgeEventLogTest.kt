package com.diegonmarcos.superapp.onehand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeEventLogTest {
    @Test fun keepsOnlyTheLastTwenty() {
        EdgeEventLog.clear()
        for (i in 1..30) EdgeEventLog.record("down", "r", "n$i", now = i.toLong())
        val s = EdgeEventLog.snapshot()
        assertEquals(20, s.size)
        assertEquals("n11", s.first().reason); assertEquals("n30", s.last().reason)
    }

    @Test fun recordIfChangedDropsRepeats() {
        EdgeEventLog.clear()
        repeat(5) { EdgeEventLog.recordIfChanged("service", "no permission") }
        EdgeEventLog.recordIfChanged("service", "restart")
        assertEquals(2, EdgeEventLog.snapshot().size)
    }

    @Test fun userStripWidthCanExceedTheDefaultCapButStaysThin() {
        val d = 3f
        val s = EdgeHandleWindow.size(OneHandConfig.Edge.RIGHT, 400, (36 * d).toInt(), d, EdgeHandleWindow.USER_MAX_THICKNESS_DP)
        assertEquals((36 * d).toInt(), s.widthPx)
        val capped = EdgeHandleWindow.size(OneHandConfig.Edge.RIGHT, 400, (90 * d).toInt(), d, EdgeHandleWindow.USER_MAX_THICKNESS_DP)
        assertTrue(capped.widthPx <= (EdgeHandleWindow.USER_MAX_THICKNESS_DP * d).toInt())
    }
}
