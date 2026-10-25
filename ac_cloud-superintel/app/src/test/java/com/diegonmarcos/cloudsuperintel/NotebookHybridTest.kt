package com.diegonmarcos.cloudsuperintel

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1179 retrieval with the fleet embeddings + rerank service, and the BM25 fallback when it does not answer. */
class NotebookHybridTest {
    private val cfg = NotebookConfig.parse(Data.asset("notebook.json"))
    private val cats = Source("s1", "Cats", "note", "Cats sleep for about fifteen hours a day. A cat's whiskers sense air movement and help it judge gaps.")
    private val tax = Source("s2", "Taxes", "note", "Freelancers in the example country file quarterly estimated income tax by the fifteenth of the month after each quarter.")
    private val sources = listOf(cats, tax)
    private val never: (String, String) -> String = { _, _ -> error("the model was called for a question the sources cannot answer") }

    /** Embeddings: one axis for sleep/felines, the other for everything else. The reranker scores a sleep passage high. */
    private class Fake(val embedOk: Boolean = true, val rerankOk: Boolean = true) : Ranker {
        private fun sleepy(t: String) = t.contains("sleep") || t.contains("felines")
        override fun embed(texts: List<String>): List<DoubleArray>? =
            if (!embedOk) null else texts.map { if (sleepy(it)) doubleArrayOf(1.0, 0.0) else doubleArrayOf(0.0, 1.0) }
        override fun rerank(query: String, docs: List<String>): List<Double>? =
            if (!rerankOk) null else docs.map { if (sleepy(it) && sleepy(query)) 0.9 else 0.01 }
    }

    private val paraphrase = "How much rest do felines need?"

    @Test fun aParaphraseBm25MissesIsFoundWithTheService() {
        val ps = Notebook.passages(sources, cfg)
        assertTrue("no shared term, so BM25 alone finds nothing", Notebook.retrieve(paraphrase, ps, cfg).isEmpty())
        val found = Notebook.retrieveHybrid(paraphrase, ps, cfg, Fake())
        assertEquals(listOf(1), found.map { it.first.n })
        val g = Notebook.answerWith(paraphrase, sources, cfg, Fake()) { _, _ -> "About fifteen hours a day [1]." }
        assertTrue(g.grounded)
        assertEquals(listOf(1), g.cited.map { it.n })
    }

    @Test fun anOutOfScopeQuestionStillFindsNothingAndTheModelIsNotCalled() {
        val g = Notebook.answerWith("What is the capital of France?", sources, cfg, Fake(), never)
        assertFalse(g.grounded)
        assertEquals(cfg.refusal, g.answer)
    }

    @Test fun withEmbeddingsOnlyTheCosineFloorDecides() {
        val found = Notebook.retrieveHybrid(paraphrase, Notebook.passages(sources, cfg), cfg, Fake(rerankOk = false))
        assertEquals(listOf(1), found.map { it.first.n })
    }

    @Test fun whenTheServiceIsDownBm25StillAnswers() {
        val g = Notebook.answerWith("How many hours do cats sleep?", sources, cfg, Fake(embedOk = false)) { _, _ -> "About fifteen hours [1]." }
        assertTrue(g.grounded)
        assertFalse(Notebook.answerWith(paraphrase, sources, cfg, Fake(embedOk = false), never).grounded)
    }

    @Test fun noRankerIsPlainBm25() {
        val ps = Notebook.passages(sources, cfg)
        assertEquals(Notebook.retrieve("quarterly income tax freelancers", ps, cfg), Notebook.retrieveHybrid("quarterly income tax freelancers", ps, cfg, null))
    }

    @Test fun theServiceIsDeclaredWithItsFloors() {
        val s = cfg.semantic!!
        assertEquals("BAAI/bge-m3", s.embedModel)
        assertEquals("BAAI/bge-reranker-v2-m3", s.rerankModel)
        assertTrue(s.minRerank > 0.0 && s.minCosine > 0.0 && s.candidates >= cfg.topK)
    }
}

class FleetRankTest {
    private val sem = NotebookConfig.parse(Data.asset("notebook.json")).semantic!!

    private class Http(val reply: (String, String) -> String?) : FleetPost {
        val calls = ArrayList<String>()
        override fun send(path: String, contentType: String, body: ByteArray, readMs: Int): String? { calls.add(path); return reply(path, String(body)) }
    }

    private fun embedReply(body: String): String {
        val input = JSONObject(body).getJSONArray("input")
        return JSONObject().put("data", org.json.JSONArray((0 until input.length()).map { JSONObject().put("index", it).put("embedding", org.json.JSONArray(listOf(it + 1.0, 0.0))) })).toString()
    }

    @Test fun embeddingsAreKeptPerText() {
        val http = Http { _, b -> embedReply(b) }
        val r = FleetRank(sem, http)
        assertEquals(2, r.embed(listOf("a", "b"))!!.size)
        assertEquals(1, http.calls.size)
        r.embed(listOf("b", "a"))
        assertEquals("a repeat is served from the cache", 1, http.calls.size)
        r.embed(listOf("a", "c"))
        assertEquals(2, http.calls.size)
    }

    @Test fun rerankScoresComeBackInDocumentOrder() {
        val http = Http { _, _ -> """{"results":[{"index":1,"relevance_score":0.8},{"index":0,"relevance_score":0.1}]}""" }
        assertEquals(listOf(0.1, 0.8), FleetRank(sem, http).rerank("q", listOf("d0", "d1")))
    }

    @Test fun aMalformedReplyIsNull() {
        assertNull(FleetRank(sem, Http { _, _ -> """{"results":[{"index":5,"relevance_score":0.8}]}""" }).rerank("q", listOf("d0")))
        assertNull(FleetRank(sem, Http { _, _ -> "not json" }).embed(listOf("a")))
    }

    @Test fun aFailedServiceIsLeftAloneUntilTheRetryWindowPasses() {
        var t = 1_000L
        val http = Http { _, _ -> null }
        val r = FleetRank(sem, http) { t }
        assertNull(r.embed(listOf("a")))
        assertNull(r.embed(listOf("a")))
        assertEquals("the second question did not wait on the dead service", 1, http.calls.size)
        t += sem.retryAfterMs + 1
        assertNull(r.rerank("q", listOf("d")))
        assertEquals(2, http.calls.size)
    }

    @Test fun pdfTextIsReadFromTheReply() {
        assertEquals("Hello fleet", FleetRank(sem, Http { p, _ -> if (p == "/pdf") """{"pages":1,"text":"Hello fleet","truncated":false}""" else null }).pdfText(byteArrayOf(1)))
        assertNull(FleetRank(sem, Http { _, _ -> null }).pdfText(byteArrayOf(1)))
        assertNull(FleetRank(sem, Http { _, _ -> """{"pages":1,"text":"  ","truncated":false}""" }).pdfText(byteArrayOf(1)))
    }
}
