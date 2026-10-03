package com.diegonmarcos.cloudsearch.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #802 I9 the tool-calling shape of the chat protocol (Cloud Browser's agent uses it). */
class ChatToolsTest {
    private val read = Chat.ToolSpec("read_page", "Read the page", JSONObject().put("type", "object").put("properties", JSONObject()))

    @Test fun bodyDeclaresToolsAsFunctions() {
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", "hi"))
        val o = JSONObject(Chat.toolsBody("m/x", msgs, listOf(read)))
        assertEquals("m/x", o.getString("model"))
        assertEquals("hi", o.getJSONArray("messages").getJSONObject(0).getString("content"))
        val f = o.getJSONArray("tools").getJSONObject(0)
        assertEquals("function", f.getString("type"))
        assertEquals("read_page", f.getJSONObject("function").getString("name"))
        assertEquals("Read the page", f.getJSONObject("function").getString("description"))
        assertEquals("object", f.getJSONObject("function").getJSONObject("parameters").getString("type"))
        assertEquals("auto", o.getString("tool_choice"))
    }

    @Test fun noToolsMeansNoToolKeys() {
        val o = JSONObject(Chat.toolsBody("m", JSONArray(), emptyList()))
        assertFalse(o.has("tools"))
        assertFalse(o.has("tool_choice"))
    }

    @Test fun replyParsesToolCallsInOrder() {
        val r = Chat.reply(Fixtures.text("openrouter-tool-call.json"))
        assertNull(r.error)
        assertEquals(listOf("read_page", "click"), r.toolCalls.map { it.name })
        assertEquals(listOf("call_1", "call_2"), r.toolCalls.map { it.id })
        assertEquals("a.first", JSONObject(r.toolCalls[1].arguments).getString("css"))
        assertEquals("{}", r.toolCalls[0].arguments)
    }

    @Test fun aPlainReplyHasNoToolCalls() {
        val r = Chat.reply("""{"choices":[{"message":{"role":"assistant","content":"hello"}}]}""")
        assertEquals("hello", r.text)
        assertTrue(r.toolCalls.isEmpty())
    }

    @Test fun aCallWithoutAFunctionOrNameIsDropped() {
        val r = Chat.reply("""{"choices":[{"message":{"tool_calls":[{"id":"a"},{"id":"b","function":{"arguments":"{}"}},{"id":"c","function":{"name":"x","arguments":""}}]}}]}""")
        assertEquals(listOf("x"), r.toolCalls.map { it.name })
        assertEquals("{}", r.toolCalls[0].arguments)
    }
}
