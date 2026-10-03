package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.PageSummary
import com.diegonmarcos.superapp.browser.PageSummary.Result
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #823 Summarize the page: the Model / on-device rule of #799/#800, and the extractive engine. */
class BrowserSummaryTest {

    private var deviceRuns = 0
    private var modelRuns = 0
    private val device = { deviceRuns++; Result(true, "• local", PageSummary.ON_DEVICE, PageSummary.ENGINE_EXTRACTIVE, PageSummary.ON_DEVICE) }
    private val deviceFails = { deviceRuns++; Result.failed(PageSummary.ON_DEVICE, PageSummary.ENGINE_EXTRACTIVE, "no sentences") }
    private val model = { modelRuns++; Result(true, "• remote", PageSummary.MODEL, "openrouter:x", PageSummary.MODEL) }
    private val modelFails = { modelRuns++; Result.failed(PageSummary.MODEL, PageSummary.ENGINE_MODEL, "HTTP 500") }

    @Test
    fun `the model route answers and the phone is not asked`() {
        val r = PageSummary.routed(PageSummary.MODEL, true, null, true, device, model)
        assertEquals("• remote", r.summary); assertEquals(PageSummary.MODEL, r.route); assertFalse(r.fellBack)
        assertEquals(0, deviceRuns)
    }

    @Test
    fun `offline the model is not even attempted, and on device stands in saying why`() {
        val r = PageSummary.routed(PageSummary.MODEL, false, null, true, device, model)
        assertEquals(0, modelRuns)
        assertTrue(r.ok); assertTrue(r.fellBack)
        assertEquals(PageSummary.ON_DEVICE, r.route); assertEquals(PageSummary.MODEL, r.requested)
        assertEquals(PageSummary.OFFLINE, r.reason)
    }

    @Test
    fun `a failing model falls back with its own error as the reason`() {
        val r = PageSummary.routed(PageSummary.MODEL, true, true, true, device, modelFails)
        assertTrue(r.fellBack); assertEquals("HTTP 500", r.reason); assertEquals("• local", r.summary)
    }

    @Test
    fun `a throwing model is a failure, not a crash`() {
        val r = PageSummary.routed(PageSummary.MODEL, null, null, true, device) { throw IllegalStateException("boom") }
        assertTrue(r.fellBack); assertEquals("boom", r.reason)
    }

    @Test
    fun `without fallback the model's failure is the answer`() {
        val r = PageSummary.routed(PageSummary.MODEL, true, true, false, device, modelFails)
        assertFalse(r.ok); assertEquals(0, deviceRuns); assertEquals("HTTP 500", r.error)
    }

    @Test
    fun `when both fail the model's sentence comes first`() {
        val r = PageSummary.routed(PageSummary.MODEL, true, true, true, deviceFails, modelFails)
        assertFalse(r.ok); assertTrue(r.error!!.startsWith("HTTP 500; on device: no sentences"))
    }

    @Test
    fun `on device never reaches the model`() {
        val r = PageSummary.routed(PageSummary.ON_DEVICE, true, true, true, device, model)
        assertEquals(0, modelRuns); assertEquals(PageSummary.ON_DEVICE, r.requested); assertFalse(r.fellBack)
    }

    @Test
    fun `an unknown route reads as the default`() {
        assertEquals(PageSummary.ON_DEVICE, PageSummary.route("bogus", PageSummary.ON_DEVICE))
        assertEquals(PageSummary.MODEL, PageSummary.route(null, "bogus"))
        assertEquals(PageSummary.ON_DEVICE, PageSummary.route("on_device", PageSummary.MODEL))
    }

    @Test
    fun `the extractive summary keeps the page's central sentences, in page order, and drops the chrome`() {
        val page = """
            Home
            Sign in
            Berlin is the capital of Germany and its largest city by population.
            The weather today is sunny in many places around the world.
            Berlin has a population of about 3.7 million people living in the city.
            Cookies help us deliver our services to you on this site.
            As the capital, Berlin hosts the German parliament and the federal government of Germany.
        """.trimIndent()
        val s = PageSummary.extractive(page, 2)
        val lines = s.lines()
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.startsWith("• ") })
        assertTrue(s, lines.all { "Berlin" in it })
        assertFalse(s.contains("Sign in"))
        assertTrue(page.indexOf(lines[0].drop(2)) < page.indexOf(lines[1].drop(2)))
        assertEquals("", PageSummary.extractive("Menu\nLogin", 3))
    }

    @Test
    fun `the answer names route, engine and fallback`() {
        val j = Result(true, "x", PageSummary.ON_DEVICE, PageSummary.ENGINE_MLKIT, PageSummary.MODEL, true, PageSummary.OFFLINE).json()
        assertEquals("on_device", j.getString("route")); assertEquals("mlkit_genai", j.getString("engine"))
        assertTrue(j.getBoolean("fell_back")); assertEquals("model", j.getString("requested"))
        assertTrue(PageSummary.credit(j).contains("ML Kit") && PageSummary.credit(j).contains(PageSummary.OFFLINE))
    }

    @Test
    fun `the declared summarize block and setting agree with the code`() {
        val b = JSONObject(File("../build.json").readText()).getJSONObject("ui").getJSONObject("browser")
        val ai = b.getJSONArray("addons").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("id") == "ai" } }
        val sum = ai.getJSONObject("summarize")
        assertTrue(sum.getString("default_route") in PageSummary.ROUTES)
        val set = b.getJSONArray("settings").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("key") == "summarize_route" } }
        val vals = set.getJSONArray("values").let { v -> (0 until v.length()).map { v.getString(it) } }
        assertEquals(PageSummary.ROUTES, vals)
        assertEquals(sum.getString("default_route"), set.getString("default"))
    }
}
