package com.diegonmarcos.superapp.decisions

import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/**
 * The shared Decisions client against a MOCK DECISIONS SERVER on loopback, over the same UrlHttp
 * the phone uses: the request shape and bearer header, and every way a call fails soft.
 */
class DecisionsTest {
    private lateinit var server: HttpServer
    private val bodies = mutableListOf<String>()
    private val auth = mutableListOf<String?>()
    private val methods = mutableListOf<String>()
    @Volatile private var reply = 200 to "{}"

    @Before fun up() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            synchronized(bodies) {
                bodies += ex.requestBody.readBytes().toString(Charsets.UTF_8)
                auth += ex.requestHeaders.getFirst("Authorization")
                methods += ex.requestMethod
            }
            val (code, body) = reply
            val b = body.toByteArray()
            ex.sendResponseHeaders(code, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        server.start()
    }

    @After fun down() = server.stop(0)

    private val url get() = "http://127.0.0.1:${server.address.port}/api/alpha/decisions"
    private fun key(tail: String) = listOf("sk", "or", tail).joinToString("-")
    private val qs = JSONObject().put("kind", JSONObject().put("type", "choice").put("instructions", "Which?").put("criteria", JSONObject().put("a", "A").put("b", "B")))

    @Test fun `a call posts model, state and questions with the token as bearer, and reads usage`() {
        reply = 200 to JSONObject().put("id", "gen-9").put("answers", JSONObject().put("kind", JSONObject().put("type", "choice").put("choice", "b")
            .put("probabilities", JSONObject().put("a", 0.3).put("b", 0.7)))).put("usage", JSONObject().put("cost", 0.00002).put("input_tokens", 120)).toString()
        var t = 1000L
        val d = Decisions.post(url, 2000, UrlHttp, key("abc-123456789"), "m/x", JSONObject().put("request", "hi"), qs) { t.also { t += 40 } }
        assertTrue(d.error, d.ok)
        val sent = JSONObject(bodies.single())
        assertEquals("m/x", sent.getString("model"))
        assertEquals("hi", sent.getJSONObject("state").getString("request"))
        assertEquals("choice", sent.getJSONObject("questions").getJSONObject("kind").getString("type"))
        assertEquals("Bearer ${key("abc-123456789")}", auth.single())
        assertEquals("POST", methods.single())
        assertEquals(200, d.status)
        assertEquals("gen-9", d.id)
        assertEquals(0.00002, d.cost!!, 0.0)
        assertEquals(120, d.inputTokens)
        assertEquals(40L, d.latencyMs)
        assertEquals(listOf("b" to 0.7, "a" to 0.3), d.options("kind").map { it.key to it.p })
        assertEquals("b", Decisions.pick(d.answers!!.getJSONObject("kind"), setOf("a", "b"))!!.key)
        val j = d.toJson()
        assertTrue(j.getBoolean("ok"))
        assertFalse(j.toString().contains("abc-123456789"))
        assertTrue(d.shownRequest(url).contains("Bearer ${Decision.REDACTED}"))
        assertFalse(d.shownRequest(url).contains("abc-123456789"))
    }

    @Test fun `no usage means no cost, and a non-number cost is ignored`() {
        reply = 200 to JSONObject().put("answers", JSONObject()).put("usage", JSONObject().put("cost", "free")).toString()
        val d = Decisions.post(url, 2000, UrlHttp, "k", "m", JSONObject(), qs)
        assertTrue(d.ok)
        assertNull(d.cost)
        assertNull(d.inputTokens)
        assertEquals("", d.id)
        assertTrue(d.toJson().isNull("cost"))
    }

    @Test fun `every failure is a reason, never an exception`() {
        val none = Decisions.post(url, 2000, UrlHttp, " ", "m", JSONObject(), qs)
        assertFalse(none.ok); assertTrue(none.error.startsWith("no OpenRouter token")); assertTrue(bodies.isEmpty())
        assertEquals(0, none.status)
        reply = 401 to """{"error":{"message":"No auth credentials found"}}"""
        assertEquals("HTTP 401: No auth credentials found", Decisions.post(url, 2000, UrlHttp, "k", "m", JSONObject(), qs).error)
        reply = 502 to "<html>bad gateway</html>"
        assertEquals("HTTP 502: <html>bad gateway</html>", Decisions.post(url, 2000, UrlHttp, "k", "m", JSONObject(), qs).error)
        reply = 299 to "not json"
        assertEquals("the answer is not JSON", Decisions.post(url, 2000, UrlHttp, "k", "m", JSONObject(), qs).error)
        reply = 200 to """{"id":"x"}"""
        val noAnswers = Decisions.post(url, 2000, UrlHttp, "k", "m", JSONObject(), qs)
        assertEquals("the answer carries no answers object", noAnswers.error)
        assertEquals(200, noAnswers.status)
        assertTrue(noAnswers.options("kind").isEmpty())
        reply = 300 to "{}"
        assertFalse(Decisions.post(url, 2000, UrlHttp, "k", "m", JSONObject(), qs).ok)
        val dead = Decisions.post("http://127.0.0.1:1/x", 500, UrlHttp, "k", "m", JSONObject(), qs)
        assertTrue(dead.error, dead.error.startsWith("network: "))
        val boom = Decisions.post(url, 2000, object : Http {
            override fun send(url: String, token: String?, body: String?, timeoutMs: Int): Http.Response = throw IllegalStateException("tls")
        }, "k", "m", JSONObject(), qs)
        assertEquals("network: IllegalStateException: tls", boom.error)
    }

    @Test fun `a GET carries the token and no body`() {
        reply = 200 to """{"data":{}}"""
        val r = UrlHttp.send(url, "k", null, 2000)
        assertEquals(200, r.code)
        assertEquals("GET", methods.single())
        assertEquals("", bodies.single())
        UrlHttp.send(url, null, null, 2000)
        assertNull(auth.last())
    }

    @Test fun `options read noul, choice and score, most probable first, and refuse bad probabilities`() {
        assertEquals(listOf("no" to 0.75, "yes" to 0.25), Decisions.options(JSONObject().put("type", "noul").put("noul", 0.25)).map { it.label to it.p })
        assertTrue(Decisions.options(JSONObject().put("type", "noul").put("noul", 1.5)).isEmpty())
        assertTrue(Decisions.options(JSONObject().put("type", "noul").put("noul", true)).isEmpty())
        assertTrue(Decisions.options(JSONObject().put("type", "noul")).isEmpty())
        val score = JSONObject().put("type", "score").put("probabilities", JSONObject().put("0", 0.1).put("1", 0.9)).put("legend", JSONObject().put("1", "high"))
        assertEquals(listOf("high" to 0.9, "0" to 0.1), Decisions.options(score).map { it.label to it.p })
        assertTrue(Decisions.options(JSONObject().put("type", "choice").put("probabilities", JSONObject().put("a", -0.1))).isEmpty())
        assertTrue(Decisions.options(JSONObject().put("type", "choice").put("probabilities", JSONObject())).isEmpty())
        assertTrue(Decisions.options(JSONObject().put("type", "choice")).isEmpty())
        assertTrue(Decisions.options(null).isEmpty())
        assertTrue(Decisions.isP(0) && Decisions.isP(1.0) && !Decisions.isP(1.0001) && !Decisions.isP(Double.NaN) && !Decisions.isP("0.5") && !Decisions.isP(null))
    }

    @Test fun `pick trusts only a choice inside the allowed set with a usable probability`() {
        val a = JSONObject().put("type", "choice").put("choice", "x").put("probabilities", JSONObject().put("x", 0.6).put("y", 0.4))
        assertEquals(0.6, Decisions.pick(a, setOf("x", "y"))!!.p, 0.0)
        assertNull(Decisions.pick(a, setOf("y")))
        assertNull(Decisions.pick(JSONObject().put("choice", 3), setOf("3")))
        assertNull(Decisions.pick(null, setOf("x")))
        assertNull(Decisions.pick(JSONObject(a.toString()).put("probabilities", JSONObject().put("y", 1.0)), setOf("x", "y")))
    }

    @Test fun `mask, cost estimate, api error and the options record`() {
        assertEquals("—", Decisions.mask(null))
        assertEquals("—", Decisions.mask(" "))
        assertEquals("…••••", Decisions.mask("12345678"))
        assertEquals("sk-or-…6789", Decisions.mask(key("v1-0123456789")))
        assertNull(Decisions.estimateCost(JSONObject(), null))
        assertEquals(1.0, Decisions.estimateCost(JSONObject(), 1.0)!!, 0.0) // "{}" = 2 chars = 1 token
        assertEquals(3.0, Decisions.estimateCost(JSONObject().put("ab", "c"), 1.0)!!, 0.0) // {"ab":"c"} = 10 chars = 3 tokens
        assertEquals("nope", Decisions.apiError("""{"error":{"message":"nope"}}"""))
        assertEquals("x".repeat(200), Decisions.apiError("x".repeat(500)))
        val rec = Decisions.optionsJson(listOf(Option("a", "A", 0.5)))
        assertEquals(1, rec.length())
        assertEquals("A", rec.getJSONObject(0).getString("label"))
        assertEquals(0.5, rec.getJSONObject(0).getDouble("p"), 0.0)
        assertEquals(JSONArray().toString(), Decisions.optionsJson(emptyList()).toString())
    }
}
