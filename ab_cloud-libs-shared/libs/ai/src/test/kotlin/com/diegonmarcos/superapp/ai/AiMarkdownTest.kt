package com.diegonmarcos.superapp.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Markdown reader and the sessions rename / delete the SuperIntel drawer drives (JVM, no UI). */
class AiMarkdownTest {
    @Test fun blocksReadHeadingsListsQuotesAndRules() {
        val b = AiMarkdown.blocks("# Title\n\nHello\nworld\n\n- one\n- two\n  - nested\n1. first\n2) second\n> quoted\n---")
        assertEquals(AiMdBlock.Heading(1, "Title"), b[0])
        assertEquals(AiMdBlock.Paragraph("Hello\nworld"), b[1])
        assertEquals(AiMdBlock.Item("•", "one", 0), b[2])
        assertEquals(AiMdBlock.Item("•", "nested", 1), b[4])
        assertEquals(AiMdBlock.Item("1.", "first", 0), b[5])
        assertEquals(AiMdBlock.Item("2.", "second", 0), b[6])
        assertEquals(AiMdBlock.Quote("quoted"), b[7])
        assertEquals(AiMdBlock.Rule, b[8])
    }

    @Test fun fencedCodeKeepsItsLinesAndAnUnclosedFenceStillShows() {
        assertEquals(listOf<AiMdBlock>(AiMdBlock.Code("kotlin", "val a = 1\n# not a heading")), AiMarkdown.blocks("```kotlin\nval a = 1\n# not a heading\n```"))
        assertEquals(listOf<AiMdBlock>(AiMdBlock.Code("", "streaming")), AiMarkdown.blocks("```\nstreaming"))
    }

    @Test fun inlineFindsBoldItalicCodeAndLinks() {
        val r = AiMarkdown.inline("a **b** *c* `d` [e](http://x) f")
        assertEquals(listOf(AiMdSpan.PLAIN, AiMdSpan.BOLD, AiMdSpan.PLAIN, AiMdSpan.ITALIC, AiMdSpan.PLAIN, AiMdSpan.CODE, AiMdSpan.PLAIN, AiMdSpan.LINK, AiMdSpan.PLAIN), r.map { it.span })
        assertEquals("http://x", r[7].url)
        assertEquals("e", r[7].text)
    }

    @Test fun unmatchedMarkersStayLiteral() {
        assertEquals(listOf(AiMdRun("2 * 3 and **open", AiMdSpan.PLAIN)), AiMarkdown.inline("2 * 3 and **open"))
        assertTrue(AiMarkdown.inline("snake_case_name").all { it.span == AiMdSpan.PLAIN })
    }

    @Test fun renameAndDeleteChangeTheStoreAndTheOpenSession() {
        val store = AiMemorySessions()
        val a = AiSession("a", "old", "m", 2, listOf(AiMsg("user", "hi")))
        val b = AiSession("b", "other", "m", 1, emptyList())
        store.put(a); store.put(b)
        store.rename("a", "new")
        assertEquals("new", store.all().first { it.id == "a" }.title)
        assertEquals(listOf("a", "b"), store.all().map { it.id })
        store.delete("a")
        assertEquals(listOf("b"), store.all().map { it.id })
    }
}
