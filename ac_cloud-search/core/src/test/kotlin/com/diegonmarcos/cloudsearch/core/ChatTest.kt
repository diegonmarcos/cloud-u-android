package com.diegonmarcos.cloudsearch.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ChatTest {
    private val ai = Fixtures.cfg.ai
    private val msgs = (1..30).map { Chat.Msg(if (it % 2 == 1) "user" else "assistant", "m$it") }

    @Test fun bodyKeepsTheDeclaredHistoryAndNoWebByDefault() {
        val o = JSONObject(Chat.body(ai, "openai/gpt-4o-mini", msgs, web = false, nativeWeb = true))
        assertEquals("openai/gpt-4o-mini", o.getString("model"))
        val m = o.getJSONArray("messages")
        assertEquals(ai.historyTurns, m.length())
        assertEquals("m30", m.getJSONObject(m.length() - 1).getString("content"))
        assertFalse(o.has(ai.nativeWebParam))
        assertFalse(o.has("plugins"))
    }

    @Test fun webSearchNativeOrPlugin() {
        assertTrue(JSONObject(Chat.body(ai, "x", msgs, web = true, nativeWeb = true)).has(ai.nativeWebParam))
        val plug = JSONObject(Chat.body(ai, "x", msgs, web = true, nativeWeb = false))
        assertFalse(plug.has(ai.nativeWebParam))
        assertEquals(ai.webPlugin, plug.getJSONArray("plugins").getJSONObject(0).getString("id"))
    }

    @Test fun headersCarryTheTokenOnlyWhereItBelongs() {
        val h = Chat.headers(ai, "sk-test")
        assertEquals("Bearer sk-test", h["Authorization"])
        assertEquals(ai.referer, h["HTTP-Referer"])
        assertEquals(ai.title, h["X-Title"])
    }

    @Test fun replyWithCitations() {
        val r = Chat.reply("""{"model":"openai/gpt-4o-mini","choices":[{"message":{"role":"assistant","content":"Hi","annotations":[
            {"type":"url_citation","url_citation":{"url":"https://a.example"}},{"type":"url_citation","url_citation":{"url":"https://a.example"}},
            {"type":"url_citation","url_citation":{"url":"https://b.example"}}]}}]}""")
        assertEquals("Hi", r.text)
        assertEquals(listOf("https://a.example", "https://b.example"), r.citations)
        assertEquals("openai/gpt-4o-mini", r.model)
        assertNull(r.error)
    }

    @Test fun replyErrors() {
        assertEquals("No auth credentials found", Chat.reply("""{"error":{"message":"No auth credentials found","code":401}}""").error)
        assertEquals("unreadable answer", Chat.reply("<html>").error)
        assertEquals("no choices in the answer", Chat.reply("""{"choices":[]}""").error)
    }

    @Test fun modelsFromTheLiveCatalogue() {
        val m = Chat.models(Fixtures.text("openrouter-models.json"), ai.nativeWebParam).associateBy { it.id }
        assertEquals(4, m.size)
        assertTrue(m.getValue("openrouter/auto").nativeWeb)
        assertTrue(m.getValue("openai/gpt-4o-mini").nativeWeb)
        assertFalse(m.getValue("google/gemini-2.5-flash").nativeWeb)
        assertFalse("price -1 is not free", m.getValue("openrouter/auto").free)
        assertEquals(1048576, m.getValue("google/gemini-2.5-flash").context)
        assertTrue(Chat.models("""{"data":[{"id":"f","name":"F","pricing":{"prompt":"0","completion":"0"}}]}""", ai.nativeWebParam).single().free)
        assertTrue(Chat.models("""{"data":[{"id":"i","architecture":{"output_modalities":["image"]}}]}""", ai.nativeWebParam).isEmpty())
    }

    @Test fun sessionTitles() {
        assertEquals("short", Chat.title("  short ", 40))
        assertEquals("abcde…", Chat.title("abcde fghij", 5))
    }

    @Test fun sessionsGroupTodayPreviousSevenOlder() {
        val zone = ZoneId.of("Europe/Berlin")
        val now = ZonedDateTime.of(2026, 10, 2, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        fun at(d: Int, h: Int) = ZonedDateTime.of(2026, 10, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()
        val s = listOf(
            Chat.Session("a", "a", "m", at(2, 0), emptyList()),
            Chat.Session("b", "b", "m", at(1, 23), emptyList()),
            Chat.Session("c", "c", "m", ZonedDateTime.of(2026, 9, 25, 12, 0, 0, 0, zone).toInstant().toEpochMilli(), emptyList()),
            Chat.Session("d", "d", "m", ZonedDateTime.of(2026, 9, 24, 12, 0, 0, 0, zone).toInstant().toEpochMilli(), emptyList()),
            Chat.Session("e", "e", "m", at(2, 8), emptyList()),
        )
        val g = Chat.group(s, now, zone)
        assertEquals(listOf(Chat.Bucket.TODAY, Chat.Bucket.PREVIOUS_7_DAYS, Chat.Bucket.OLDER), g.map { it.first })
        assertEquals(listOf("e", "a"), g[0].second.map { it.id })
        assertEquals(listOf("b", "c"), g[1].second.map { it.id })
        assertEquals(listOf("d"), g[2].second.map { it.id })
    }

    @Test fun sessionRoundTrip() {
        val s = Chat.Session("id", "t", "m", 5L, listOf(Chat.Msg("user", "q"), Chat.Msg("assistant", "a")))
        assertEquals(s, Chat.sessionFromJson(s.toJson()))
    }
}
