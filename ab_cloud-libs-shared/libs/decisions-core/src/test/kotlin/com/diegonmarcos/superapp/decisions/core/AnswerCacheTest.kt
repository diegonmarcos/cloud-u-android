package com.diegonmarcos.superapp.decisions.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnswerCacheTest {
    private val clock = Clock(1_000_000L)
    private fun answer(n: Int) = JSONObject().put("n", n)

    @Test fun anEntryIsServedUntilItsTtlRunsOut() {
        val c = AnswerCache(null, 5, clock)
        c.put("k", answer(1), 10)
        assertEquals(1, c.get("k")!!.getInt("n"))
        clock.now += 9_999
        assertEquals(1, c.get("k")!!.getInt("n"))
        clock.now += 1
        assertNull(c.get("k"))
        assertEquals(0, c.size())
    }

    @Test fun anUnknownKeyIsAMiss() {
        assertNull(AnswerCache(null, 5, clock).get("nope"))
    }

    @Test fun thePastTheBoundTheSoonestToExpireGoesFirst() {
        val c = AnswerCache(null, 2, clock)
        c.put("a", answer(1), 100)
        c.put("b", answer(2), 10)
        c.put("c", answer(3), 50)
        assertNull(c.get("b"))
        assertEquals(1, c.get("a")!!.getInt("n"))
        assertEquals(3, c.get("c")!!.getInt("n"))
        assertEquals(2, c.size())
    }

    @Test fun puttingAKeyAgainReplacesItAndRestartsItsTtl() {
        val c = AnswerCache(null, 2, clock)
        c.put("a", answer(1), 10)
        clock.now += 5_000
        c.put("a", answer(2), 10)
        clock.now += 6_000
        assertEquals(2, c.get("a")!!.getInt("n"))
        assertEquals(1, c.size())
    }

    @Test fun expiredEntriesAreDroppedOnTheNextPut() {
        val c = AnswerCache(null, 5, clock)
        c.put("old", answer(1), 1)
        clock.now += 1_000
        c.put("new", answer(2), 10)
        assertEquals(1, c.size())
    }

    @Test fun theCacheSurvivesARestartWithoutItsExpiredEntries() {
        val s = MemoryStore()
        val a = AnswerCache(s, 5, clock)
        a.put("keep", answer(1), 100)
        a.put("gone", answer(2), 1)
        clock.now += 2_000
        val b = AnswerCache(s, 5, clock)
        assertEquals(1, b.get("keep")!!.getInt("n"))
        assertNull(b.get("gone"))
        assertEquals(1, b.size())
    }

    @Test fun theStoreHoldsOnlyHashesAndAnswers() {
        val s = MemoryStore()
        AnswerCache(s, 5, clock).put("abc123", answer(7), 10)
        val doc = s.read()!!.getJSONObject("entries").getJSONObject("abc123")
        assertEquals(setOf("exp", "results"), doc.keys().asSequence().toSet())
        assertEquals(clock.now + 10_000, doc.getLong("exp"))
    }

    @Test fun aGarbageStoreIsIgnored() {
        val s = MemoryStore(JSONObject().put("entries", JSONObject().put("x", "not an object").put("y", JSONObject().put("exp", 0))))
        assertEquals(0, AnswerCache(s, 5, clock).size())
    }
}
