package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.PageTopics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #886 part 4: Summarise by topics - the model route's parsing and the on-device outline with its honest note. */
class PageTopicsTest {

    private val pageJson = """{"title":"Coffee","lang":"en","sections":[
        {"h":"","level":0,"text":"Coffee is one of the most widely consumed drinks in the world, enjoyed by billions every day."},
        {"h":"History","level":2,"text":"Coffee was first cultivated in Ethiopia and spread across the Arabian peninsula in the fifteenth century. Trade then carried it to Europe."},
        {"h":"Brewing","level":2,"text":"Espresso forces hot water through finely ground beans under pressure. Filter coffee drips water slowly over coarser grounds."},
        {"h":"Empty","level":2,"text":""}
    ]}"""

    @Test
    fun `the page's sections are read, an unreadable answer is null`() {
        val p = PageTopics.parsePage(pageJson)!!
        assertEquals("Coffee", p.title)
        assertEquals(listOf("", "History", "Brewing", "Empty"), p.sections.map { it.heading })
        assertNull(PageTopics.parsePage("nope"))
        assertNull(PageTopics.parsePage("""{"sections":[]}"""))
    }

    @Test
    fun `the model input carries the title and the headings, cut at the cap`() {
        val p = PageTopics.parsePage(pageJson)!!
        val full = PageTopics.llmInput(p, 10_000)
        assertTrue(full.startsWith("# Coffee"))
        assertTrue(full.contains("## History"))
        assertEquals(100, PageTopics.llmInput(p, 100).length)
    }

    @Test
    fun `the prompt names the language and the JSON shape`() {
        val pr = PageTopics.prompt("Portuguese")
        assertTrue(pr.contains("Portuguese")); assertTrue(pr.contains("\"topics\"")); assertTrue(pr.contains("points"))
    }

    @Test
    fun `a model reply becomes topics - plain, fenced, or not JSON at all`() {
        val json = """{"topics":[{"title":"History","points":["Began in Ethiopia","Reached Europe"]},{"title":"Brewing","points":["Espresso","Filter"]}]}"""
        val t = PageTopics.parseTopics(json)
        assertEquals(listOf("History", "Brewing"), t.map { it.title })
        assertEquals(listOf("Began in Ethiopia", "Reached Europe"), t[0].points)
        assertEquals(2, PageTopics.parseTopics("```json\n$json\n```").size)
        val prose = PageTopics.parseTopics("- one thing\n- another thing")
        assertEquals("Summary", prose.single().title)
        assertEquals(listOf("one thing", "another thing"), prose.single().points)
        assertTrue(PageTopics.parseTopics("").isEmpty())
        assertTrue(PageTopics.parseTopics(null).isEmpty())
    }

    @Test
    fun `without a model the outline is the page's own headings with their key sentences`() {
        val o = PageTopics.outline(PageTopics.parsePage(pageJson)!!)
        assertEquals(listOf("Introduction", "History", "Brewing"), o.map { it.title })
        assertTrue(o.all { it.points.isNotEmpty() })
        assertTrue("a heading with no text under it is not a topic", o.none { it.title == "Empty" })
        assertTrue(o[1].points.any { it.contains("Ethiopia") })
    }

    @Test
    fun `the on-device note says in words that ML cannot summarise and where to go`() {
        assertTrue(PageTopics.ON_DEVICE_NOTE.contains("cannot write a summary"))
        assertTrue(PageTopics.ON_DEVICE_NOTE.contains("OpenRouter"))
        assertNotNull(PageTopics.asText(PageTopics.outline(PageTopics.parsePage(pageJson)!!)))
    }
}
