package com.diegonmarcos.cloudkgstore

import com.diegonmarcos.cloudsearch.core.CgcConfig
import com.diegonmarcos.cloudsearch.core.FleetEndpoint
import com.diegonmarcos.cloudsearch.core.Http
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fleet calls on a fake network: what each route is sent, and the honest state when a service is missing, down or refuses. */
class KgFleetTest {
    private class Net(val reply: (String, String, String) -> Http.Response) : Http {
        val seen = mutableListOf<Triple<String, Map<String, String>, String>>()
        override fun get(url: String, headers: Map<String, String>, timeoutMs: Int): Http.Response { seen += Triple(url, headers, ""); return reply("GET", url, "") }
        override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): Http.Response { seen += Triple(url, headers, body); return reply("POST", url, body) }
    }

    private fun svc(id: String, mesh: String, public: String, policy: String = "mesh_first", health: String = "/health", paths: List<String> = emptyList()) =
        KgService(id, "api", health, paths, FleetEndpoint(id, mesh, public, policy))

    private val api = svc("c3-infra-api", "http://c3-infra-api.mesh:8081", "https://c3-pvt.example/c3-infra-api", paths = listOf("/apk", "/containers"))
    private fun calls(net: Http, bearer: String? = null, vararg more: KgService, cgc: CgcConfig? = null) =
        KgCalls(net, (listOf(api) + more).associateBy { it.id }, cgc, 1000, "/mcp") { bearer }

    @Test fun aGetGoesToTheMeshRouteWithoutTheBearer() {
        val net = Net { _, _, _ -> Http.Response(200, "[]") }
        val r = calls(net, "tok").request("c3-infra-api", "GET", "apk", "")
        assertEquals(KgCalls.Kind.OK, r.kind)
        assertEquals("mesh", r.via)
        assertEquals("http://c3-infra-api.mesh:8081/apk", net.seen.single().first)
        assertFalse("the bearer never goes to a mesh name", net.seen.single().second.containsKey("Authorization"))
        assertEquals(200, r.items.single().code)
    }

    @Test fun aPostCarriesItsBodyAndANon2xxIsRefusedWithTheReplyKept() {
        val net = Net { m, _, _ -> if (m == "POST") Http.Response(422, """{"error":"bad body"}""") else Http.Response(200, "") }
        val r = calls(net).request("c3-infra-api", "POST", "/containers", """{"a":1}""")
        assertEquals(KgCalls.Kind.REFUSED, r.kind)
        assertEquals("HTTP 422", r.detail)
        assertEquals("""{"error":"bad body"}""", r.items.single().body)
        assertEquals("""{"a":1}""", net.seen.single().third)
    }

    @Test fun whenTheMeshIsDownThePublicRouteNeedsTheSignInAndThenCarriesIt() {
        val down = Net { _, url, _ -> if ("mesh" in url) throw java.io.IOException("no route to host") else Http.Response(200, "ok") }
        val signedOut = calls(down).request("c3-infra-api", "GET", "/health", "")
        assertEquals(KgCalls.Kind.UNAVAILABLE, signedOut.kind)
        assertTrue(signedOut.detail, signedOut.detail.contains("mesh: no route to host") && signedOut.detail.contains("fleet sign-in"))

        val signedIn = Net { _, url, _ -> if ("mesh" in url) throw java.io.IOException("no route to host") else Http.Response(200, "ok") }
        val r = calls(signedIn, "tok").request("c3-infra-api", "GET", "/health", "")
        assertEquals("public", r.via)
        assertEquals("Bearer tok", signedIn.seen.last().second["Authorization"])
        assertEquals("https://c3-pvt.example/c3-infra-api/health", signedIn.seen.last().first)
    }

    @Test fun aServiceTheBuildDidNotBakeIsSaidToBeMissing() {
        val r = calls(Net { _, _, _ -> error("no network call for a missing service") }).request("tokens-api", "GET", "/health", "")
        assertEquals(KgCalls.Kind.UNAVAILABLE, r.kind)
        assertEquals("tokens-api is not in the fleet endpoints", r.detail)
        assertTrue(r.items.isEmpty())
    }

    @Test fun theConsolePathsAreTheHealthPathThenTheDeclaredRoutesOnce() {
        assertEquals(listOf("/health", "/apk", "/containers"), api.consolePaths())
        assertEquals(listOf("/health"), svc("x", "http://x", "", paths = listOf("/health")).consolePaths())
    }

    // ── MCP ───────────────────────────────────────────────────────────────────────────────────

    private fun sse(id: Int, result: String) = "event: message\ndata: {\"result\":$result,\"jsonrpc\":\"2.0\",\"id\":$id}\n\n"

    private fun mcpNet(list: String) = Net { _, _, body ->
        when (JSONObject(body).getString("method")) {
            "initialize" -> Http.Response(200, sse(1, """{"protocolVersion":"2025-03-26","capabilities":{}}"""), mapOf("mcp-session-id" to "s1"))
            "notifications/initialized" -> Http.Response(202, "")
            else -> Http.Response(200, sse(2, list))
        }
    }

    @Test fun theToolsAreListedFromTheMcpServiceWithTheSession() {
        val net = mcpNet("""{"tools":[{"name":"cgc.octocode.search","description":" Semantic code search ","inputSchema":{"properties":{"query":{},"repo":{}}}},{"name":"cgc.octocode.graphrag"}]}""")
        val mcp = svc("cgc-pub-mcp", "http://cgc.mesh:3105", "", "mesh_only")
        val r = calls(net, null, mcp).tools("cgc-pub-mcp")
        assertEquals(KgCalls.Kind.OK, r.kind)
        assertEquals(listOf("cgc.octocode.search", "cgc.octocode.graphrag"), r.items.map { it.name })
        assertEquals("Semantic code search", r.items[0].description)
        assertEquals(listOf("query", "repo"), r.items[0].inputs)
        assertEquals(listOf("initialize", "notifications/initialized", "tools/list"), net.seen.map { JSONObject(it.third).getString("method") })
        assertEquals("http://cgc.mesh:3105/mcp", net.seen[0].first)
        assertNull(net.seen[0].second["Mcp-Session-Id"])
        assertEquals("s1", net.seen[2].second["Mcp-Session-Id"])
    }

    @Test fun aServerWithNoToolsIsEmptyNotAnError() {
        val mcp = svc("cgc-pub-mcp", "http://cgc.mesh:3105", "", "mesh_only")
        assertEquals(KgCalls.Kind.EMPTY, calls(mcpNet("""{"tools":[]}"""), null, mcp).tools("cgc-pub-mcp").kind)
    }

    @Test fun aRefusingMcpServerCarriesItsStatus() {
        val mcp = svc("cgc-pub-mcp", "http://cgc.mesh:3105", "", "mesh_only")
        val r = calls(Net { _, _, _ -> Http.Response(503, "Service Unavailable") }, null, mcp).tools("cgc-pub-mcp")
        assertEquals(KgCalls.Kind.UNAVAILABLE, r.kind)
        assertTrue(r.detail, r.detail.contains("HTTP 503"))
    }

    @Test fun thePrivateIndexWaitsForTheAccountSignIn() {
        val cgc = CgcConfig.parse(JSONObject(Data.searchJson.getJSONObject("cgc").toString()))
        val pvt = svc("cgc-pvt-mcp", "http://pvt.mesh:3107", "", "mesh_only")
        val r = calls(Net { _, _, _ -> error("must not call") }, null, pvt, cgc = cgc).tools("cgc-pvt-mcp")
        assertEquals(KgCalls.Kind.UNAVAILABLE, r.kind)
        assertEquals("Cloud Account has no fleet sign-in", r.detail)
    }

    @Test fun aNoServiceListIsReportedAsMissingNotAsEmpty() {
        val r = calls(Net { _, _, _ -> error("no call") }).tools("app-mcp")
        assertEquals(KgCalls.Kind.UNAVAILABLE, r.kind)
    }

    // ── CGC and rerank ────────────────────────────────────────────────────────────────────────

    private val cgcCfg get() = CgcConfig.parse(JSONObject(Data.searchJson.getJSONObject("cgc").toString()))

    @Test fun aSemanticSearchReadsTheRealCodeIndexAnswer() {
        val pub = svc("cgc-pub-mcp", "http://cgc.mesh:3105", "", "mesh_only")
        val net = Net { _, _, body ->
            when (JSONObject(body).getString("method")) {
                "initialize" -> Http.Response(200, sse(1, "{}"), mapOf("mcp-session-id" to "s"))
                "notifications/initialized" -> Http.Response(202, "")
                else -> {
                    val text = JSONObject.quote(Data.searchFixture("cgc-search.json"))
                    Http.Response(200, sse(2, """{"content":[{"type":"text","text":$text}]}"""))
                }
            }
        }
        val c = calls(net, null, pub, cgc = cgcCfg)
        val ix = cgcCfg.indexes.first { it.service == "cgc-pub-mcp" }
        val r = c.semantic(ix, ix.repos.first(), "mcp client")
        assertEquals(KgCalls.Kind.OK, r.kind)
        assertTrue(r.items.isNotEmpty())
        assertTrue(r.items.all { it.path.isNotBlank() })
        val call = net.seen.map { JSONObject(it.third) }.last { it.getString("method") == "tools/call" }
        assertEquals("cgc.octocode.search", call.getJSONObject("params").getString("name"))
    }

    @Test fun aToolThatCallsItselfAnErrorIsRefusedWithItsWords() {
        val pub = svc("cgc-pub-mcp", "http://cgc.mesh:3105", "", "mesh_only")
        val net = Net { _, _, body ->
            when (JSONObject(body).getString("method")) {
                "initialize" -> Http.Response(200, sse(1, "{}"))
                "notifications/initialized" -> Http.Response(202, "")
                else -> Http.Response(200, sse(2, """{"isError":true,"content":[{"type":"text","text":"index not built"}]}"""))
            }
        }
        val ix = cgcCfg.indexes.first { it.service == "cgc-pub-mcp" }
        val r = calls(net, null, pub, cgc = cgcCfg).graph(ix, ix.repos.first(), "x")
        assertEquals(KgCalls.Kind.REFUSED, r.kind)
        assertEquals("index not built", r.detail)
    }

    @Test fun rerankScoresComeBackInDocumentOrder() {
        assertEquals(listOf(0.2, 0.9), KgCalls.scores("""{"results":[{"index":1,"relevance_score":0.9},{"index":0,"relevance_score":0.2}]}""", 2))
        assertTrue(runCatching { KgCalls.scores("""{"results":[{"index":0,"relevance_score":0.2}]}""", 2) }.isFailure)
        assertTrue(runCatching { KgCalls.scores("""{"results":[{"index":5,"relevance_score":0.2}]}""", 2) }.isFailure)
    }

    @Test fun theRerankerIsAskedOnTheMeshAndItsAbsenceIsSaid() {
        val emb = svc("embeddings-api", "http://emb.mesh:7997", "", "mesh_only")
        val r = KgPage.Rerank("embeddings-api", "/rerank", "BAAI/bge-reranker-v2-m3")
        val net = Net { _, _, _ -> Http.Response(200, """{"results":[{"index":0,"relevance_score":0.7}]}""") }
        val ok = calls(net, null, emb).rerank(r, "q", listOf("doc"))
        assertEquals(listOf(0.7), ok.items)
        val sent = JSONObject(net.seen.single().third)
        assertEquals("BAAI/bge-reranker-v2-m3", sent.getString("model"))
        assertEquals("http://emb.mesh:7997/rerank", net.seen.single().first)

        val down = calls(Net { _, _, _ -> throw java.io.IOException("timed out") }, null, emb).rerank(r, "q", listOf("doc"))
        assertEquals(KgCalls.Kind.UNAVAILABLE, down.kind)
        assertTrue(down.detail.contains("timed out"))
        assertEquals(KgCalls.Kind.UNAVAILABLE, calls(net).rerank(r, "q", listOf("doc")).kind)
        assertEquals(KgCalls.Kind.EMPTY, calls(net, null, emb).rerank(r, "q", emptyList()).kind)
    }

    // ── the baked declarations ────────────────────────────────────────────────────────────────

    @Test fun theBakedServicesCarryTheirAddressesAndRoutes() {
        val json = """[{"id":"cloud-me-api","kind":"data","health":"/me/health","route_paths":[],"mesh_url":"http://m:8124","public_url":"https://p/x","policy":"mesh_first"},{"id":"cgc-pvt-mcp","mesh_url":"http://v:3107","public_url":"","policy":"mesh_only"}]"""
        val s = KgService.parse(json)
        assertEquals(setOf("cloud-me-api", "cgc-pvt-mcp"), s.keys)
        assertEquals("http://m:8124", s.getValue("cloud-me-api").endpoint.meshUrl)
        assertEquals("mesh_only", s.getValue("cgc-pvt-mcp").endpoint.policy)
        assertEquals(emptyMap<String, KgService>(), KgService.parse("not json"))
    }

    @Test fun theFleetAppsFilterAndTheirDebugPortsComeFromTheGeneratedManifests() {
        val apps = Data.fleetManifest.getJSONArray("apps").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.filter { it.getString("kind") == "app" }
        val json = org.json.JSONArray(apps.map { JSONObject().put("id", it.getString("id")).put("label", it.optString("label")).put("display_name", it.optString("display_name")).put("package", it.getString("package")).put("version_name", it.optString("version_name")) }).toString()
        val all = KgApp.parse(json)
        assertTrue(all.size > 30)
        assertTrue("the new app is in the fleet manifest the Binary page lists", all.any { it.pkg == "com.diegonmarcos.cloudkgstore" })
        assertTrue(KgApps.filter(all, "").size == all.size)
        assertEquals(listOf("com.diegonmarcos.cloudkgstore"), KgApps.filter(all, "KG store").map { it.pkg })
        val ports = KgApps.ports(Data.debugPorts.toString())
        val rows = KgApps.debugRows(all, ports, "kgstore")
        assertEquals(1, rows.size)
        assertNotNull("the app has a declared debug port", rows.single().port)
        assertEquals(ports["com.diegonmarcos.cloudkgstore"], rows.single().port)
        assertEquals("/app/kg-store/api/<op>", KgApps.gatewayRoute("/app/{id}/api/{op}", rows.single().app))
        assertNull("an app with no port is listed with none", KgApps.debugRows(listOf(rows.single().app.copy(pkg = "x.y")), ports, "").single().port)
    }
}
