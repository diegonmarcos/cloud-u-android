package com.diegonmarcos.superapp.cgc

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Describe your avatar": the request, the answer to configuration mapping and the key's handling, over a mocked HTTP. */
class OpenRouterAvatarTest {
    private val spec = Fixture.spec
    private val key = "sk-or-test-SECRET"

    private class FakeHttp(val code: Int = 200, val body: String = "") : HttpPort {
        var url = ""; var headers = emptyMap<String, String>(); var sent = ""; var calls = 0
        override fun post(url: String, headers: Map<String, String>, body: String): HttpReply {
            calls++; this.url = url; this.headers = headers; sent = body
            return HttpReply(code, this.body)
        }
    }

    private fun reply(content: String) = JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
        JSONObject().put("role", "assistant").put("content", content)))).toString()

    private fun applied(r: AiResult) = r as AiResult.Applied

    @Test fun theRequestGoesToTheDeclaredEndpointWithTheKeyInTheHeaderOnly() {
        val http = FakeHttp(body = reply("{\"top\": \"forest\"}"))
        val r = applied(OpenRouterAvatar({ if (it == "openrouter") key else null }, http).describe(spec, spec.defaults, "green hoodie"))
        assertEquals(1, http.calls)
        assertEquals("https://example.invalid/v1/chat/completions", http.url)
        assertEquals("Bearer $key", http.headers["Authorization"])
        assertEquals("https://example.invalid/app", http.headers["HTTP-Referer"])
        assertEquals("Test", http.headers["X-Title"])
        assertFalse("the key never travels in the body", http.sent.contains(key))
        val body = JSONObject(http.sent)
        assertEquals("test/model", body.getString("model"))
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        assertEquals("green hoodie", body.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals("forest", r.config["top"])
    }

    @Test fun thePromptListsWiredCategoriesOnly() {
        val sys = OpenRouterAvatar.request(spec, "x").getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(sys.contains("- top (Hoodie): original = Original, navy #22345e = Navy"))
        assertTrue(sys.contains("- anim (Animation): idle-opt = Idle, dance-opt = Dance"))
        assertFalse("soon categories are not offered", sys.contains("- hair"))
        assertFalse(sys.contains("- later"))
    }

    @Test fun noKeyNoRequest() {
        val http = FakeHttp(body = reply("{}"))
        assertEquals(AiResult.NoKey, OpenRouterAvatar({ null }, http).describe(spec, spec.defaults, "x"))
        assertEquals(AiResult.NoKey, OpenRouterAvatar({ "  " }, http).describe(spec, spec.defaults, "x"))
        assertEquals(0, http.calls)
        assertEquals(AiResult.NoEndpoint, OpenRouterAvatar({ key }, http).describe(CgcSpec.parse("{}"), emptyMap(), "x"))
    }

    @Test fun httpAndNetworkErrorsNeverCarryTheKey() {
        val err = OpenRouterAvatar({ key }, FakeHttp(401, "{\"error\":{\"message\":\"No auth credentials found\"}}")).describe(spec, spec.defaults, "x")
        assertEquals(AiResult.Http(401, "No auth credentials found"), err)
        assertEquals(AiResult.Http(500, "request failed"), OpenRouterAvatar({ key }, FakeHttp(500, "<html>$key</html>")).describe(spec, spec.defaults, "x"))
        val boom = OpenRouterAvatar({ key }, { _, _, _ -> throw java.io.IOException("refused for $key") }).describe(spec, spec.defaults, "x")
        assertEquals(AiResult.Network("IOException"), boom)
        assertEquals(AiResult.BadReply("no message content"), OpenRouterAvatar({ key }, FakeHttp(body = "{}")).describe(spec, spec.defaults, "x"))
    }

    @Test fun mapByIdLabelAndCategoryLabel() {
        val r = applied(OpenRouterAvatar.map(spec, spec.defaults, "{\"Animation\": \"dance\", \"glasses\": \"OFF\", \"face\": \"smile\"}"))
        assertEquals("dance-opt", r.config["anim"])
        assertEquals("off", r.config["glasses"])
        assertEquals("smile", r.config["face"])
        assertEquals(mapOf("anim" to "dance-opt", "face" to "smile", "glasses" to "off"), r.applied)
        assertEquals("untouched categories keep their value", "navy", r.config["top"])
    }

    @Test fun aColourPicksTheNearestDeclaredOne() {
        val r = applied(OpenRouterAvatar.map(spec, spec.defaults, "{\"top\": \"#f5f5f0\", \"skin\": \"#dda080\"}"))
        assertEquals("snow", r.config["top"])
        assertEquals("tan", r.config["skin"])
        assertEquals("forest", OpenRouterAvatar.nearest(spec.category("top")!!, "#306040")!!.id)
        assertEquals("navy", OpenRouterAvatar.nearest(spec.category("top")!!, "#123")!!.id)
        assertEquals(null, OpenRouterAvatar.nearest(spec.category("top")!!, "blue"))
        assertEquals("a non-colour category takes no hex", "on",
            applied(OpenRouterAvatar.map(spec, spec.defaults, "{\"glasses\": \"#000000\"}")).config["glasses"])
    }

    @Test fun soonUnknownAndUnmatchedAreIgnoredAndReported() {
        val r = applied(OpenRouterAvatar.map(spec, spec.defaults, "{\"hair\": \"bun\", \"later\": \"white\", \"wings\": \"yes\", \"top\": \"plaid\"}"))
        assertEquals(spec.defaults, r.config)
        assertEquals(listOf("hair", "later", "top", "wings"), r.ignored)
        assertTrue(r.applied.isEmpty())
    }

    @Test fun theObjectMayBeWrappedInProseAFenceOrAKey() {
        val prose = "Sure! Here it is:\n```json\n{\"config\": {\"top\": \"forest\"}}\n```"
        assertEquals("forest", applied(OpenRouterAvatar.map(spec, spec.defaults, prose)).config["top"])
        assertEquals("snow", applied(OpenRouterAvatar.map(spec, spec.defaults, "{\"options\": {\"top\": \"snow\"}}")).config["top"])
        assertEquals(AiResult.BadReply("no JSON object"), OpenRouterAvatar.map(spec, spec.defaults, "I cannot help"))
        assertEquals(AiResult.BadReply("invalid JSON"), OpenRouterAvatar.map(spec, spec.defaults, "{top: }}"))
    }

    @Test fun rgb() {
        assertEquals(listOf(0x22, 0x34, 0x5e), OpenRouterAvatar.rgb("#22345e")!!.toList())
        assertEquals(listOf(0x11, 0x22, 0x33), OpenRouterAvatar.rgb("123")!!.toList())
        assertEquals(null, OpenRouterAvatar.rgb("#12345g"))
    }
}
