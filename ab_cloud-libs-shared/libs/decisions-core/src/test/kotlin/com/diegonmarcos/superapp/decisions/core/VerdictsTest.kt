package com.diegonmarcos.superapp.decisions.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerdictsTest {
    private val policy = Policy.parse(Fixtures.block())
    private val ui = policy.uses.getValue("ui")          // allowed a, b
    private val bg = policy.uses.getValue("bg")          // no allowed list

    private fun q(type: String, instructions: String = "i") = JSONObject().put("type", type).put("instructions", instructions)
    private fun choice(vararg o: String) = Fixtures.choiceQ(*o).getJSONObject("pick")

    @Test fun wellFormedQuestionsPass() {
        assertNull(Verdicts.check(Fixtures.noulQ(), ui))
        assertNull(Verdicts.check(Fixtures.choiceQ("a", "b"), ui))
        assertNull(Verdicts.check(JSONObject().put("s", q("score")), ui))
        assertNull(Verdicts.check(Fixtures.choiceQ("x", "y", "z"), bg))
    }

    @Test fun theCountOfQuestionsIsBoundedBothWays() {
        assertEquals("bad_questions", Verdicts.check(null, ui))
        assertEquals("bad_questions", Verdicts.check(JSONObject(), ui))
        val eight = JSONObject().also { o -> repeat(8) { o.put("q$it", q("noul")) } }
        assertNull(Verdicts.check(eight, ui))
        assertEquals("bad_questions", Verdicts.check(eight.put("q9", q("noul")), ui))
    }

    @Test fun aQuestionNeedsATypeFromTheApiAndInstructions() {
        assertEquals("bad_questions", Verdicts.check(JSONObject().put("x", "not an object"), ui))
        assertEquals("bad_questions", Verdicts.check(JSONObject().put("x", q("nope")), ui))
        assertEquals("bad_questions", Verdicts.check(JSONObject().put("x", JSONObject().put("type", "noul")), ui))
        assertEquals("bad_questions", Verdicts.check(JSONObject().put("x", q("noul", "")), ui))
        assertEquals("bad_questions", Verdicts.check(JSONObject().put("x", JSONObject().put("instructions", "i")), ui))
    }

    @Test fun aChoiceNeedsAtLeastTwoCriteria() {
        assertEquals("bad_questions", Verdicts.check(JSONObject().put("x", q("choice")), ui))
        assertEquals("bad_questions", Verdicts.check(Fixtures.choiceQ("a"), ui))
        assertNull(Verdicts.check(Fixtures.choiceQ("a", "b"), ui))
    }

    @Test fun aChoiceMayOnlyOfferTheOptionsTheUseAllows() {
        assertEquals("option_not_allowed", Verdicts.check(Fixtures.choiceQ("a", "c"), ui))
        assertEquals("option_not_allowed", Verdicts.check(Fixtures.choiceQ("c", "d"), ui))
        assertNull(Verdicts.check(Fixtures.choiceQ("b", "a"), ui))
    }

    @Test fun noulReadsPAndTheValueAndConfidence() {
        val qs = Fixtures.noulQ()
        fun read(p: Double) = Verdicts.read(JSONObject().put("ok", JSONObject().put("noul", p)), qs, 0.8)!!.getJSONObject("ok")
        val yes = read(0.95)
        assertEquals("noul", yes.getString("type"))
        assertEquals(0.95, yes.getDouble("p"), 0.0)
        assertTrue(yes.getBoolean("value"))
        assertTrue(yes.getBoolean("confident"))
        assertTrue(read(0.8).getBoolean("confident"))
        assertFalse(read(0.79).getBoolean("confident"))
        assertTrue(read(0.5).getBoolean("value"))
        assertFalse(read(0.49).getBoolean("value"))
        assertTrue(read(0.2).getBoolean("confident"))             // a confident NO
        assertFalse(read(0.21).getBoolean("confident"))
        assertTrue(read(0.0).getBoolean("confident"))
        assertTrue(read(1.0).getBoolean("confident"))
    }

    @Test fun aChoiceReadsItsPickAndConfidence() {
        val qs = Fixtures.choiceQ("a", "b")
        fun answer(pick: String, pa: Double, pb: Double) = JSONObject().put("pick", JSONObject().put("type", "choice").put("choice", pick)
            .put("probabilities", JSONObject().put("a", pa).put("b", pb)))
        val r = Verdicts.read(answer("a", 0.8, 0.2), qs, 0.8)!!.getJSONObject("pick")
        assertEquals("a", r.getString("pick"))
        assertEquals(0.8, r.getDouble("p"), 0.0)
        assertTrue(r.getBoolean("confident"))
        assertEquals(0.2, r.getJSONObject("probabilities").getDouble("b"), 0.0)
        assertFalse(Verdicts.read(answer("b", 0.45, 0.55), qs, 0.8)!!.getJSONObject("pick").getBoolean("confident"))
        assertEquals("choice", r.getString("type"))
    }

    @Test fun aScoreReadsItsBestLevel() {
        val qs = JSONObject().put("s", q("score"))
        val a = JSONObject().put("s", JSONObject().put("type", "score").put("probabilities", JSONObject().put("low", 0.1).put("mid", 0.2).put("high", 0.7)))
        val r = Verdicts.read(a, qs, 0.7)!!.getJSONObject("s")
        assertEquals("score", r.getString("type"))
        assertEquals("high", r.getString("level"))
        assertEquals(0.7, r.getDouble("p"), 0.0)
        assertTrue(r.getBoolean("confident"))
        assertFalse(Verdicts.read(a, qs, 0.71)!!.getJSONObject("s").getBoolean("confident"))
    }

    @Test fun anyUnreadableAnswerMakesTheWholeCallUnreadable() {
        val noul = Fixtures.noulQ()
        val ch = Fixtures.choiceQ("a", "b")
        assertNull(Verdicts.read(JSONObject(), noul, 0.8))
        assertNull(Verdicts.read(JSONObject().put("ok", JSONObject().put("noul", 1.5)), noul, 0.8))
        assertNull(Verdicts.read(JSONObject().put("ok", JSONObject().put("noul", -0.1)), noul, 0.8))
        assertNull(Verdicts.read(JSONObject().put("ok", JSONObject().put("noul", true)), noul, 0.8))
        assertNull(Verdicts.read(JSONObject().put("ok", JSONObject().put("noul", "0.9")), noul, 0.8))
        assertNull(Verdicts.read(JSONObject().put("ok", JSONObject()), noul, 0.8))
        fun c(choice: Any?, probs: JSONObject?): JSONObject {
            val o = JSONObject().put("type", "choice")
            if (choice != null) o.put("choice", choice)
            if (probs != null) o.put("probabilities", probs)
            return JSONObject().put("pick", o)
        }
        assertNull(Verdicts.read(c("a", null), ch, 0.8))
        assertNull(Verdicts.read(c("a", JSONObject()), ch, 0.8))
        assertNull(Verdicts.read(c(null, JSONObject().put("a", 0.9).put("b", 0.1)), ch, 0.8))
        assertNull(Verdicts.read(c(7, JSONObject().put("a", 0.9).put("b", 0.1)), ch, 0.8))
        assertNull(Verdicts.read(c("z", JSONObject().put("a", 0.9).put("b", 0.1).put("z", 0.0)), ch, 0.8))      // not the caller's option
        assertNull(Verdicts.read(c("a", JSONObject().put("b", 0.9)), ch, 0.8))                                    // pick without a probability
        assertNull(Verdicts.read(c("a", JSONObject().put("a", 1.4).put("b", 0.1)), ch, 0.8))
        assertNull(Verdicts.read(c("a", JSONObject().put("a", 0.9).put("b", "x")), ch, 0.8))
        assertNotNull(Verdicts.read(c("a", JSONObject().put("a", 0.9).put("b", 0.1)), ch, 0.8))
        val sc = JSONObject().put("s", q("score"))
        assertNull(Verdicts.read(JSONObject().put("s", JSONObject().put("probabilities", JSONObject())), sc, 0.8))
        assertNull(Verdicts.read(JSONObject().put("s", JSONObject().put("probabilities", JSONObject().put("l", 2))), sc, 0.8))
    }
}
