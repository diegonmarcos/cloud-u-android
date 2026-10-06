package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.PageTranslate
import com.diegonmarcos.superapp.browser.PageTranslator
import com.diegonmarcos.superapp.browser.translateChipText
import com.diegonmarcos.superapp.texttools.TextTools
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #886 parts 2 and 3: the engine choice and the in-place translation loop, against a FAKE PAGE (a list of
 * text nodes obeying the same collect/apply/restore protocol as assets/browser/translate_*.js) and a FAKE
 * text-tools port that records which engine was asked.
 */
class PageTranslateTest {

    /** The fleet's text tools as a test double: counts what each engine was asked. */
    private class FakePort(
        var installed: Boolean = true,
        var languages: List<String> = listOf("en", "es", "pt"),
        var llm: (String) -> TextTools.Result = { TextTools.Result.failed("no llm") },
    ) : PageTranslate.TextToolsPort {
        val translateCalls = ArrayList<Pair<String, String>>()
        val llmCalls = ArrayList<Pair<String, String>>()
        override fun installed() = installed
        override fun translate(text: String, tag: String): TextTools.Result { translateCalls.add(text to tag); return TextTools.Result("[$tag]$text", null) }
        override fun translateLanguages() = languages
        override fun enhanceWith(text: String, systemPrompt: String): TextTools.Result { llmCalls.add(text to systemPrompt); return llm(text) }
        override fun summariseWith(text: String, systemPrompt: String) = TextTools.Result.failed("not used")
        override fun providerLabel() = "Fake"
    }

    /** A page of text nodes, with the same claim / apply / restore protocol as the JS. */
    private class FakePage(texts: List<String>, private val perBatch: Int = 3) : PageTranslator.Io {
        val originals = texts.toList()
        val nodes = texts.toMutableList()
        private val claimed = ArrayList<Int>()
        var collects = 0
        var restoreCalls = 0
        val mainQueue = ArrayDeque<() -> Unit>()
        override fun collect(maxChars: Int, done: (String?) -> Unit) {
            collects++
            val next = nodes.indices.filter { it !in claimed }.take(perBatch)
            claimed.addAll(next)
            val items = JSONArray(); next.forEach { items.put(JSONArray().put(it).put(nodes[it])) }
            done(JSONObject().put("items", items).put("remaining", nodes.size - claimed.size).toString())
        }
        override fun apply(mapJson: String, done: (String?) -> Unit) {
            val m = JSONObject(mapJson)
            m.keys().forEach { k -> nodes[k.toInt()] = m.getString(k) }
            done("""{"applied":${m.length()}}""")
        }
        override fun restore(done: (String?) -> Unit) { restoreCalls++; nodes.clear(); nodes.addAll(originals); claimed.clear(); done("""{"restored":1}""") }
        override fun background(work: () -> Unit) = work()
        override fun main(work: () -> Unit) = work()
    }

    private fun seg(i: Int, t: String) = PageTranslate.Seg(i, t)

    // ── protocol helpers ──

    @Test
    fun `collect answers are read, blanks and garbage dropped`() {
        val c = PageTranslate.parseCollect("""{"items":[[0,"Hello"],[1,"  "],[2,"World"]],"remaining":5}""")
        assertEquals(listOf(seg(0, "Hello"), seg(2, "World")), c.items)
        assertEquals(5, c.remaining)
        assertTrue(PageTranslate.parseCollect(null).items.isEmpty())
        assertTrue(PageTranslate.parseCollect("not json").items.isEmpty())
    }

    @Test
    fun `batches respect the character and item limits and keep order`() {
        val segs = (0 until 10).map { seg(it, "x".repeat(10)) }
        val b = PageTranslate.batches(segs, maxChars = 35, maxItems = 100)
        assertEquals(listOf(3, 3, 3, 1), b.map { it.size })
        assertEquals((0 until 10).toList(), b.flatten().map { it.idx })
        assertEquals(listOf(2, 2, 2, 2, 2), PageTranslate.batches(segs, 1000, 2).map { it.size })
        assertEquals("one oversize segment still travels", 1, PageTranslate.batches(listOf(seg(0, "x".repeat(500))), 10, 5).size)
    }

    @Test
    fun `the target follows the phone unless one is chosen`() {
        assertEquals("pt", PageTranslate.targetTag("device", "pt"))
        assertEquals("pt", PageTranslate.targetTag(null, "pt"))
        assertEquals("de", PageTranslate.targetTag("de", "pt"))
        assertEquals("en", PageTranslate.targetTag("device", ""))
        assertEquals("Spanish", PageTranslate.languageName("es"))
        assertEquals("Portuguese", PageTranslate.languageName("pt-BR"))
    }

    @Test
    fun `an LLM reply is read only when it has exactly the right number of strings`() {
        assertEquals(listOf("a", "b"), PageTranslate.parseLlm("""["a","b"]""", 2))
        assertEquals("a fence and a sentence around it are tolerated", listOf("a", "b"), PageTranslate.parseLlm("Here you go:\n```json\n[\"a\",\"b\"]\n```", 2))
        assertNull("wrong count is refused, never guessed", PageTranslate.parseLlm("""["a"]""", 2))
        assertNull(PageTranslate.parseLlm("no array here", 1))
        assertNull(PageTranslate.parseLlm(null, 1))
    }

    @Test
    fun `the apply map is index to text`() {
        val m = JSONObject(PageTranslate.applyMap(mapOf(3 to "tres", 7 to "siete \"x\"")))
        assertEquals("tres", m.getString("3")); assertEquals("siete \"x\"", m.getString("7"))
    }

    // ── the two engines, kept apart ──

    @Test
    fun `on-device ML translates through the translate call and never touches the LLM`() {
        val port = FakePort()
        val out = PageTranslate.backend(PageTranslate.ON_DEVICE, port).translate(listOf(seg(0, "Hello"), seg(1, "World")), "es")
        assertEquals(mapOf(0 to "[es]Hello", 1 to "[es]World"), out.results)
        assertEquals(2, port.translateCalls.size)
        assertTrue("on-device must never be billed to the LLM provider", port.llmCalls.isEmpty())
    }

    @Test
    fun `OpenRouter translates through the model call with a translation prompt and never uses the translator`() {
        val port = FakePort(llm = { TextTools.Result("""["Hola","Mundo"]""", null) })
        val out = PageTranslate.backend(PageTranslate.OPENROUTER, port).translate(listOf(seg(4, "Hello"), seg(5, "World")), "es")
        assertEquals(mapOf(4 to "Hola", 5 to "Mundo"), out.results)
        assertEquals(1, port.llmCalls.size)
        assertEquals("""["Hello","World"]""", port.llmCalls[0].first)
        assertTrue(port.llmCalls[0].second.contains("Spanish"))
        assertTrue("an LLM translation is never routed through the translator", port.translateCalls.isEmpty())
    }

    @Test
    fun `a model reply with the wrong count is split and asked again, down to single strings`() {
        // answers a 4-batch with 3, a 2-batch correctly
        val port = FakePort(llm = { text ->
            val n = JSONArray(text).length()
            if (n == 4) TextTools.Result("""["1","2","3"]""", null)
            else TextTools.Result(JSONArray((0 until n).map { "t$it" }).toString(), null)
        })
        val segs = (0 until 4).map { seg(it, "s$it") }
        val out = PageTranslate.backend(PageTranslate.OPENROUTER, port).translate(segs, "en")
        assertEquals(setOf(0, 1, 2, 3), out.results.keys)
        assertNull(out.error)
        assertEquals(3, port.llmCalls.size)
    }

    @Test
    fun `no serving app, or a language the on-device engine lacks, is an error in words with nothing translated`() {
        val none = PageTranslate.backend(PageTranslate.ON_DEVICE, FakePort(installed = false)).translate(listOf(seg(0, "x")), "es")
        assertEquals(TextTools.NOT_INSTALLED, none.error); assertTrue(none.results.isEmpty())
        val lang = PageTranslate.backend(PageTranslate.ON_DEVICE, FakePort()).translate(listOf(seg(0, "x")), "ja")
        assertTrue(lang.error!!.contains("Japanese")); assertTrue(lang.results.isEmpty())
        val llm = PageTranslate.backend(PageTranslate.OPENROUTER, FakePort(llm = { TextTools.Result.failed("quota exceeded") })).translate(listOf(seg(0, "x")), "es")
        assertEquals("quota exceeded", llm.error)
    }

    @Test
    fun `an unknown engine setting reads as on-device`() {
        assertTrue(PageTranslate.backend("whatever", FakePort()) is PageTranslate.OnDeviceBackend)
        assertTrue(PageTranslate.backend(null, FakePort()) is PageTranslate.OnDeviceBackend)
    }

    // ── the loop ──

    @Test
    fun `a long page is translated in progressive batches until nothing is left`() {
        val page = FakePage((0 until 8).map { "line $it" }, perBatch = 3)
        val states = ArrayList<PageTranslator.State>()
        val t = PageTranslator(page) { states.add(it) }
        t.start(PageTranslate.backend(PageTranslate.ON_DEVICE, FakePort()), "es")
        assertEquals((0 until 8).map { "[es]line $it" }, page.nodes)
        assertTrue(t.active)
        assertEquals(PageTranslator.State.Done(8), t.state)
        assertTrue("several batches, not one request", page.collects >= 3)
        assertTrue(states.filterIsInstance<PageTranslator.State.Working>().size >= 3)
    }

    @Test
    fun `toggling off puts every original back`() {
        val page = FakePage(listOf("a", "b", "c"))
        val t = PageTranslator(page) { }
        t.start(PageTranslate.backend(PageTranslate.ON_DEVICE, FakePort()), "es")
        assertEquals(listOf("[es]a", "[es]b", "[es]c"), page.nodes)
        t.restore()
        assertEquals(listOf("a", "b", "c"), page.nodes)
        assertEquals(PageTranslator.State.Idle, t.state)
        assertTrue(!t.active)
    }

    @Test
    fun `an engine failure with nothing translated leaves the page untouched and says why`() {
        val page = FakePage(listOf("a", "b"))
        val t = PageTranslator(page) { }
        t.start(PageTranslate.backend(PageTranslate.OPENROUTER, FakePort(llm = { TextTools.Result.failed("offline") })), "es")
        assertEquals(listOf("a", "b"), page.nodes)
        assertEquals(PageTranslator.State.Failed("offline", 0), t.state)
        assertTrue(!t.active)
    }

    @Test
    fun `a failure after some batches keeps what was translated and offers the way back`() {
        val page = FakePage((0 until 6).map { "n$it" }, perBatch = 3)
        var calls = 0
        val port = FakePort(llm = { text ->
            calls++
            if (calls == 1) TextTools.Result(JSONArray((0 until JSONArray(text).length()).map { "T$it" }).toString(), null) else TextTools.Result.failed("rate limited")
        })
        val t = PageTranslator(page) { }
        t.start(PageTranslate.backend(PageTranslate.OPENROUTER, port), "es")
        assertEquals(PageTranslator.State.Failed("rate limited", 3), t.state)
        assertEquals(listOf("T0", "T1", "T2", "n3", "n4", "n5"), page.nodes)
        assertTrue("restore is still possible", t.active)
        t.restore()
        assertEquals((0 until 6).map { "n$it" }, page.nodes)
    }

    @Test
    fun `new content is translated by more() and a navigation abandons the job`() {
        val page = FakePage(listOf("a", "b"))
        val t = PageTranslator(page) { }
        t.start(PageTranslate.backend(PageTranslate.ON_DEVICE, FakePort()), "es")
        page.nodes.add("late"); // content that arrived after the first pass
        t.more()
        assertEquals("[es]late", page.nodes[2])
        t.abandon()
        assertTrue(!t.active)
        page.nodes.add("after"); t.more()
        assertEquals("nothing happens after abandon", "after", page.nodes[3])
    }

    @Test
    fun `a batch that comes back after the user toggled off is dropped`() {
        val fake = FakePage(listOf("a", "b"))
        val deferred = ArrayList<() -> Unit>()
        val io = object : PageTranslator.Io by fake {
            override fun background(work: () -> Unit) { deferred.add(work) }
        }
        val t = PageTranslator(io) { }
        t.start(PageTranslate.backend(PageTranslate.ON_DEVICE, FakePort()), "es")
        t.restore()                       // user toggled off while the batch was out
        deferred.forEach { it() }         // …the batch now returns
        assertEquals("the stale batch must not be written", listOf("a", "b"), fake.nodes)
        assertEquals(PageTranslator.State.Idle, t.state)
    }

    @Test
    fun `the chip says what is happening`() {
        assertNull(translateChipText(PageTranslator.State.Idle, "X"))
        assertTrue(translateChipText(PageTranslator.State.Working(2, 5), "On-device ML")!!.contains("Translating"))
        assertTrue(translateChipText(PageTranslator.State.Done(9), "On-device ML")!!.contains("original"))
        assertTrue(translateChipText(PageTranslator.State.Failed("offline", 0), "X")!!.contains("offline"))
    }
}
