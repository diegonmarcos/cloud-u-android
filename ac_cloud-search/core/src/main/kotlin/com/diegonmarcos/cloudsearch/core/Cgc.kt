package com.diegonmarcos.cloudsearch.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * build.json::search.cgc: the two code indexes (Cloud-CGC over the public index, Me-CGC over the private one). Both are the
 * fleet's cgc MCP service (cloud-u-containers user-ai_cloud-cgc-pub-mcp: the same image on two surfaces, tools
 * cgc.octocode.search and cgc.octocode.graphrag), each named by its fleet_endpoints id. Nothing about a host is spelled in code.
 */
data class CgcConfig(val timeoutMs: Int, val limit: Int, val threshold: Double, val indexes: List<Index>) {
    /**
     * [id] is the ui section it serves. [needsAccount]: the index is private, so a query waits for the Cloud Account
     * sign-in (the fleet bearer, read over its IPC); the bearer itself still goes only to a public origin (FleetEndpoint).
     */
    data class Index(
        val id: String, val label: String, val service: String, val mcpPath: String, val healthPath: String, val repos: List<String>,
        val needsAccount: Boolean, val webUrl: String, val driveFleet: String, val repoNames: Map<String, String> = emptyMap(),
    ) {
        /** The repository's file on the web at [line] (or without a line anchor). */
        fun web(repo: String, path: String, line: Int?): String =
            webUrl.replace("{repo}", repoNames[repo] ?: repo).replace("{path}", path).replace("{line}", (line ?: 1).toString())
    }

    fun index(id: String): Index? = indexes.firstOrNull { it.id == id }

    companion object {
        fun parse(o: JSONObject): CgcConfig {
            val a = o.getJSONArray("indexes")
            val ix = (0 until a.length()).map { a.getJSONObject(it) }.map { x ->
                Index(
                    x.getString("id"), x.getString("label"), x.getString("service"), x.getString("mcp_path"), x.getString("health_path"),
                    x.getJSONArray("repos").let { r -> (0 until r.length()).map { r.getString(it) } },
                    x.getBoolean("needs_account"), x.getString("web_url"), x.getString("drive_fleet"),
                    x.optJSONObject("repo_names")?.let { n -> n.keys().asSequence().associateWith { k -> n.getString(k) } } ?: emptyMap(),
                )
            }
            val cfg = CgcConfig(o.getInt("timeout_ms"), o.getInt("limit"), o.getDouble("threshold"), ix)
            require(ix.isNotEmpty() && ix.map { it.id }.toSet().size == ix.size) { "search.cgc.indexes needs at least one index and no repeated id" }
            require(ix.all { it.repos.isNotEmpty() && it.mcpPath.startsWith("/") && it.webUrl.startsWith("https://") }) { "search.cgc.indexes need repos, a /mcp_path and an https web_url" }
            require(cfg.threshold in 0.0..1.0 && cfg.limit in 1..100) { "search.cgc threshold must be 0..1 and limit 1..100" }
            return cfg
        }
    }
}

/** A code-search hit: where ([repo]/[path]:[line]) and what ([snippet], the [symbols] in it). [score] is similarity 0..1 when the service gave one. */
data class CgcHit(val repo: String, val path: String, val line: Int?, val snippet: String, val symbols: List<String>, val score: Double?)

/** A graph node (a file or symbol the index knows) and an edge between two of them. */
data class CgcNode(val id: String, val repo: String, val path: String, val kind: String, val description: String)
data class CgcEdge(val source: String, val target: String, val type: String, val description: String)

/** What the service said: the parsed [items], or - when the answer was not JSON - its own words in [note]. */
data class CgcAnswer<T>(val items: List<T>, val note: String = "")

/** An MCP tools/call result: the tool's text, and whether the tool itself called it an error (a refusal, a missing index). */
data class McpReply(val text: String, val isError: Boolean)

/** The MCP streamable-HTTP client for one POST endpoint: initialize, initialized, tools/call (the session id the server hands out is echoed). */
class McpClient(private val http: Http, private val url: String, private val headers: Map<String, String>, private val timeoutMs: Int) {
    fun call(tool: String, args: JSONObject): McpReply {
        val h = headers + mapOf("Accept" to "application/json, text/event-stream")
        val init = post(h, rpc(1, "initialize", JSONObject().put("protocolVersion", "2025-03-26").put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "cloud-research").put("version", "1"))))
        result(init.body, 1)
        val sid = init.headers["mcp-session-id"]
        val h2 = if (sid.isNullOrBlank()) h else h + ("Mcp-Session-Id" to sid)
        post(h2, JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized").toString())
        val r = result(post(h2, rpc(2, "tools/call", JSONObject().put("name", tool).put("arguments", args))).body, 2)
        val c = r.optJSONArray("content") ?: JSONArray()
        val text = (0 until c.length()).map { c.getJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") }
        return McpReply(text, r.optBoolean("isError"))
    }

    private fun post(h: Map<String, String>, body: String): Http.Response {
        val r = http.post(url, h, body, timeoutMs)
        if (r.code !in 200..299) throw IllegalStateException("HTTP ${r.code} " + r.body.take(160).replace('\n', ' '))
        return r
    }

    private fun rpc(id: Int, method: String, params: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params).toString()

    companion object {
        /** The JSON-RPC `result` of reply [id] from a plain JSON body or an SSE stream (`data:` lines); an `error` member throws its message. */
        fun result(body: String, id: Int): JSONObject {
            val frames = if (body.trimStart().startsWith("{")) listOf(body)
            else body.lines().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }
            for (f in frames) {
                val o = runCatching { JSONObject(f) }.getOrNull() ?: continue
                if (o.optInt("id", -1) != id) continue
                o.optJSONObject("error")?.let { throw IllegalStateException(it.optString("message", "MCP error")) }
                return o.optJSONObject("result") ?: throw IllegalStateException("MCP reply without result")
            }
            throw IllegalStateException("no MCP reply for request $id")
        }
    }
}

/** The tool calls of the two modes and the readers of what comes back. */
object Cgc {
    const val SEARCH_TOOL = "cgc.octocode.search"
    const val GRAPH_TOOL = "cgc.octocode.graphrag"

    fun semanticArgs(repo: String, query: String, threshold: Double): JSONObject =
        JSONObject().put("query", query).put("repo", repo).put("mode", "all").put("format", "json").put("threshold", threshold)

    fun graphSearchArgs(repo: String, query: String): JSONObject =
        JSONObject().put("operation", "search").put("repo", repo).put("query", query).put("format", "json")

    fun relationshipsArgs(repo: String, nodeId: String): JSONObject =
        JSONObject().put("operation", "get-relationships").put("repo", repo).put("node_id", nodeId).put("format", "json")

    /** Octocode's search JSON: blocks under code_blocks / doc_blocks / text_blocks (or one array); a block with no path is dropped. */
    fun hits(repo: String, text: String, limit: Int): CgcAnswer<CgcHit> = answer(text) { root ->
        rows(root, "code_blocks", "doc_blocks", "text_blocks", "results", "matches").mapNotNull { o ->
            val path = first(o, "path", "file_path", "file")
            if (path.isEmpty()) null
            else CgcHit(repo, path, intOf(o, "start_line", "line", "startLine"), first(o, "content", "snippet", "text").trim().take(600), strings(o.optJSONArray("symbols")), score(o))
        }.sortedByDescending { it.score ?: -1.0 }.take(limit)
    }

    /** GraphRAG nodes (`graphrag search`). */
    fun nodes(repo: String, text: String, limit: Int): CgcAnswer<CgcNode> = answer(text) { root ->
        rows(root, "nodes", "results", "items").mapNotNull { o ->
            val id = first(o, "id", "node_id", "path")
            if (id.isEmpty()) null else CgcNode(id, repo, first(o, "path").ifEmpty { id }, first(o, "kind", "node_type", "type"), first(o, "description", "name").take(300))
        }.take(limit)
    }

    /** GraphRAG relationships of one node (`graphrag get-relationships`). */
    fun edges(text: String): CgcAnswer<CgcEdge> = answer(text) { root ->
        rows(root, "relationships", "edges", "results").mapNotNull { o ->
            val s = first(o, "source", "source_id", "from")
            val t = first(o, "target", "target_id", "to")
            if (s.isEmpty() || t.isEmpty()) null else CgcEdge(s, t, first(o, "relationship_type", "type", "kind", "relation"), first(o, "description").take(300))
        }
    }

    /** Who points at [node] (callers, importers) and what it points at (callees, imports): the edges split by direction. */
    fun split(node: String, edges: List<CgcEdge>): Pair<List<CgcEdge>, List<CgcEdge>> =
        edges.filter { it.target == node && it.source != node } to edges.filter { it.source == node }

    /** The service's /health JSON as one honest line: its status and every failing check. */
    fun health(body: String): String = runCatching {
        val o = JSONObject(body)
        val c = o.optJSONObject("checks")
        val bad = ArrayList<String>()
        if (c != null) {
            if (c.optJSONObject("octocode")?.optBoolean("binary", true) == false) bad.add("octocode binary missing")
            if (c.optJSONObject("kgstore")?.optBoolean("reachable", true) == false) bad.add("graph store unreachable")
            if (c.optJSONObject("codegraph_bundle")?.optBoolean("present", true) == false) bad.add("code graph bundle missing")
        }
        "service " + o.optString("status", "unknown") + (if (bad.isEmpty()) "" else " (" + bad.joinToString(", ") + ")")
    }.getOrDefault("service health unreadable")

    private fun <T> answer(text: String, read: (Any) -> List<T>): CgcAnswer<T> {
        val root: Any = runCatching { JSONObject(text) }.getOrNull() ?: runCatching { JSONArray(text) }.getOrNull()
            ?: return CgcAnswer(emptyList(), text.trim().take(300))
        return CgcAnswer(read(root))
    }

    private fun rows(root: Any, vararg keys: String): List<JSONObject> {
        val arrays = if (root is JSONArray) listOf(root) else keys.mapNotNull { (root as JSONObject).optJSONArray(it) }
        return arrays.flatMap { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
    }

    private fun first(o: JSONObject, vararg keys: String): String = keys.map { o.optString(it, "") }.firstOrNull { it.isNotEmpty() && it != "null" } ?: ""
    private fun intOf(o: JSONObject, vararg keys: String): Int? = keys.firstOrNull { o.has(it) && !o.isNull(it) }?.let { o.optInt(it, -1) }?.takeIf { it >= 0 }
    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
    private fun score(o: JSONObject): Double? = when {
        o.has("similarity") -> o.optDouble("similarity")
        o.has("relevance") -> o.optDouble("relevance")
        o.has("score") -> o.optDouble("score")
        o.has("distance") -> 1.0 - o.optDouble("distance")
        else -> null
    }?.takeIf { !it.isNaN() }
}
