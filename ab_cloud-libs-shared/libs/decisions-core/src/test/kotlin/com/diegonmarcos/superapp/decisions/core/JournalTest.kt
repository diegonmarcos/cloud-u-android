package com.diegonmarcos.superapp.decisions.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalTest {
    private val clock = Clock(5L)

    @Test fun aLineCarriesWhoWhatAndWhenAndTheCallersExtras() {
        val s = MemorySink()
        Journal(s, 10, clock).record("app", "use", "answered", JSONObject().put("cost", 0.1))
        val o = JSONObject(s.lines.single())
        assertEquals("app", o.getString("app"))
        assertEquals("use", o.getString("use"))
        assertEquals("answered", o.getString("outcome"))
        assertEquals(5L, o.getLong("ts"))
        assertEquals(0.1, o.getDouble("cost"), 0.0)
    }

    @Test fun theExtraObjectIsNotMutated() {
        val extra = JSONObject().put("cost", 1)
        Journal(MemorySink(), 10, clock).record("a", "u", "x", extra)
        assertEquals(setOf("cost"), extra.keys().asSequence().toSet())
    }

    @Test fun itIsCutBackToTheNewestLinesEveryMax() {
        val s = MemorySink()
        val j = Journal(s, 3, clock)
        repeat(2) { j.record("a", "u$it", "x") }
        assertEquals(2, s.lines.size)
        j.record("a", "u2", "x")
        assertEquals(3, s.lines.size)
        repeat(3) { j.record("a", "v$it", "x") }
        assertEquals(3, s.lines.size)
        assertEquals("v2", JSONObject(s.lines.last()).getString("use"))
        assertEquals("v0", JSONObject(s.lines.first()).getString("use"))
    }

    @Test fun theSummaryIsNamesAndMarksOnly() {
        val s = MemorySink()
        val j = Journal(s, 100, clock)
        j.record("calc", "probe", "answered", JSONObject().put("cost", 0.5).put("scores", JSONObject().put("q", 0.9)))
        j.record("calc", "probe", "cached")
        j.record("mail", "triage", "refused", JSONObject().put("reason", "no_consent"))
        s.append("not json")
        assertEquals(listOf("calc probe ✓", "calc probe ✓", "mail triage ✗ no_consent"), j.summary(10))
        assertEquals(listOf("mail triage ✗ no_consent"), j.summary(2).takeLast(1))
        assertFalse(j.summary(10).joinToString().contains("0.5"))
        assertTrue(j.summary(1).size <= 1)
    }
}
