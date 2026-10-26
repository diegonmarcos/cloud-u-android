package com.diegonmarcos.cloudsearch.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * #1184 the code indexes: the declaration, the readers of the cgc MCP service's answers for both modes, and the MCP client.
 * The fixtures (cgc-search.json, cgc-graph-*.json) follow the JSON that octocode 0.22 prints for `search` / `graphrag`
 * (code_blocks / doc_blocks, nodes, relationships); they are written to that shape, not captured from the live service, which
 * the build cannot reach: the readers are tolerant of the field spellings they accept, and say so when an answer is not JSON.
 */
class CgcTest {
    private val cfg get() = requireNotNull(Fixtures.cfg.cgc) { "build.json::search.cgc is declared" }

    @Test fun theShippedDeclarationNamesThePublicAndThePrivateIndex() {
        assertEquals(listOf("cgc", "mecgc"), cfg.indexes.map { it.id })
        val pub = cfg.index("cgc")!!
        val pvt = cfg.index("mecgc")!!
        assertEquals("cgc-pub-mcp", pub.service)
        assertEquals("cgc-pvt-mcp", pvt.service)
        assertFalse(pub.needsAccount)
        assertTrue(pvt.needsAccount)
        assertEquals(listOf("cloud-data", "cloud-me-my-ai"), pvt.repos)
        assertTrue("no private repository is reachable through the public index", pub.repos.none { it in pvt.repos })
        assertNull(cfg.index("ghost"))
        assertTrue(cfg.threshold in 0.0..1.0)
    }

    /** #1197 one CGC section, two rows: each index names its Semantic and Graph RAG page ids, and a page resolves back to (index, mode). */
    @Test fun eachIndexNamesItsTwoPagesInTheCgcSection() {
        assertEquals("cloud_semantic" to "cloud_graph", cfg.index("cgc")!!.let { it.semanticPage to it.graphPage })
        assertEquals("me_semantic" to "me_graph", cfg.index("mecgc")!!.let { it.semanticPage to it.graphPage })
        assertEquals("cgc" to false, cfg.indexOfPage("cloud_semantic")!!.let { it.first.id to it.second })
        assertEquals("cgc" to true, cfg.indexOfPage("cloud_graph")!!.let { it.first.id to it.second })
        assertEquals("mecgc" to false, cfg.indexOfPage("me_semantic")!!.let { it.first.id to it.second })
        assertEquals("mecgc" to true, cfg.indexOfPage("me_graph")!!.let { it.first.id to it.second })
        assertNull(cfg.indexOfPage("semantic"))
    }

    @Test fun aResultOpensOnTheWebAtItsLine() {
        val pub = cfg.index("cgc")!!
        assertEquals("https://github.com/diegonmarcos/cloud-u-android/blob/main/a/B.kt#L12", pub.web("cloud-u-android", "a/B.kt", 12))
        assertEquals("a repo name maps to its GitHub repository", "https://github.com/diegonmarcos/diegonmarcos.github.io/blob/main/x.md#L1", pub.web("front-diegonmarcos", "x.md", null))
    }

    private fun refused(edit: (JSONObject) -> Unit): String {
        val j = Fixtures.searchJson().getJSONObject("cgc")
        edit(j)
        return runCatching { CgcConfig.parse(j) }.exceptionOrNull()?.message ?: ""
    }

    @Test fun aBrokenDeclarationIsRefused() {
        assertTrue(refused { it.getJSONArray("indexes").getJSONObject(1).put("graph_page", "cloud_graph") }.contains("distinct semantic_page and graph_page"))
        assertTrue(refused { it.getJSONArray("indexes").getJSONObject(0).remove("semantic_page") }.contains("distinct semantic_page and graph_page"))
        assertTrue(refused { it.put("indexes", org.json.JSONArray()) }.contains("at least one index"))
        assertTrue(refused { it.getJSONArray("indexes").put(it.getJSONArray("indexes").getJSONObject(0)) }.contains("no repeated id"))
        assertTrue(refused { it.getJSONArray("indexes").getJSONObject(0).put("repos", org.json.JSONArray()) }.contains("need repos"))
        assertTrue(refused { it.getJSONArray("indexes").getJSONObject(0).put("mcp_path", "mcp") }.contains("/mcp_path"))
        assertTrue(refused { it.getJSONArray("indexes").getJSONObject(0).put("web_url", "http://x/{path}") }.contains("https web_url"))
        assertTrue(refused { it.put("threshold", 1.5) }.contains("threshold"))
        assertTrue(refused { it.put("limit", 0) }.contains("limit"))
    }

    // ── Semantic ───────────────────────────────────────────────────────────────────────────────
    @Test fun semanticHitsCarryRepoPathLineAndAreBestFirst() {
        val a = Cgc.hits("cloud-u-android", Fixtures.text("cgc-search.json"), 10)
        assertEquals("a block with no path is dropped, never filled in", 3, a.items.size)
        assertEquals("", a.note)
        val first = a.items[0]
        assertEquals("cloud-u-android", first.repo)
        assertTrue(first.path.endsWith("core/Engine.kt"))
        assertEquals(120, first.line)
        assertEquals(listOf("SearchEngine", "query"), first.symbols)
        assertTrue(first.snippet.startsWith("class SearchEngine"))
        assertEquals(0.82, first.score!!, 1e-9)
        assertEquals(listOf(82L, 65L, 60L), a.items.map { Math.round(it.score!! * 100) })
        assertEquals("a doc block is a hit too", "ac_cloud-search/NOTICE.md", a.items[2].path)
    }

    @Test fun semanticHitsAreCappedAndAnArrayOrOtherSpellingsReadToo() {
        assertEquals(2, Cgc.hits("r", Fixtures.text("cgc-search.json"), 2).items.size)
        val b = Cgc.hits("r", """[{"file_path":"x/y.rs","line":7,"text":"fn y()","similarity":0.9},{"file":"z.rs","score":0.5,"relevance":0.1}]""", 10)
        assertEquals(listOf("x/y.rs", "z.rs"), b.items.map { it.path })
        assertEquals(7, b.items[0].line)
        assertEquals(0.9, b.items[0].score!!, 1e-9)
        assertEquals(0.1, b.items[1].score!!, 1e-9)
        assertNull("no line given is no line shown", b.items[1].line)
        assertEquals("fn y()", b.items[0].snippet)
        assertNull(Cgc.hits("r", """[{"path":"p","content":"c"}]""", 5).items[0].score)
        assertNull(Cgc.hits("r", """[{"path":"p","distance":"nan"}]""", 5).items[0].score)
    }

    @Test fun anEmptyOrNonJsonAnswerIsSaidAsTheServiceWroteIt() {
        val none = Cgc.hits("r", """{"code_blocks":[],"doc_blocks":[],"text_blocks":[]}""", 5)
        assertTrue(none.items.isEmpty()); assertEquals("", none.note)
        val words = Cgc.hits("r", "  No index found for project cloud-data  ", 5)
        assertTrue(words.items.isEmpty())
        assertEquals("No index found for project cloud-data", words.note)
        assertEquals(300, Cgc.hits("r", "x".repeat(500), 5).note.length)
    }

    // ── Graph RAG ─────────────────────────────────────────────────────────────────────────────
    @Test fun graphNodesAreReadWithTheirPath() {
        val a = Cgc.nodes("cloud-u-android", Fixtures.text("cgc-graph-nodes.json"), 10)
        assertEquals(2, a.items.size)
        assertTrue(a.items[0].path.endsWith("ui/SearchShell.kt"))
        assertEquals("file", a.items[0].kind)
        assertEquals("The shell: island, first row, second rows", a.items[0].description)
        assertEquals("a node with no path is shown by its id", a.items[1].id, a.items[1].path)
        assertEquals("cloud-u-android", a.items[1].repo)
        assertEquals(1, Cgc.nodes("r", Fixtures.text("cgc-graph-nodes.json"), 1).items.size)
    }

    @Test fun graphRelationshipsSplitIntoCallersAndCallees() {
        val a = Cgc.edges(Fixtures.text("cgc-graph-relationships.json"))
        assertEquals("an edge with no source is dropped", 4, a.items.size)
        assertEquals("imports", a.items[0].type)
        assertEquals("A imports B", a.items[0].description)
        assertEquals("the type is read from `type` as well", "calls", a.items[2].type)
        val (callers, callees) = Cgc.split("A.kt", a.items + CgcEdge("A.kt", "A.kt", "self", ""))
        assertEquals(listOf("C.kt", "E.kt"), callers.map { it.source })
        assertEquals("a self edge is never its own caller", listOf("B.kt", "D.kt", "A.kt"), callees.map { it.target })
        assertEquals(emptyList<CgcEdge>() to emptyList<CgcEdge>(), Cgc.split("Z.kt", a.items))
    }

    @Test fun theToolArgumentsAreTheServicesOwn() {
        val s = Cgc.semanticArgs("cloud-data", "find the mcp client", 0.3)
        assertEquals("find the mcp client", s.getString("query")); assertEquals("cloud-data", s.getString("repo"))
        assertEquals("json", s.getString("format")); assertEquals(0.3, s.getDouble("threshold"), 1e-9); assertEquals("all", s.getString("mode"))
        val g = Cgc.graphSearchArgs("r", "q")
        assertEquals("search", g.getString("operation")); assertEquals("q", g.getString("query")); assertEquals("json", g.getString("format"))
        val r = Cgc.relationshipsArgs("r", "n1")
        assertEquals("get-relationships", r.getString("operation")); assertEquals("n1", r.getString("node_id")); assertEquals("r", r.getString("repo"))
        assertEquals("cgc.octocode.search", Cgc.SEARCH_TOOL); assertEquals("cgc.octocode.graphrag", Cgc.GRAPH_TOOL)
    }

    @Test fun theHealthLineNamesWhatFails() {
        assertEquals("service ok", Cgc.health("""{"status":"ok","checks":{"octocode":{"binary":true},"kgstore":{"reachable":true},"codegraph_bundle":{"present":true}}}"""))
        assertEquals(
            "service degraded (octocode binary missing, graph store unreachable, code graph bundle missing)",
            Cgc.health("""{"status":"degraded","checks":{"octocode":{"binary":false},"kgstore":{"reachable":false},"codegraph_bundle":{"present":false}}}"""),
        )
        assertEquals("service unknown", Cgc.health("{}"))
        assertEquals("service health unreadable", Cgc.health("<html>"))
    }

    // ── the MCP client ────────────────────────────────────────────────────────────────────────
    private class McpFake(val reply: (String) -> Http.Response) : Http {
        val posted = mutableListOf<Triple<String, Map<String, String>, JSONObject>>()
        override fun get(url: String, headers: Map<String, String>, timeoutMs: Int) = error("no GET")
        override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): Http.Response {
            val j = JSONObject(body)
            posted += Triple(url, headers, j)
            return when (j.getString("method")) {
                "initialize" -> Http.Response(200, sse(1, """{"protocolVersion":"2025-03-26","capabilities":{},"serverInfo":{"name":"cloud-cgc-pub-mcp"}}"""), mapOf("mcp-session-id" to "sess-9"))
                "notifications/initialized" -> Http.Response(202, "")
                else -> reply(j.getJSONObject("params").getString("name"))
            }
        }
    }

    private companion object {
        fun sse(id: Int, result: String) = "event: message\ndata: {\"result\":$result,\"jsonrpc\":\"2.0\",\"id\":$id}\n\n"
    }

    @Test fun aToolCallIsInitializeThenInitializedThenCallWithTheSession() {
        val http = McpFake { Http.Response(200, sse(2, """{"content":[{"type":"text","text":"line one"},{"type":"image","text":"skipped"},{"type":"text","text":"line two"}]}""")) }
        val reply = McpClient(http, "http://svc:3105/mcp", mapOf("X-Test" to "1"), 5000).call(Cgc.SEARCH_TOOL, Cgc.semanticArgs("r", "q", 0.3))
        assertEquals("line one\nline two", reply.text)
        assertFalse(reply.isError)
        assertEquals(listOf("initialize", "notifications/initialized", "tools/call"), http.posted.map { it.third.getString("method") })
        for ((url, headers, _) in http.posted) {
            assertEquals("http://svc:3105/mcp", url)
            assertEquals("application/json, text/event-stream", headers["Accept"])
            assertEquals("1", headers["X-Test"])
        }
        assertNull("the first request has no session yet", http.posted[0].second["Mcp-Session-Id"])
        assertEquals("sess-9", http.posted[1].second["Mcp-Session-Id"])
        assertEquals("sess-9", http.posted[2].second["Mcp-Session-Id"])
        val call = http.posted[2].third
        assertEquals(2, call.getInt("id"))
        assertEquals("cgc.octocode.search", call.getJSONObject("params").getString("name"))
        assertEquals("q", call.getJSONObject("params").getJSONObject("arguments").getString("query"))
    }

    @Test fun aToolThatCallsItselfAnErrorIsReportedAsOne() {
        val http = McpFake { Http.Response(200, """{"jsonrpc":"2.0","id":2,"result":{"isError":true,"content":[{"type":"text","text":"Refused: cloud-data is not served by this surface"}]}}""") }
        val reply = McpClient(http, "u", emptyMap(), 1).call("t", JSONObject())
        assertTrue(reply.isError)
        assertEquals("Refused: cloud-data is not served by this surface", reply.text)
    }

    @Test fun aServerWithoutASessionHeaderIsStillCalled() {
        val http = object : Http {
            val methods = mutableListOf<Pair<String, Map<String, String>>>()
            override fun get(url: String, headers: Map<String, String>, timeoutMs: Int) = error("no GET")
            override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): Http.Response {
                val m = JSONObject(body).getString("method"); methods += m to headers
                return Http.Response(200, if (m == "initialize") """{"jsonrpc":"2.0","id":1,"result":{}}""" else if (m == "tools/call") """{"jsonrpc":"2.0","id":2,"result":{"content":[]}}""" else "")
            }
        }
        assertEquals("", McpClient(http, "u", emptyMap(), 1).call("t", JSONObject()).text)
        assertTrue(http.methods.none { it.second.containsKey("Mcp-Session-Id") })
    }

    @Test fun aFailureCarriesItsReason() {
        fun message(h: Http) = runCatching { McpClient(h, "u", emptyMap(), 1).call("t", JSONObject()) }.exceptionOrNull()?.message ?: fail("expected a failure")
        val down = object : Http {
            override fun get(url: String, headers: Map<String, String>, timeoutMs: Int) = error("no GET")
            override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int) = Http.Response(503, "Service\nUnavailable ".repeat(30))
        }
        val m = message(down).toString()
        assertTrue(m, m.startsWith("HTTP 503 Service Unavailable"))
        assertTrue(m.length <= "HTTP 503 ".length + 160)
        assertEquals("boom", McpClient.runCatching { result("""{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"boom"}}""", 1) }.exceptionOrNull()?.message)
    }

    @Test fun aReplyIsReadFromJsonOrFromAnEventStreamByItsId() {
        assertEquals("v", McpClient.result("""{"jsonrpc":"2.0","id":3,"result":{"k":"v"}}""", 3).getString("k"))
        val stream = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\nevent: message\ndata: not json\n\nevent: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"k\":\"w\"}}\n\n"
        assertEquals("w", McpClient.result(stream, 7).getString("k"))
        assertEquals("a reply for another request is not this one's", "no MCP reply for request 8", McpClient.runCatching { result(stream, 8) }.exceptionOrNull()?.message)
        assertEquals("MCP reply without result", McpClient.runCatching { result("""{"id":1}""", 1) }.exceptionOrNull()?.message)
        assertEquals("MCP error", McpClient.runCatching { result("""{"id":1,"error":{}}""", 1) }.exceptionOrNull()?.message)
    }
}
