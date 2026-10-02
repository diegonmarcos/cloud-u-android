package com.diegonmarcos.cloudcalc.jev

import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * The Jev layer against a MOCK DECISIONS SERVER: a real HTTP server on loopback that records each
 * request and answers what the test scripted, so the request shape, the token header, every
 * threshold boundary and every failure path (HTTP error, malformed body, bad probability, a pick
 * outside the set, timeout, no token) are exercised over the same UrlHttp the phone uses.
 */
class JevTest {
    private class Mock {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val bodies = mutableListOf<JSONObject>()
        val auth = mutableListOf<String?>()
        val replies = ArrayDeque<Triple<Int, String, Long>>()

        init {
            server.createContext("/") { ex ->
                val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                synchronized(this) {
                    if (body.isNotEmpty()) bodies += JSONObject(body)
                    auth += ex.requestHeaders.getFirst("Authorization")
                }
                val (code, reply, delay) = synchronized(this) { replies.removeFirstOrNull() } ?: Triple(500, "{}", 0L)
                if (delay > 0) Thread.sleep(delay)
                val bytes = reply.toByteArray()
                runCatching {
                    ex.sendResponseHeaders(code, bytes.size.toLong())
                    ex.responseBody.use { it.write(bytes) }
                }
            }
            // Handlers on their own threads, so a scripted delay never holds up stop().
            server.executor = Executors.newCachedThreadPool()
            server.start()
        }

        val url get() = "http://127.0.0.1:${server.address.port}/api/alpha/decisions"
        fun reply(code: Int, body: String, delayMs: Long = 0) = synchronized(this) { replies.addLast(Triple(code, body, delayMs)) }
    }

    private lateinit var mock: Mock

    @Before fun up() { mock = Mock() }
    @After fun down() { mock.server.stop(0) }

    /** A small declaration of our own, so these tests do not move when build.json's tools do. */
    private fun cfgJson(mutate: (JSONObject) -> Unit = {}): JSONObject = JSONObject(
        """
        {"endpoint":"https://x.test/d","models_url":"https://x.test/m","key_url":"https://x.test/k","account_provider":"openrouter",
         "timeout_ms":1000,"catalog_ttl_hours":24,
         "uses":{"route":"typesafe/jev-1.13","score":"cheapest","_doc":"x"},
         "fallback_model":"typesafe/jev-1.13",
         "use_labels":{"route":"Routing","_doc":"x"},
         "route":{"instructions":"Which tool?","none_criterion":"Nothing to compute.","threshold":0.75,
           "candidates":2,"candidate_min_p":0.1,"on_error":"expression","fallback_mode":"standard",
           "slot_threshold":0.6,"slot_instructions":"Which number is the {field}?",
           "strip":["^(convert|what's)\\s+","\\?+$"],
           "duration_units":{"s":1,"min":60,"minutes":60,"h":3600},
           "tools":{"_doc":"x",
             "units":{"criterion":"Convert units.","action":"expression","mode":"units"},
             "tip":{"criterion":"A tip.","action":"form","mode":"finance","form":"tip"},
             "timer":{"criterion":"A timer.","action":"timer"},
             "meter":{"criterion":"Measure noise.","action":"open","mode":"meter"}},
           "questions":{"computable":{"type":"noul","instructions":"Is it computable?","criteria":{"true":"yes","false":"no"}}}},
         "ask":{"*":[{"id":"plausible","label":"Plausible?","type":"score","instructions":"How plausible?","criteria":["no","maybe","yes"]}],
                "finance":[{"id":"tip_ok","label":"Reasonable tip?","type":"noul","instructions":"Reasonable?"}]},
         "identify":{"_doc":"x","sound":{"instructions":"Which source?",
           "classes":{"_doc":"x","speech":"A voice.","alarm":"A beeper.","other":"Else."},
           "questions":{"natural":{"label":"Natural?","type":"noul","instructions":"Natural source?"},
                        "attention":{"type":"score","instructions":"How urgent?","criteria":["none","some","act"]}}}}}
        """.trimIndent(),
    ).also(mutate)

    private fun cfg(mutate: (JSONObject) -> Unit = {}): JevConfig =
        JevConfig.parse(cfgJson(mutate).toString()).copy(endpoint = mock.url)

    private fun routeAnswer(choice: String, probs: Map<String, Any>, computable: Any = 0.9) = JSONObject()
        .put("id", "gen-1")
        .put("answers", JSONObject()
            .put("route", JSONObject().put("type", "choice").put("choice", choice).put("probabilities", JSONObject(probs)))
            .put("computable", JSONObject().put("type", "noul").put("noul", computable)))
        .put("usage", JSONObject().put("input_tokens", 300).put("output_tokens", 10).put("cost", 0.0000126))
        .toString()

    /** Fake keys are assembled at run time, so no key-shaped literal trips the leak scan. */
    private fun fakeKey(tail: String) = listOf("sk", "or", tail).joinToString("-")
    private val testKey = fakeKey("test-0123456789")
    private val secretKey = fakeKey("SECRET-abcdef123456")

    private fun route(request: String, token: String? = testKey) =
        JevRouter.route(cfg(), UrlHttp, token, "typesafe/jev-1.13", request)

    // ── the declaration ─────────────────────────────────────────────────────────────────────

    @Test fun `parse reads every field, skipping _doc keys`() {
        val c = cfg()
        assertEquals(setOf("route", "score"), c.uses.keys)
        assertEquals(JevConfig.CHEAPEST, c.model("score"))
        assertEquals(JevConfig.CHEAPEST, c.model("unknown"))
        assertEquals("Routing", c.useLabel("route"))
        assertEquals("score", c.useLabel("score"))
        assertEquals(setOf("route"), c.useLabels.keys)
        // org.json's JVM JSONObject is a HashMap: compare as sets (Android's keeps declared order).
        assertEquals(setOf("units", "tip", "timer", "meter"), c.route.tools.map { it.id }.toSet())
        assertEquals(JevConfig.Tool("tip", "A tip.", "form", "finance", "tip"), c.route.tools.first { it.id == "tip" })
        assertEquals(0.75, c.route.threshold, 0.0)
        assertEquals(2, c.route.candidates)
        assertEquals(0.1, c.route.candidateMinP, 0.0)
        assertEquals(0.6, c.route.slotThreshold, 0.0)
        assertEquals(60, c.route.durationUnits["min"])
        assertEquals(1000, c.timeoutMs)
        assertEquals("openrouter", c.accountProvider)
        assertEquals(24, c.catalogTtlHours)
        assertEquals("standard", c.route.fallbackMode)
        assertEquals("computable", c.route.extra.single().id)
        assertEquals(mapOf("true" to "yes", "false" to "no"), c.route.extra.single().choices)
        assertEquals(listOf("tip_ok", "plausible"), c.questionsFor("finance").map { it.id })
        assertEquals(listOf("no", "maybe", "yes"), c.questionsFor("units").single().levels)
        assertEquals("Reasonable tip?", c.ask.getValue("finance").single().label)
    }

    @Test fun `parse defaults the optional knobs`() {
        val c = JevConfig.parse(cfgJson { o ->
            o.remove("timeout_ms"); o.remove("catalog_ttl_hours")
            o.getJSONObject("route").apply { remove("candidates"); remove("candidate_min_p"); remove("slot_threshold"); remove("questions") }
            o.remove("ask")
        }.toString())
        assertEquals(8000, c.timeoutMs)
        assertEquals(24, c.catalogTtlHours)
        assertEquals(3, c.route.candidates)
        assertEquals(0.05, c.route.candidateMinP, 0.0)
        assertEquals(0.6, c.route.slotThreshold, 0.0)
        assertTrue(c.route.extra.isEmpty())
        assertTrue(c.ask.isEmpty())
    }

    private fun rejects(why: String, mutate: (JSONObject) -> Unit) {
        try {
            JevConfig.parse(cfgJson(mutate).toString())
            fail("accepted a config with $why")
        } catch (e: IllegalArgumentException) {
            assertTrue("$why → ${e.message}", e.message!!.isNotBlank())
        }
    }

    private fun JSONObject.route() = getJSONObject("route")
    private fun JSONObject.tool(id: String) = route().getJSONObject("tools").getJSONObject(id)

    @Test fun `parse rejects every broken declaration with a reason`() {
        try { JevConfig.parse("not json"); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.startsWith("not a JSON object")) }
        rejects("an http endpoint") { it.put("endpoint", "http://x.test/d") }
        rejects("an http catalogue") { it.put("models_url", "http://x.test/m") }
        rejects("an http key check") { it.put("key_url", "http://x.test/k") }
        rejects("no endpoint") { it.remove("endpoint") }
        rejects("no account provider") { it.remove("account_provider") }
        rejects("a blank endpoint") { it.put("endpoint", " ") }
        rejects("no route") { it.remove("route") }
        rejects("a timeout of 999 ms") { it.put("timeout_ms", 999) }
        rejects("a timeout of 60001 ms") { it.put("timeout_ms", 60001) }
        rejects("a zero TTL") { it.put("catalog_ttl_hours", 0) }
        rejects("no score use") { it.getJSONObject("uses").remove("score") }
        rejects("no route use") { it.getJSONObject("uses").remove("route") }
        rejects("a threshold above 1") { it.route().put("threshold", 1.01) }
        rejects("a negative threshold") { it.route().put("threshold", -0.01) }
        rejects("a candidate_min_p above 1") { it.route().put("candidate_min_p", 1.5) }
        rejects("a slot_threshold above 1") { it.route().put("slot_threshold", 2) }
        rejects("zero candidates") { it.route().put("candidates", 0) }
        rejects("an unknown on_error") { it.route().put("on_error", "guess") }
        rejects("on_error expression without a fallback mode") { it.route().put("fallback_mode", "") }
        rejects("no tools") { it.route().put("tools", JSONObject()) }
        rejects("a tool named none") { it.route().getJSONObject("tools").put("none", JSONObject().put("criterion", "x").put("action", "open").put("mode", "m")) }
        rejects("an unknown action") { it.tool("units").put("action", "compute") }
        rejects("an expression with no mode") { it.tool("units").remove("mode") }
        rejects("a form with no form") { it.tool("tip").remove("form") }
        rejects("a tool with no criterion") { it.tool("units").remove("criterion") }
        rejects("a timer with no duration units") { it.route().put("duration_units", JSONObject()) }
        rejects("a bad strip pattern") { it.route().put("strip", JSONArray().put("(")) }
        rejects("an unknown question type") { it.route().getJSONObject("questions").getJSONObject("computable").put("type", "maybe") }
        rejects("a one-way choice") {
            it.getJSONObject("ask").put("*", JSONArray().put(JSONObject().put("type", "choice").put("instructions", "q").put("criteria", JSONObject().put("a", "x"))))
        }
        rejects("a one-level score") {
            it.getJSONObject("ask").put("*", JSONArray().put(JSONObject().put("type", "score").put("instructions", "q").put("criteria", JSONArray().put("x"))))
        }
        rejects("a question with no instructions") { it.route().getJSONObject("questions").getJSONObject("computable").remove("instructions") }
        rejects("an identify with one class") { it.getJSONObject("identify").getJSONObject("sound").put("classes", JSONObject().put("x", "X")) }
        rejects("an identify with no classes") { it.getJSONObject("identify").getJSONObject("sound").remove("classes") }
        rejects("an identify with no instructions") { it.getJSONObject("identify").getJSONObject("sound").remove("instructions") }
        rejects("an identify extra named class") {
            it.getJSONObject("identify").getJSONObject("sound").getJSONObject("questions").put("class", JSONObject().put("type", "noul").put("instructions", "q"))
        }
        rejects("an identify extra of an unknown type") {
            it.getJSONObject("identify").getJSONObject("sound").getJSONObject("questions").getJSONObject("natural").put("type", "maybe")
        }
    }

    // ── what is this? (#772) ────────────────────────────────────────────────────────────────

    @Test fun `identify is parsed per kind, _doc keys skipped, and is optional`() {
        val i = cfg().identify.getValue("sound")
        assertEquals(setOf("sound"), cfg().identify.keys)
        assertEquals("Which source?", i.instructions)
        assertEquals(setOf("speech", "alarm", "other"), i.classes.keys)
        assertEquals("A beeper.", i.classes["alarm"])
        assertEquals(setOf("natural", "attention"), i.extra.map { it.id }.toSet())
        assertEquals("Natural?", i.extra.first { it.id == "natural" }.label)
        assertTrue(JevConfig.parse(cfgJson { it.remove("identify") }.toString()).identify.isEmpty())
    }

    @Test fun `identify sends what was measured and asks one choice over the classes plus the extras`() {
        mock.reply(200, JSONObject().put("answers", JSONObject()
            .put("class", JSONObject().put("type", "choice").put("choice", "alarm").put("probabilities", JSONObject().put("alarm", 0.9).put("speech", 0.06).put("other", 0.04)))
            .put("natural", JSONObject().put("type", "noul").put("noul", 0.1))).toString())
        val measured = JSONObject().put("dominant_hz", 3150.0).put("periodic", true)
        val d = JevRouter.identify(cfg(), UrlHttp, testKey, "typesafe/jev-1.13", "sound", measured)
        val sent = mock.bodies.single()
        assertEquals("typesafe/jev-1.13", sent.getString("model"))
        assertEquals(3150.0, sent.getJSONObject("state").getJSONObject("measured").getDouble("dominant_hz"), 0.0)
        assertTrue(sent.getJSONObject("state").getJSONObject("measured").getBoolean("periodic"))
        val qs = sent.getJSONObject("questions")
        assertEquals(setOf(JevConfig.IDENTIFY_CLASS, "natural", "attention"), qs.keySet())
        val c = qs.getJSONObject(JevConfig.IDENTIFY_CLASS)
        assertEquals("choice", c.getString("type"))
        assertEquals("Which source?", c.getString("instructions"))
        assertEquals(setOf("speech", "alarm", "other"), c.getJSONObject("criteria").keySet())
        assertEquals("noul", qs.getJSONObject("natural").getString("type"))
        assertEquals(JSONArray(listOf("none", "some", "act")).toString(), qs.getJSONObject("attention").getJSONArray("criteria").toString())
        assertEquals("Bearer $testKey", mock.auth.single())
        assertTrue(d.ok)
        assertEquals(listOf("alarm", "speech", "other"), d.options(JevConfig.IDENTIFY_CLASS).map { it.key })
        assertEquals(0.9, Decisions.pick(d.answers?.optJSONObject(JevConfig.IDENTIFY_CLASS), setOf("speech", "alarm", "other"))!!.p, 0.0)
        assertEquals(listOf("no", "yes"), d.options("natural").map { it.label })
    }

    @Test fun `identify fails soft - no token makes no call, an HTTP error is a reason, an unknown kind is refused`() {
        val none = JevRouter.identify(cfg(), UrlHttp, null, "m", "sound", JSONObject())
        assertFalse(none.ok)
        assertTrue(mock.bodies.isEmpty())
        mock.reply(503, """{"error":{"message":"overloaded"}}""")
        val err = JevRouter.identify(cfg(), UrlHttp, testKey, "m", "sound", JSONObject())
        assertFalse(err.ok)
        assertEquals("HTTP 503: overloaded", err.error)
        assertTrue(err.options(JevConfig.IDENTIFY_CLASS).isEmpty())
        try { JevRouter.identifyQuestions(cfg(), "smell"); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("smell")) }
    }

    @Test fun `identify with an image sends the measurements as text and the image as content parts`() {
        val m = JSONObject().put("note", "A4")
        assertEquals("""{"measured":{"note":"A4"}}""", JevRouter.identifyState(m, null).toString())
        val parts = JevRouter.identifyState(m, "data:image/png;base64,AAAA") as JSONArray
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertEquals("""measured: {"note":"A4"}""", parts.getJSONObject(0).getString("text"))
        assertEquals("image_url", parts.getJSONObject(1).getString("type"))
        assertEquals("data:image/png;base64,AAAA", parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
    }

    @Test fun `a timer-free config needs no duration units and on_error none needs no fallback mode`() {
        val c = JevConfig.parse(cfgJson {
            it.route().getJSONObject("tools").remove("timer")
            it.route().put("duration_units", JSONObject())
            it.route().put("on_error", "none").put("fallback_mode", "")
        }.toString())
        assertEquals("none", c.route.onError)
    }

    @Test fun `question wire shapes are what the Decisions API takes`() {
        val noul = JevConfig.Question("a", "A", "noul", "Is it?")
        // Compared field by field: the JVM's org.json orders keys by hash, not insertion.
        assertEquals(setOf("type", "instructions"), noul.wire().keySet())
        assertEquals("noul", noul.wire().getString("type"))
        assertEquals("Is it?", noul.wire().getString("instructions"))
        val noulCrit = JevConfig.Question("a", "A", "noul", "Is it?", mapOf("true" to "y"))
        assertEquals("y", noulCrit.wire().getJSONObject("criteria").getString("true"))
        val choice = JevConfig.Question("b", "B", "choice", "Which?", mapOf("x" to "X", "y" to "Y"))
        assertEquals("X", choice.wire().getJSONObject("criteria").getString("x"))
        val score = JevConfig.Question("c", "C", "score", "How?", levels = listOf("lo", "hi"))
        assertEquals(JSONArray(listOf("lo", "hi")).toString(), score.wire().getJSONArray("criteria").toString())
    }

    // ── routing against the mock server ─────────────────────────────────────────────────────

    @Test fun `a confident pick routes, and the request is the declared shape with the token as bearer`() {
        mock.reply(200, routeAnswer("units", mapOf("units" to 0.9, "tip" to 0.05, "timer" to 0.03, "meter" to 0.01, "none" to 0.01)))
        val o = route("convert 3 ft to cm")
        assertEquals(JevRouter.Kind.ROUTED, o.kind)
        assertEquals("units", o.tool!!.id)
        assertEquals("units", o.options.first().key)
        assertEquals(0.9, o.options.first().p, 1e-9)
        assertEquals(0.9, o.extras.getValue("computable").first { it.key == "true" }.p, 1e-9)
        assertNull(o.fallback)
        val sent = mock.bodies.single()
        assertEquals("typesafe/jev-1.13", sent.getString("model"))
        assertEquals("convert 3 ft to cm", sent.getJSONObject("state").getString("request"))
        val q = sent.getJSONObject("questions").getJSONObject("route")
        assertEquals("choice", q.getString("type"))
        assertEquals("Which tool?", q.getString("instructions"))
        assertEquals(setOf("units", "tip", "timer", "meter", "none"), q.getJSONObject("criteria").keySet())
        assertEquals("Nothing to compute.", q.getJSONObject("criteria").getString("none"))
        assertEquals("noul", sent.getJSONObject("questions").getJSONObject("computable").getString("type"))
        assertEquals("Bearer $testKey", mock.auth.single())
        val d = o.decision
        assertEquals(200, d.status)
        assertEquals("gen-1", d.id)
        assertEquals(0.0000126, d.cost!!, 1e-12)
        assertEquals(300, d.inputTokens)
        assertTrue(d.latencyMs >= 0)
    }

    @Test fun `the threshold is inclusive`() {
        mock.reply(200, routeAnswer("units", mapOf("units" to 0.75, "tip" to 0.25)))
        assertEquals(JevRouter.Kind.ROUTED, route("x").kind)
        mock.reply(200, routeAnswer("units", mapOf("units" to 0.7499, "tip" to 0.2501)))
        assertEquals(JevRouter.Kind.CANDIDATES, route("x").kind)
    }

    @Test fun `a confident none routes nowhere`() {
        mock.reply(200, routeAnswer("none", mapOf("none" to 0.95, "units" to 0.05)))
        val o = route("tell me a joke")
        assertEquals(JevRouter.Kind.NONE, o.kind)
        assertNull(o.tool)
        assertTrue(o.candidates.isEmpty())
    }

    @Test fun `low confidence offers the top candidates above the floor, none excluded`() {
        mock.reply(200, routeAnswer("tip", mapOf("tip" to 0.4, "none" to 0.35, "units" to 0.15, "meter" to 0.08, "timer" to 0.02)))
        val o = route("42 and 15")
        assertEquals(JevRouter.Kind.CANDIDATES, o.kind)
        assertEquals(listOf("tip", "units"), o.candidates.map { it.id })
        assertNull(o.tool)
    }

    @Test fun `the candidate floor is inclusive and the count is capped`() {
        mock.reply(200, routeAnswer("tip", mapOf("tip" to 0.5, "units" to 0.1, "meter" to 0.1, "timer" to 0.3)))
        assertEquals(listOf("tip", "timer"), route("x").candidates.map { it.id })
        mock.reply(200, routeAnswer("tip", mapOf("tip" to 0.5, "units" to 0.1, "none" to 0.4)))
        assertEquals(listOf("tip", "units"), route("x").candidates.map { it.id })
    }

    private fun assertFails(o: JevRouter.Outcome, why: String) {
        assertEquals(JevRouter.Kind.FAILED, o.kind)
        assertTrue("${o.reason} should mention $why", o.reason.contains(why))
        // Offline-safe: the request, stripped, goes to the calculator as typed.
        assertEquals(JevRouter.Plan.Expression("standard", "3 ft to cm"), o.fallback)
        assertNull(o.tool)
    }

    @Test fun `HTTP errors fall back to the calculator, with OpenRouter's message`() {
        mock.reply(500, """{"error":{"message":"upstream down"}}""")
        assertFails(route("convert 3 ft to cm?"), "upstream down")
        mock.reply(401, "nope")
        val o = route("convert 3 ft to cm")
        assertFails(o, "HTTP 401: nope")
        assertEquals(401, o.decision.status)
        mock.reply(300, "{}")
        assertFails(route("convert 3 ft to cm"), "HTTP 300")
    }

    @Test fun `a 2xx edge status is still read`() {
        mock.reply(299, routeAnswer("units", mapOf("units" to 0.9)))
        assertEquals(JevRouter.Kind.ROUTED, route("x").kind)
    }

    @Test fun `malformed answers fall back`() {
        mock.reply(200, "<html>")
        assertFails(route("convert 3 ft to cm"), "not JSON")
        mock.reply(200, """{"id":"x"}""")
        assertFails(route("convert 3 ft to cm"), "no answers")
        for (bad in listOf(
            """{"answers":{}}""",
            routeAnswer("units", mapOf("units" to 1.2, "tip" to 0.0)),
            routeAnswer("units", mapOf("units" to true)),
            routeAnswer("rocket", mapOf("rocket" to 0.99)),
            routeAnswer("units", mapOf("units" to -0.1)),
        )) {
            mock.reply(200, bad)
            val o = route("convert 3 ft to cm")
            assertFails(o, "no usable pick")
            assertTrue(o.decision.ok)
        }
    }

    @Test fun `no token makes no call and falls back`() {
        assertFails(route("convert 3 ft to cm", token = null), "no OpenRouter token")
        assertFails(route("convert 3 ft to cm", token = " "), "no OpenRouter token")
        assertTrue(mock.bodies.isEmpty())
        assertTrue(mock.auth.isEmpty())
    }

    @Test fun `a timeout falls back instead of hanging`() {
        mock.reply(200, routeAnswer("units", mapOf("units" to 0.9)), delayMs = 2500)
        val t0 = System.currentTimeMillis()
        val o = route("convert 3 ft to cm")
        assertFails(o, "network")
        assertTrue(System.currentTimeMillis() - t0 < 2400)
    }

    @Test fun `an unreachable server falls back`() {
        val c = cfg().copy(endpoint = "http://127.0.0.1:1/d")
        val o = JevRouter.route(c, UrlHttp, "k", "m", "convert 3 ft to cm")
        assertEquals(JevRouter.Kind.FAILED, o.kind)
        assertTrue(o.reason.startsWith("network: "))
    }

    @Test fun `on_error none says so instead of guessing`() {
        mock.reply(500, "{}")
        val c = JevConfig.parse(cfgJson { it.route().put("on_error", "none") }.toString()).copy(endpoint = mock.url)
        val o = JevRouter.route(c, UrlHttp, "k", "m", "convert 3 ft to cm")
        assertEquals(JevRouter.Kind.FAILED, o.kind)
        assertNull(o.fallback)
    }

    @Test fun `the outcome serialises without the token`() {
        mock.reply(200, routeAnswer("units", mapOf("units" to 0.9)))
        val j = route("convert 3 ft to cm", token = secretKey).toJson()
        assertFalse(j.toString().contains("SECRET"))
        assertEquals("routed", j.getString("kind"))
        assertEquals("units", j.getString("tool"))
        assertEquals("units", j.getJSONArray("options").getJSONObject(0).getString("key"))
        assertTrue(j.getJSONObject("extras").has("computable"))
        assertEquals("typesafe/jev-1.13", j.getJSONObject("decision").getString("model"))
        assertTrue(j.getJSONObject("decision").getBoolean("ok"))
        val shown = route("x", token = secretKey).decision.shownRequest("https://e")
        assertFalse(shown.contains("SECRET"))
        assertTrue(shown.contains("Bearer [REDACTED]"))
        assertTrue(shown.startsWith("POST https://e\n"))
    }

    // ── reading the request ─────────────────────────────────────────────────────────────────

    @Test fun `numbers are read in order, decimals and signs kept, words skipped`() {
        assertEquals(listOf("60", "63"), JevRouter.numbers("dB sum of 60 and 63"))
        assertEquals(listOf("15", "42"), JevRouter.numbers("what's 15% tip on 42"))
        assertEquals(listOf("-2.5", "3"), JevRouter.numbers("add -2.5 to 3"))
        assertEquals(listOf("60", "63"), JevRouter.numbers("60, 63"))
        assertEquals(emptyList<String>(), JevRouter.numbers("abc123 x1"))
        assertEquals(listOf("2"), JevRouter.numbers("x-2"))
        assertEquals(listOf("1.5"), JevRouter.numbers("1.5."))
    }

    @Test fun `strip drops declared lead-ins and the question mark`() {
        val p = cfg().route.strip
        assertEquals("3 ft to cm", JevRouter.strip("  Convert 3 ft to cm?? ", p))
        assertEquals("15% of 42", JevRouter.strip("what's 15% of 42?", p))
        assertEquals("2+2", JevRouter.strip("2+2", p))
        assertEquals("2+2", JevRouter.strip("2+2", emptyList()))
    }

    @Test fun `durations add up by the declared units`() {
        val u = cfg().route.durationUnits
        assertEquals(600L, JevRouter.duration("start a 10 min timer", u))
        assertEquals(600L, JevRouter.duration("10 minutes", u))
        assertEquals(5400L, JevRouter.duration("1 h 30 min", u))
        assertEquals(90L, JevRouter.duration("90s", u))
        assertEquals(90L, JevRouter.duration("1.5 MIN", u))
        assertNull(JevRouter.duration("a timer", u))
        assertNull(JevRouter.duration("10 parsecs", u))
        assertNull(JevRouter.duration("10 min", emptyMap()))
    }

    @Test fun `positional fills fields in order and keeps defaults`() {
        val f = listOf("a" to "1", "b" to "2", "c" to "3")
        assertEquals(mapOf("a" to "9", "b" to "8", "c" to "3"), JevRouter.positional(f, listOf("9", "8")))
        assertEquals(mapOf("a" to "1", "b" to "2", "c" to "3"), JevRouter.positional(f, emptyList()))
    }

    private val tipFields = listOf(Triple("bill", "Bill", "50"), Triple("tip", "Tip %", "15"), Triple("people", "People", "2"))

    private fun slotAnswer(vararg picks: Pair<String, Pair<String, Double>>) = JSONObject().put("answers", JSONObject().apply {
        picks.forEach { (field, pick) ->
            put(field, JSONObject().put("type", "choice").put("choice", pick.first)
                .put("probabilities", JSONObject().put(pick.first, pick.second).put("other", 1 - pick.second)))
        }
    }).toString()

    @Test fun `slot questions decide which number is which field`() {
        mock.reply(200, slotAnswer("bill" to ("n2" to 0.9), "tip" to ("n1" to 0.95), "people" to ("none" to 0.9)))
        val (v, d) = JevRouter.slots(cfg(), UrlHttp, "k", "m", "what's 15% tip on 42", tipFields)
        assertEquals(mapOf("bill" to "42", "tip" to "15", "people" to "2"), v)
        assertTrue(d!!.ok)
        val q = mock.bodies.single().getJSONObject("questions")
        assertEquals(setOf("bill", "tip", "people"), q.keySet())
        assertEquals("Which number is the Tip %?", q.getJSONObject("tip").getString("instructions"))
        assertEquals(setOf("n1", "n2", "none"), q.getJSONObject("bill").getJSONObject("criteria").keySet())
        assertTrue(q.getJSONObject("bill").getJSONObject("criteria").getString("n2").contains("42"))
    }

    @Test fun `an unsure or unusable slot keeps its default, the boundary inclusive`() {
        mock.reply(200, slotAnswer("bill" to ("n2" to 0.6), "tip" to ("n1" to 0.59), "people" to ("n9" to 0.99)))
        val (v, _) = JevRouter.slots(cfg(), UrlHttp, "k", "m", "16 on 42", tipFields)
        assertEquals(mapOf("bill" to "42", "tip" to "15", "people" to "2"), v)
    }

    @Test fun `a failed slot call falls back to positional, and a lone field or no number makes no call`() {
        mock.reply(500, "{}")
        val (v, d) = JevRouter.slots(cfg(), UrlHttp, "k", "m", "42 15", tipFields)
        assertEquals(mapOf("bill" to "42", "tip" to "15", "people" to "2"), v)
        assertFalse(d!!.ok)
        val (v1, d1) = JevRouter.slots(cfg(), UrlHttp, "k", "m", "20 degrees", listOf(Triple("t", "Temperature", "15")))
        assertEquals(mapOf("t" to "20"), v1)
        assertNull(d1)
        val (v2, d2) = JevRouter.slots(cfg(), UrlHttp, "k", "m", "a tip", tipFields)
        assertEquals(mapOf("bill" to "50", "tip" to "15", "people" to "2"), v2)
        assertNull(d2)
        assertEquals(1, mock.bodies.size)
    }

    @Test fun `plan turns each action into what the app runs`() {
        val c = cfg()
        val t = c.route.tools.associateBy { it.id }
        assertEquals(JevRouter.Plan.Expression("units", "3 ft to cm"), JevRouter.plan(c, UrlHttp, "k", "m", t.getValue("units"), "convert 3 ft to cm?", emptyList()))
        assertEquals(JevRouter.Plan.Timer(600, ""), JevRouter.plan(c, UrlHttp, "k", "m", t.getValue("timer"), "start a 10 min timer", emptyList()))
        assertEquals(JevRouter.Plan.Timer(0, ""), JevRouter.plan(c, UrlHttp, "k", "m", t.getValue("timer"), "a timer", emptyList()))
        assertEquals(JevRouter.Plan.Open("meter"), JevRouter.plan(c, UrlHttp, "k", "m", t.getValue("meter"), "how loud", emptyList()))
        val form = JevRouter.plan(c, UrlHttp, null, "m", t.getValue("tip"), "42 15", tipFields) as JevRouter.Plan.Form
        assertEquals("finance", form.mode)
        assertEquals("tip", form.form)
        assertEquals(mapOf("bill" to "42", "tip" to "15", "people" to "2"), form.values)
    }

    @Test fun `an image rides as content parts`() {
        assertEquals("""{"request":"hi"}""", JevRouter.state("hi").toString())
        val parts = JevRouter.state("hi", "data:image/png;base64,AAAA") as JSONArray
        assertEquals("hi", parts.getJSONObject(0).getString("text"))
        assertEquals("data:image/png;base64,AAAA", parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
    }

    // ── follow-up questions about a result ──────────────────────────────────────────────────

    @Test fun `a free question is yes-no unless options make it a choice or a scale`() {
        assertEquals(JevConfig.NOUL, JevRouter.freeQuestion("Is it hot?", "noul", "").type)
        assertEquals(JevConfig.NOUL, JevRouter.freeQuestion("Is it hot?", "choice", "only one").type)
        val c = JevRouter.freeQuestion("Which?", "choice", "cold | warm |hot| warm")
        assertEquals(JevConfig.CHOICE, c.type)
        assertEquals(listOf("cold", "warm", "hot"), c.choices.keys.toList())
        val s = JevRouter.freeQuestion("How hot?", "score", "low\nhigh")
        assertEquals(listOf("low", "high"), s.levels)
        assertEquals(JevConfig.NOUL, JevRouter.freeQuestion("x", "score", "one").type)
    }

    @Test fun `asking about a result sends the result as state and reads every option`() {
        mock.reply(200, JSONObject().put("answers", JSONObject().put("plausible", JSONObject().put("type", "score").put("score", 1.8)
            .put("probabilities", JSONObject().put("0", 0.05).put("1", 0.15).put("2", 0.8))
            .put("legend", JSONObject().put("0", "no").put("1", "maybe").put("2", "yes")))).toString())
        val q = cfg().questionsFor("units").single()
        val d = JevRouter.ask(cfg(), UrlHttp, "k", "m", q, JevRouter.resultState("Units", "3 ft to cm", "91.44 cm", ""))
        val sent = mock.bodies.single()
        assertEquals("91.44 cm", sent.getJSONObject("state").getString("result"))
        assertFalse(sent.getJSONObject("state").has("context"))
        assertEquals("score", sent.getJSONObject("questions").getJSONObject("plausible").getString("type"))
        assertEquals(listOf("yes" to 0.8, "maybe" to 0.15, "no" to 0.05), d.options("plausible").map { it.label to it.p })
        assertEquals("ctx", JevRouter.resultState("m", "c", "r", "ctx").getString("context"))
    }

    // ── answers, cost, masking ──────────────────────────────────────────────────────────────

    @Test fun `options read noul, choice and score and reject bad probabilities`() {
        val noul = Decisions.options(JSONObject().put("type", "noul").put("noul", 0.3))
        assertEquals(listOf("no" to 0.7, "yes" to 0.3), noul.map { it.label to it.p }.map { it.first to Math.round(it.second * 10) / 10.0 })
        assertTrue(Decisions.options(JSONObject().put("type", "noul").put("noul", 1.3)).isEmpty())
        assertTrue(Decisions.options(JSONObject().put("type", "noul").put("noul", true)).isEmpty())
        assertTrue(Decisions.options(JSONObject().put("type", "noul")).isEmpty())
        assertTrue(Decisions.options(null).isEmpty())
        assertTrue(Decisions.options(JSONObject().put("type", "choice")).isEmpty())
        assertTrue(Decisions.options(JSONObject().put("type", "choice").put("probabilities", JSONObject())).isEmpty())
        assertTrue(Decisions.options(JSONObject().put("type", "choice").put("probabilities", JSONObject().put("a", 0.5).put("b", "x"))).isEmpty())
        val score = Decisions.options(JSONObject().put("type", "score").put("probabilities", JSONObject().put("0", 0.2).put("1", 0.8)))
        assertEquals(listOf("1", "0"), score.map { it.label })
        assertTrue(Decisions.isP(0)); assertTrue(Decisions.isP(1.0)); assertFalse(Decisions.isP(Double.NaN))
        assertFalse(Decisions.isP(-0.0001)); assertFalse(Decisions.isP(1.0001)); assertFalse(Decisions.isP("0.5"))
    }

    @Test fun `pick only trusts a choice inside the set`() {
        val a = JSONObject().put("type", "choice").put("choice", "a").put("probabilities", JSONObject().put("a", 0.6).put("b", 0.4))
        assertEquals(Option("a", "a", 0.6), Decisions.pick(a, setOf("a", "b")))
        assertNull(Decisions.pick(a, setOf("b")))
        assertNull(Decisions.pick(JSONObject().put("choice", 3), setOf("3")))
        assertNull(Decisions.pick(null, setOf("a")))
        assertNull(Decisions.pick(JSONObject().put("type", "choice").put("choice", "a").put("probabilities", JSONObject().put("b", 1)), setOf("a", "b")))
    }

    @Test fun `cost estimate, mask and api error`() {
        val req = JSONObject().put("k", "1234567")
        assertEquals(req.toString().length, 15)
        assertEquals(4 * 0.5, Decisions.estimateCost(req, 0.5)!!, 1e-12)
        assertNull(Decisions.estimateCost(req, null))
        assertEquals("sk-or-…cdef", Decisions.mask(fakeKey("v1-0123456789abcdef")))
        assertEquals("—", Decisions.mask(null))
        assertEquals("—", Decisions.mask(""))
        assertEquals("…••••", Decisions.mask("12345678"))
        assertEquals("123456…6789", Decisions.mask("123456789"))
        assertEquals("boom", Decisions.apiError("""{"error":{"message":"boom"}}"""))
        assertEquals("x".repeat(200), Decisions.apiError("x".repeat(300)))
    }

    @Test fun `a decision with no usage reports no cost`() {
        mock.reply(200, """{"answers":{"route":{"type":"choice","choice":"units","probabilities":{"units":0.9}}},"usage":{"cost":"free"}}""")
        val d = route("x").decision
        assertNull(d.cost)
        assertNull(d.inputTokens)
        assertEquals("", d.id)
        mock.reply(200, """{"answers":{"route":{"type":"choice","choice":"units","probabilities":{"units":0.9}}}}""")
        assertNull(route("x").decision.cost)
    }

    // ── the catalogue ───────────────────────────────────────────────────────────────────────

    private val catalogue = """
        {"data":[
          {"id":"typesafe/jev-1.13","name":"TypeSafe: Jev 1.13","context_length":32000,
           "architecture":{"input_modalities":["text"],"output_modalities":["decisions"]},"pricing":{"prompt":"0.000000042","completion":"0"}},
          {"id":"inception/mercury-decide:free","name":"Mercury Decide (free)","context_length":32768,
           "architecture":{"input_modalities":["text"],"output_modalities":["decisions"]},"pricing":{"prompt":"0","completion":"0"}},
          {"id":"respan/span-01-lite","name":"Span Lite","context_length":0,
           "architecture":{"input_modalities":["text","image"],"output_modalities":["decisions"]},"pricing":{"prompt":"0","completion":"0"}},
          {"id":"~typesafe/jev-latest","name":"Jev Latest","architecture":{"output_modalities":["decisions"]},"pricing":{"prompt":"-1"}},
          {"id":"openai/gpt-x","name":"Chat","architecture":{"output_modalities":["text"]},"pricing":{"prompt":"0"}},
          {"id":"odd/no-arch"}
        ]}
    """.trimIndent()

    @Test fun `the catalogue keeps decision models only and reads their fields`() {
        val m = Models.parse(catalogue)
        assertEquals(listOf("typesafe/jev-1.13", "inception/mercury-decide:free", "respan/span-01-lite", "~typesafe/jev-latest"), m.map { it.slug })
        val jev = m[0]
        assertEquals("typesafe", jev.provider)
        assertEquals(0.000000042, jev.promptPrice!!, 1e-18)
        assertEquals(32000, jev.context)
        assertFalse(jev.free)
        assertFalse(jev.images)
        assertTrue(m[1].free)
        assertTrue(m[2].free)
        assertTrue(m[2].images)
        assertEquals("typesafe", m[3].provider)
        assertNull(m[3].promptPrice)
        assertFalse(m[3].free)
        assertEquals(0, m[3].context)
        assertEquals("Jev Latest", m[3].name)
        assertTrue(Models.parse("nonsense").isEmpty())
        assertTrue(Models.parse(null).isEmpty())
        assertTrue(Models.parse("""{"data":[]}""").isEmpty())
    }

    @Test fun `cheapest first prefers free variants among equals, unknown prices last`() {
        val order = Models.cheapestFirst(Models.parse(catalogue)).map { it.slug }
        assertEquals(listOf("inception/mercury-decide:free", "respan/span-01-lite", "typesafe/jev-1.13", "~typesafe/jev-latest"), order)
    }

    @Test fun `resolve trusts a declared slug only when the catalogue confirms it`() {
        val m = Models.parse(catalogue)
        assertEquals("typesafe/jev-1.13", Models.resolve("typesafe/jev-1.13", m, "fb"))
        assertEquals("inception/mercury-decide:free", Models.resolve("retired/model", m, "fb"))
        assertEquals("inception/mercury-decide:free", Models.resolve(JevConfig.CHEAPEST, m, "fb"))
        assertEquals("fb", Models.resolve(JevConfig.CHEAPEST, emptyList(), "fb"))
        assertEquals("retired/model", Models.resolve("retired/model", emptyList(), "fb"))
    }

    @Test fun `prices are quoted per million tokens`() {
        val m = Models.parse(catalogue)
        assertEquals("$0.042/M", Models.perMillion(m[0]))
        assertEquals("$0.000/M", Models.perMillion(m[1]))
        assertEquals("?", Models.perMillion(m[3]))
        assertEquals("?", Models.perMillion(null))
    }

    @Test fun `GET carries the token and no body`() {
        mock.reply(200, """{"data":{"label":"x"}}""")
        val r = UrlHttp.send(mock.url, "tok-123", null, 1000)
        assertEquals(200, r.code)
        assertEquals("""{"data":{"label":"x"}}""", r.body)
        assertEquals("Bearer tok-123", mock.auth.single())
        assertTrue(mock.bodies.isEmpty())
        mock.reply(404, "missing")
        assertEquals(Http.Response(404, "missing"), UrlHttp.send(mock.url, null, null, 1000))
        assertNull(mock.auth.last())
    }

    @Test fun `the decision json carries the request and the answers`() {
        mock.reply(200, routeAnswer("units", mapOf("units" to 0.9)))
        val j = route("x").decision.toJson()
        assertNotNull(j.getJSONObject("request").getJSONObject("questions"))
        assertEquals(0.0000126, j.getDouble("cost"), 1e-12)
        assertEquals(300, j.getInt("input_tokens"))
        assertTrue(j.getJSONObject("answers").has("route"))
        assertEquals("", j.getString("error"))
        val failed = route("x", token = null).decision.toJson()
        assertTrue(failed.isNull("answers"))
        assertTrue(failed.isNull("cost"))
        assertFalse(failed.getBoolean("ok"))
    }
}
