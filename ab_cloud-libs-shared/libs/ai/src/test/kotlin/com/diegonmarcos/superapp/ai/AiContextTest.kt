package com.diegonmarcos.superapp.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiContextTest {
    private val s = AiStrings()
    private fun session(vararg m: AiMsg) = AiSession("1", "t", "m", 0, m.toList())

    @Test fun usageIsTheLastReportedAnswerAndNeverEstimated() {
        assertNull(AiContext.line(session(AiMsg("user", "hi")), 1000, s))
        assertNull(AiContext.line(session(AiMsg("user", "hi"), AiMsg("assistant", "yo")), 1000, s))
        val ses = session(AiMsg("user", "a"), AiMsg("assistant", "b", "", 100, 20), AiMsg("user", "c"), AiMsg("assistant", "d", "", 1500, 250))
        assertEquals(1750, AiContext.used(ses))
        assertEquals("Context 1,750 / 128,000 tokens (1.4%)", AiContext.line(ses, 128000, s))
        assertEquals("Context 1,750 tokens", AiContext.line(ses, 0, s))
        assertEquals("1,500 in · 250 out", AiContext.answer(ses.messages[3], s.answerTokens))
        assertEquals("", AiContext.answer(ses.messages[0], s.answerTokens))
    }

    @Test fun modelListReportsContextLengthAndSessionsKeepTokens() {
        val ms = AiProtocol.parseModels("""{"data":[{"id":"a/b","name":"B","context_length":200000}]}""", "web")
        assertEquals(200000L, ms[0].context)
        val back = AiFileSessions.fromJson(AiFileSessions.toJson(session(AiMsg("assistant", "x", "", 7, 3))))
        assertEquals(7L, back.messages[0].prompt)
        assertEquals(3L, back.messages[0].completion)
    }
}
