package com.diegonmarcos.superapp.decisions.link

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reply the engine's own suite pins (decisions-core EngineTest), read by the client. */
class VerdictTest {

    private val reply = """
    {"ok":true,"use":"writer_route","class":"user_facing","cached":false,"threshold":0.8,"results":{
      "ok":{"type":"noul","p":0.95,"value":true,"confident":true},
      "unsure":{"type":"noul","p":0.6,"value":true,"confident":false},
      "no":{"type":"noul","p":0.1,"value":false,"confident":true},
      "pick":{"type":"choice","pick":"model","p":0.9,"confident":true,"probabilities":{"model":0.9,"local":0.1}},
      "weak":{"type":"choice","pick":"local","p":0.5,"confident":false,"probabilities":{"model":0.5,"local":0.5}},
      "level":{"type":"score","level":"high","p":0.7,"confident":false,"probabilities":{"low":0.1,"mid":0.2,"high":0.7}}}}
    """.trimIndent()

    private fun verdict(json: String = reply) = (Outcome.parse(JSONObject(json)) as Outcome.Answered).verdict

    @Test fun anAnswerIsReadWithItsClassAndEveryTypedResult() {
        val v = verdict()
        assertEquals("writer_route", v.use)
        assertEquals("user_facing", v.cls)
        assertFalse(v.cached)
        assertEquals(0.8, v.threshold, 0.0)
        assertFalse(v.adviceOnly)
        assertTrue(v.mayPreselect)
        assertEquals(6, v.answers.size)
        assertEquals(0.95, v.answers.getValue("ok").p, 0.0)
        assertEquals("high", v.answers.getValue("level").level)
    }

    @Test fun onlyAConfidentAnswerIsUsable() {
        val v = verdict()
        assertEquals(true, v.yes("ok"))
        assertEquals(false, v.yes("no"))
        assertNull(v.yes("unsure"))
        assertNull(v.yes("pick"))
        assertNull(v.yes("missing"))
        assertEquals("model", v.pick("pick", setOf("model", "local")))
        assertNull(v.pick("pick", setOf("local")))            // outside what the caller can do
        assertNull(v.pick("weak", setOf("model", "local")))
        assertNull(v.pick("ok", setOf("model")))
    }

    @Test fun rankedIsMostProbableFirst() {
        assertEquals(listOf("high" to 0.7, "mid" to 0.2, "low" to 0.1), verdict().ranked("level"))
        assertTrue(verdict().ranked("ok").isEmpty())
        assertTrue(verdict().ranked("missing").isEmpty())
    }

    @Test fun gatingIsAdviceOnlyAndNeverPreselects() {
        val v = verdict(reply.replace("\"user_facing\"", "\"gating\"").replace("\"cached\":false", "\"cached\":false,\"advice_only\":true"))
        assertTrue(v.adviceOnly)
        assertFalse(v.mayPreselect)
        assertFalse(verdict(reply.replace("\"user_facing\"", "\"background\"")).mayPreselect)
        assertFalse(verdict(reply.replace("\"user_facing\"", "\"user_facing\",\"advice_only\":true")).mayPreselect)
    }

    @Test fun aCachedFlagIsKept() {
        assertTrue(verdict(reply.replace("\"cached\":false", "\"cached\":true")).cached)
    }

    @Test fun aRefusalKeepsItsReasonAndNothingElseIsAnAnswer() {
        assertEquals("no_consent", (Outcome.parse(JSONObject("""{"ok":false,"use":"u","reason":"no_consent"}""")) as Outcome.NoOpinion).reason)
        assertEquals("malformed", (Outcome.parse(JSONObject("""{"ok":false}""")) as Outcome.NoOpinion).reason)
        assertNull(Outcome.parse(JSONObject("""{"ok":false,"reason":"x"}""")).verdictOrNull)
        for (bad in listOf(
            """{"ok":true,"use":"u","class":"user_facing"}""",
            """{"ok":true,"use":"u","class":"user_facing","results":{}}""",
            """{"ok":true,"class":"user_facing","results":{"a":{"type":"noul","p":0.9}}}""",
            """{"ok":true,"use":"u","results":{"a":{"type":"noul","p":0.9}}}""",
            """{"ok":true,"use":"u","class":"user_facing","results":{"a":"x"}}""",
            """{"ok":true,"use":"u","class":"user_facing","results":{"a":{"type":"noul"}}}""",
            """{"ok":true,"use":"u","class":"user_facing","results":{"a":{"type":"noul","p":1.5}}}""",
            """{"ok":true,"use":"u","class":"user_facing","results":{"a":{"type":"noul","p":-0.5}}}""",
            """{"ok":true,"use":"u","class":"user_facing","results":{"a":{"type":"other","p":0.5}}}""",
            """{"ok":true,"use":"u","class":"user_facing","results":{"a":{"type":"choice","p":0.5}}}""",
            """{"ok":true,"use":"u","class":"user_facing","results":{"a":{"type":"score","p":0.5}}}""",
            """{"ok":true,"use":"u","class":"user_facing","results":{"a":{"type":"choice","pick":"x","p":0.5,"probabilities":{"x":"no"}}}}""",
        )) {
            val o = Outcome.parse(JSONObject(bad))
            assertTrue(bad, o is Outcome.NoOpinion && o.reason == "malformed")
        }
    }

    @Test fun theBoundariesOfAProbabilityAreInclusive() {
        for (p in listOf("0.0", "1.0")) {
            assertTrue(Outcome.parse(JSONObject("""{"ok":true,"use":"u","class":"c","results":{"a":{"type":"noul","p":$p,"value":true,"confident":true}}}""")) is Outcome.Answered)
        }
    }
}
