package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.AgentLoop
import com.diegonmarcos.superapp.browser.AgentPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #802 I9 the agent asks before it acts, stops at its cap, and never writes a secret field. */
class BrowserAgentTest {

    private val declared: JSONArray = JSONObject(File("../build.json").readText()).getJSONObject("ui").getJSONObject("browser")
        .getJSONArray("addons").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("id") == "ai" } }
        .getJSONArray("tools")
    private val tools = AgentLoop.parseTools(declared)

    /** A fake model: answers each turn from [script] in order, recording what it was shown. */
    private class Model(vararg val script: AgentLoop.ModelTurn) {
        var turns = 0
        val seen = ArrayList<String>()
        fun call(m: JSONArray): AgentLoop.ModelTurn { seen.add(m.toString()); return script[minOf(turns++, script.size - 1)] }
    }

    private fun call(id: String, name: String, args: String = "{}") = AgentLoop.Call(id, name, JSONObject(args))
    private fun turn(vararg c: AgentLoop.Call) = AgentLoop.ModelTurn(null, c.toList())
    private fun words(t: String) = AgentLoop.ModelTurn(t, emptyList())

    @Test
    fun `the shipped tools parse, and every mutating one asks first`() {
        assertTrue(tools.size >= 10)
        tools.forEach { assertTrue("${it.id} mutating ⇒ confirm", !it.mutating || it.confirm) }
        assertTrue(tools.first { it.id == "click" }.mutating)
        assertFalse(tools.first { it.id == "read_page" }.mutating)
    }

    @Test
    fun `a reading tool runs without asking, and its result goes back to the model`() {
        val ran = ArrayList<String>()
        val m = Model(turn(call("1", "read_page")), words("It is about Berlin."))
        val loop = AgentLoop(tools, 6).apply { user("summarize") }
        val out = loop.step(m::call, { ran.add(it.name); "Berlin article" }, { "https://en.wikipedia.org/wiki/Berlin" })
        assertEquals(AgentLoop.Outcome.Answer("It is about Berlin."), out)
        assertEquals(listOf("read_page"), ran)
        assertTrue(m.seen[1].contains("Berlin article"))
    }

    @Test
    fun `a mutating tool waits for him, a denied one never executes and the model is told`() {
        val ran = ArrayList<String>()
        val m = Model(turn(call("c1", "click", """{"css":"a.first"}""")), words("Ok, I did not click."))
        val loop = AgentLoop(tools, 6).apply { user("click the first link") }
        val p = loop.step(m::call, { ran.add(it.name); "clicked" }, { "https://shop.example/x" })
        assertTrue(p is AgentLoop.Outcome.Pending)
        p as AgentLoop.Outcome.Pending
        assertTrue(p.sentence, p.sentence.contains("a.first") && p.sentence.contains("shop.example"))
        assertEquals("c1", loop.pending?.id)
        assertTrue(ran.isEmpty())
        loop.decide("c1", AgentLoop.Decision.DENY)
        val a = loop.step(m::call, { ran.add(it.name); "clicked" }, { "https://shop.example/x" })
        assertEquals(AgentLoop.Outcome.Answer("Ok, I did not click."), a)
        assertTrue("a denied tool never runs", ran.isEmpty())
        assertTrue(m.seen.last().contains("denied"))
        assertNull(loop.pending)
    }

    @Test
    fun `an allowed tool runs once`() {
        val ran = ArrayList<String>()
        val m = Model(turn(call("c1", "click", """{"css":"a"}""")), words("Done."))
        val loop = AgentLoop(tools, 6).apply { user("click") }
        loop.step(m::call, { ran.add(it.name); "ok" }, { null })
        loop.decide("c1", AgentLoop.Decision.ALLOW)
        assertEquals(AgentLoop.Outcome.Answer("Done."), loop.step(m::call, { ran.add(it.name); "ok" }, { null }))
        assertEquals(listOf("click"), ran)
    }

    @Test
    fun `a model that keeps calling stops at the cap`() {
        var n = 0
        val m = Model(turn(call("r", "list_tabs")))
        val loop = AgentLoop(tools, 3).apply { user("loop") }
        val out = loop.step(m::call, { n++; "tabs" }, { null })
        assertTrue(out is AgentLoop.Outcome.Answer && out.text.contains("limit"))
        assertEquals(3, n)
    }

    @Test
    fun `an unknown tool and a model error are answered, not executed`() {
        val m = Model(turn(call("u", "rm_rf")), words("sorry"))
        val loop = AgentLoop(tools, 6).apply { user("x") }
        assertEquals(AgentLoop.Outcome.Answer("sorry"), loop.step(m::call, { error("must not run") }, { null }))
        assertTrue(m.seen.last().contains("no tool named rm_rf"))
        val failing = AgentLoop(tools, 6).apply { user("x") }
        assertEquals(AgentLoop.Outcome.Failed("no openrouter token in the fleet Account"),
            failing.step({ AgentLoop.ModelTurn(null, emptyList(), "no openrouter token in the fleet Account") }, { error("no") }, { null }))
    }

    @Test
    fun `passwords and card fields are refused before any confirmation`() {
        val m = Model(turn(call("f", "fill_form", """{"fields":{"input[name=password]":"x"}}""")), words("I can't fill passwords."))
        val loop = AgentLoop(tools, 6).apply { user("fill the password") }
        assertEquals(AgentLoop.Outcome.Answer("I can't fill passwords."), loop.step(m::call, { error("must not run") }, { null }))
        assertTrue(m.seen.last().contains("refused"))
        for (k in listOf("#cc-number", "input[autocomplete=cc-csc]", "#cvv", "#card_no", "#pwd", "[name=iban]"))
            assertNotNull(k, AgentPolicy.refuse("fill_form", JSONObject().put("fields", JSONObject().put(k, "1"))))
        assertNull(AgentPolicy.refuse("fill_form", JSONObject().put("fields", JSONObject().put("#email", "a@b.c"))))
        assertNotNull(AgentPolicy.refuse("navigate", JSONObject().put("url", "javascript:alert(1)")))
        assertNull(AgentPolicy.refuse("navigate", JSONObject().put("url", "https://example.org")))
    }

    @Test
    fun `leaving the site is named in the confirmation`() {
        assertFalse(AgentPolicy.leavesSite("https://a.example/x", "https://a.example/y"))
        assertTrue(AgentPolicy.leavesSite("https://a.example/x", "https://evil.example/"))
        val nav = tools.first { it.id == "navigate" }
        val s = AgentPolicy.describe(nav, JSONObject().put("url", "https://evil.example/"), "https://a.example/x")
        assertTrue(s, s.contains("leave a.example for evil.example"))
    }
}
