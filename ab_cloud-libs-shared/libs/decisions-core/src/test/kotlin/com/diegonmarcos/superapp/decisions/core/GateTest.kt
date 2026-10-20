package com.diegonmarcos.superapp.decisions.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The evidence gate (#884) and the outcome method: a gated use is observe-only until the journal proves it. */
class GateTest {
    private fun rig(minLabelled: Int = 2, minPrecision: Double = 0.75, ttl: Long = 0) = Rig(Fixtures.block {
        it.put("journal_max", 100)
        it.getJSONObject("budget").put("per_app_daily_calls", 100).put("daily_usd_cap", 10.0)
        it.getJSONObject("uses").put("g", JSONObject()
            .put("enabled", true).put("class", "user_facing").put("threshold", 0.8).put("max_calls_per_hour", 100)
            .put("consent", "implicit").put("content", "none").put("ttl_s", ttl)
            .put("apps", org.json.JSONArray().put(Fixtures.APP))
            .put("gate", JSONObject().put("after", org.json.JSONArray().put("g"))
                .put("min_labelled", minLabelled).put("min_precision", minPrecision)))
    })

    private fun ask(r: Rig, n: Int) = r.decide("g", JSONObject().put("n", n), Fixtures.choiceQ("a", "b"))
    private fun report(r: Rig, call: String?, label: Any?, app: String = Fixtures.APP, use: String = "g"): JSONObject =
        r.engine.outcome(app, JSONObject().put("use", use).also { o -> call?.let { o.put("call", it) }; label?.let { o.put("label", it) } })

    @Test fun aGatedUseIsHeldWithItsCallIdButStillScoredAndJournaled() {
        val r = rig()
        val a = ask(r, 1)
        assertFalse(a.getBoolean("ok"))
        assertEquals("gate_closed", a.getString("reason"))
        assertEquals(12, a.getString("call").length)
        assertEquals(1, r.http.sent.size)
        val kinds = r.sink.lines.map { JSONObject(it).getString("outcome") }
        assertEquals(listOf("answered", "held"), kinds)
        val answered = JSONObject(r.sink.lines.first())
        assertEquals(a.getString("call"), answered.getString("call"))
        assertEquals("a", answered.getJSONObject("picks").getString("pick"))
    }

    @Test fun anUngatedUseIsUnchangedAndNowCarriesItsCall() {
        val r = Rig()
        val a = r.decide("ui")
        assertTrue(a.getBoolean("ok"))
        assertEquals(12, a.getString("call").length)
        assertTrue(r.sink.lines.none { JSONObject(it).getString("outcome") == "held" })
    }

    @Test fun enoughLabelledOutcomesAtThePrecisionOpenTheGate() {
        val r = rig()
        val calls = (1..4).map { ask(r, it).getString("call") }
        assertTrue(report(r, calls[0], JSONObject().put("pick", "a")).getBoolean("ok"))
        assertTrue(report(r, calls[1], JSONObject().put("pick", "a")).getBoolean("ok"))
        assertTrue("2 labelled, both right: open", ask(r, 5).getBoolean("ok"))
        assertTrue(report(r, calls[2], JSONObject().put("pick", "b")).getBoolean("ok"))
        // 3 labelled, 2 right = 0.67 < 0.75: closed again
        assertEquals("gate_closed", ask(r, 6).getString("reason"))
        assertTrue(report(r, calls[3], "a").getBoolean("ok")) // a bare label on a one-question use
        // 4 labelled, 3 right = 0.75: open
        assertTrue(ask(r, 7).getBoolean("ok"))
    }

    @Test fun tooFewLabelsKeepItClosedWhateverThePrecision() {
        val r = rig(minLabelled = 3)
        val calls = (1..2).map { ask(r, it).getString("call") }
        calls.forEach { report(r, it, "a") }
        assertEquals("gate_closed", ask(r, 3).getString("reason"))
    }

    @Test fun theLatestOutcomeOfACallWins() {
        val r = rig(minLabelled = 1, minPrecision = 1.0)
        val c = ask(r, 1).getString("call")
        report(r, c, "b")
        assertEquals("gate_closed", ask(r, 2).getString("reason"))
        report(r, c, "a")
        assertTrue(ask(r, 3).getBoolean("ok"))
    }

    @Test fun aCachedAnswerIsHeldToo() {
        val r = rig(ttl = 60)
        assertEquals("gate_closed", ask(r, 1).getString("reason"))
        val again = ask(r, 1)
        assertEquals("gate_closed", again.getString("reason"))
        assertEquals(1, r.http.sent.size)
        assertEquals("cached", JSONObject(r.sink.lines.first { it.contains("cached") }).getString("outcome"))
    }

    @Test fun anOpenGateServesTheCachedAnswerWithTheCall() {
        val r = rig(minLabelled = 1, minPrecision = 0.5, ttl = 60)
        val c = ask(r, 1).getString("call")
        report(r, c, "a")
        val hit = ask(r, 1)
        assertTrue(hit.getBoolean("ok"))
        assertTrue(hit.getBoolean("cached"))
        assertEquals(c, hit.getString("call"))
    }

    @Test fun anOutcomeIsRefusedForTheWrongAppUseOrShape() {
        val r = rig()
        val c = ask(r, 1).getString("call")
        assertEquals("unknown_use", report(r, c, "a", use = "zzz").getString("reason"))
        assertEquals("app_not_allowed", report(r, c, "a", app = "com.other").getString("reason"))
        assertEquals("bad_request", report(r, null, "a").getString("reason"))
        assertEquals("bad_request", report(r, "", "a").getString("reason"))
        assertEquals("bad_request", report(r, "x".repeat(65), "a").getString("reason"))
        assertEquals("bad_request", report(r, c, null).getString("reason"))
        assertEquals("bad_request", report(r, c, 7).getString("reason"))
        assertTrue(report(r, c, true).getBoolean("ok"))
        assertTrue(report(r, c, JSONObject().put("pick", "a")).getBoolean("ok"))
        assertEquals(1, r.sink.lines.count { JSONObject(it).getString("outcome") == "outcome" && it.contains("\"label\":{") })
    }

    @Test fun theJournalHoldsNoStateOrLabelContentBeyondTheOption() {
        val r = rig()
        r.decide("g", JSONObject().put("marker", "state-marker-xyz"), Fixtures.choiceQ("a", "b"))
        for (l in r.sink.lines) assertFalse(l, l.contains("state-marker"))
    }

    @Test fun precisionCountsOnlyConfidentAnswersWithAnOutcome() {
        val lines = listOf(
            """{"use":"u","outcome":"answered","call":"c1","picks":{"q":"a"}}""",
            """{"use":"u","outcome":"answered","call":"c2","picks":{}}""",
            """{"use":"u","outcome":"answered","call":"c3","picks":{"q":true}}""",
            """{"use":"v","outcome":"answered","call":"c4","picks":{"q":"a"}}""",
            """{"use":"u","outcome":"outcome","call":"c1","label":{"q":"a"}}""",
            """{"use":"u","outcome":"outcome","call":"c2","label":{"q":"a"}}""",
            """{"use":"u","outcome":"outcome","call":"c3","label":true}""",
            """{"use":"u","outcome":"outcome","call":"c9","label":"a"}""",
            "not json",
            """{"use":"v","outcome":"outcome","call":"c4","label":{"q":"b"}}""",
        )
        assertEquals(2 to 2, Evidence.precision(lines, "u"))
        assertEquals(1 to 0, Evidence.precision(lines, "v"))
        assertEquals(0 to 0, Evidence.precision(lines, "none"))
        assertFalse(Evidence.open(GateDecl(listOf("v"), 1, 0.5), lines))
        assertTrue(Evidence.open(GateDecl(listOf("u"), 2, 1.0), lines))
        assertFalse(Evidence.open(GateDecl(listOf("u", "v"), 1, 0.5), lines))
    }

    @Test fun aLabelOfAnotherShapeIsNotACountedAnswer() {
        val lines = listOf(
            """{"use":"u","outcome":"answered","call":"c1","picks":{"q":"a","r":"b"}}""",
            """{"use":"u","outcome":"outcome","call":"c1","label":"a"}""",
        )
        assertEquals("a bare label cannot say which of two questions it answers", 0 to 0, Evidence.precision(lines, "u"))
    }

    @Test fun aBadGateDeclarationIsRejectedNotServed() {
        fun gated(g: JSONObject?) = Policy.parse(Fixtures.block {
            it.getJSONObject("uses").put("g", JSONObject().put("enabled", true).put("class", "gating").put("threshold", 0.8)
                .put("max_calls_per_hour", 5).put("consent", "implicit").put("apps", org.json.JSONArray().put(Fixtures.APP))
                .also { u -> g?.let { x -> u.put("gate", x) } })
        })
        fun gate(after: List<String>, n: Int, p: Double) = JSONObject().put("after", org.json.JSONArray(after)).put("min_labelled", n).put("min_precision", p)
        assertNull(gated(null).rejected["g"])
        assertNull(gated(gate(listOf("g"), 1, 1.0)).rejected["g"])
        assertNotNull(gated(gate(emptyList(), 1, 0.9)).rejected["g"])
        assertNotNull(gated(gate(listOf("g"), 0, 0.9)).rejected["g"])
        assertNotNull(gated(gate(listOf("g"), 1, 0.0)).rejected["g"])
        assertNotNull(gated(gate(listOf("g"), 1, 1.5)).rejected["g"])
        assertEquals(GateDecl(listOf("g"), 2, 0.9), gated(gate(listOf("g"), 2, 0.9)).uses["g"]!!.gate)
    }
}
