package com.diegonmarcos.cloudsearch.core.agents

import com.diegonmarcos.cloudsearch.core.Fixtures
import com.diegonmarcos.cloudsearch.core.Http
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmTest {
    private val ai = Fixtures.cfg.ai

    private class Fake(val reply: Http.Response? = null, val boom: Exception? = null) : Http {
        var url = ""; var headers: Map<String, String> = emptyMap(); var body = ""; var posts = 0
        override fun get(url: String, headers: Map<String, String>, timeoutMs: Int): Http.Response = error("an agent never GETs through the LLM door")
        override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): Http.Response {
            posts++; this.url = url; this.headers = headers; this.body = body
            boom?.let { throw it }
            return reply!!
        }
    }

    private val req = LlmRequest("m/x", "sys", "usr", 120)

    @Test fun theRequestHasNoToolsAndCapsTheAnswer() {
        val h = Fake(Http.Response(200, """{"choices":[{"message":{"content":"Hallo"}}]}"""))
        OpenRouterLlm(ai, h) { "sk-secret" }.complete(req)
        val o = JSONObject(h.body)
        assertEquals("m/x", o.getString("model"))
        assertEquals(120, o.getInt("max_tokens"))
        assertFalse(o.has("tools")); assertFalse(o.has("tool_choice")); assertFalse(o.has("plugins"))
        assertTrue(o.getJSONObject("usage").getBoolean("include"))
        assertEquals(listOf("system", "user"), (0 until 2).map { o.getJSONArray("messages").getJSONObject(it).getString("role") })
        assertEquals(ai.chatUrl, h.url)
    }

    @Test fun theTokenGoesOnlyInTheAuthorizationHeader() {
        val h = Fake(Http.Response(200, """{"choices":[{"message":{"content":"Hallo"}}]}"""))
        val r = OpenRouterLlm(ai, h) { "sk-secret" }.complete(req)
        assertEquals("Bearer sk-secret", h.headers["Authorization"])
        assertFalse(h.body.contains("sk-secret"))
        assertFalse(r.toString().contains("sk-secret"))
    }

    @Test fun noTokenNoRequest() {
        val h = Fake(Http.Response(200, "{}"))
        val r = OpenRouterLlm(ai, h) { null }.complete(req)
        assertEquals(0, h.posts)
        assertNull(r.text)
        assertTrue(r.error!!.contains("token"))
    }

    @Test fun usageAndCostAreRead() {
        val h = Fake(Http.Response(200, """{"choices":[{"message":{"content":"Hallo"}}],"usage":{"prompt_tokens":120,"completion_tokens":40,"cost":0.00031}}"""))
        val r = OpenRouterLlm(ai, h) { "t" }.complete(req)
        assertEquals("Hallo", r.text)
        assertEquals(120, r.promptTokens); assertEquals(40, r.completionTokens)
        assertEquals(0.00031, r.costUsd!!, 0.0)
    }

    @Test fun noCostFieldMeansNoProviderCost() {
        val h = Fake(Http.Response(200, """{"choices":[{"message":{"content":"x"}}],"usage":{"prompt_tokens":1,"completion_tokens":2,"cost":null}}"""))
        assertNull(OpenRouterLlm(ai, h) { "t" }.complete(req).costUsd)
        val h2 = Fake(Http.Response(200, """{"choices":[{"message":{"content":"x"}}]}"""))
        val r2 = OpenRouterLlm(ai, h2) { "t" }.complete(req)
        assertNull(r2.costUsd); assertEquals(0, r2.promptTokens)
    }

    @Test fun errorsNeverCarryTheToken() {
        val h = Fake(Http.Response(401, """{"error":{"message":"bad key sk-secret"}}"""))
        val r = OpenRouterLlm(ai, h) { "sk-secret" }.complete(req)
        assertNull(r.text)
        assertFalse(r.error!!.contains("sk-secret"))
        assertTrue(r.error!!.contains("***"))
        val boom = Fake(boom = java.io.IOException("connect to sk-secret failed"))
        val r2 = OpenRouterLlm(ai, boom) { "sk-secret" }.complete(req)
        assertNotNull(r2.error)
        assertFalse(r2.error!!.contains("sk-secret"))
        assertEquals("request failed: IOException", r2.error)
    }

    @Test fun anUnreadableAnswerIsAnError() {
        val r = OpenRouterLlm(ai, Fake(Http.Response(502, "<html>bad gateway</html>"))) { "t" }.complete(req)
        assertNull(r.text); assertNotNull(r.error)
    }
}
