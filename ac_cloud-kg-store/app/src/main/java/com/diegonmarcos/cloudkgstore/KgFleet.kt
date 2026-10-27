package com.diegonmarcos.cloudkgstore

import com.diegonmarcos.cloudsearch.core.Cgc
import com.diegonmarcos.cloudsearch.core.CgcAnswer
import com.diegonmarcos.cloudsearch.core.CgcConfig
import com.diegonmarcos.cloudsearch.core.CgcEdge
import com.diegonmarcos.cloudsearch.core.CgcHit
import com.diegonmarcos.cloudsearch.core.CgcNode
import com.diegonmarcos.cloudsearch.core.FleetEndpoint
import com.diegonmarcos.cloudsearch.core.Http
import com.diegonmarcos.cloudsearch.core.McpClient
import com.diegonmarcos.cloudsearch.core.Outcome
import org.json.JSONArray
import org.json.JSONObject

/**
 * One fleet service as app/build.gradle bakes it (BuildConfig.FLEET_SERVICES_B64) from ac_cloud-account/build.json::fleet_endpoints:
 * its kind, health path, declared route paths and the [endpoint] (mesh base, public base, policy) core routes by. No host is
 * spelled in Kotlin.
 */
data class KgService(val id: String, val kind: String, val health: String, val paths: List<String>, val endpoint: FleetEndpoint) {
    /** What the console offers to call: the health path first, then the declared route paths, each once. */
    fun consolePaths(): List<String> = (listOf(health) + paths).filter { it.isNotBlank() }.distinct()

    companion object {
        fun parse(json: String): Map<String, KgService> = runCatching {
            val a = JSONArray(json)
            (0 until a.length()).map { a.getJSONObject(it) }.associate { o ->
                val paths = o.optJSONArray("route_paths")?.let { r -> (0 until r.length()).map { r.getString(it) } } ?: emptyList()
                o.getString("id") to KgService(
                    o.getString("id"), o.optString("kind"), o.optString("health"), paths,
                    FleetEndpoint(o.getString("id"), o.optString("mesh_url"), o.optString("public_url"), o.optString("policy", "mesh_first")),
                )
            }
        }.getOrDefault(emptyMap())
    }
}

/** One fleet app as the generated manifest (aa_cloud-superapp/data/constellation-fleet.json) names it. */
data class KgApp(
    val id: String, val label: String, val name: String, val pkg: String, val version: String,
    val image: String, val asset: String, val releaseUrl: String, val repoUrl: String,
) {
    companion object {
        fun parse(json: String): List<KgApp> = runCatching {
            val a = JSONArray(json)
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                KgApp(it.getString("id"), it.optString("label"), it.optString("display_name"), it.getString("package"), it.optString("version_name"),
                    it.optString("image"), it.optString("asset"), it.optString("release_url"), it.optString("repo_url"))
            }
        }.getOrDefault(emptyList())
    }
}

/** What the Binary and Web/Debug pages list. */
object KgApps {
    /** The apps whose id, label, name or package contains [q] (case-insensitive), by name; all of them for a blank query. */
    fun filter(all: List<KgApp>, q: String): List<KgApp> {
        val t = q.trim().lowercase()
        return all.filter { t.isEmpty() || listOf(it.id, it.label, it.name, it.pkg).any { f -> f.lowercase().contains(t) } }.sortedBy { it.name.lowercase() }
    }

    /** package -> debug port, from the baked 1_cicd/src/data/debug-ports.json::ports. */
    fun ports(json: String): Map<String, Int> = runCatching {
        val o = JSONObject(json)
        o.keys().asSequence().filterNot { it.startsWith("_") }.associateWith { o.getInt(it) }
    }.getOrDefault(emptyMap())

    /** An app and its declared debug port; null = none declared (the page says so). */
    data class DebugRow(val app: KgApp, val port: Int?)

    fun debugRows(all: List<KgApp>, ports: Map<String, Int>, q: String): List<DebugRow> = filter(all, q).map { DebugRow(it, ports[it.pkg]) }

    /** The gateway route of one app's debug API, from the declared pattern. */
    fun gatewayRoute(pattern: String, app: KgApp): String = pattern.replace("{id}", app.id).replace("{op}", "<op>")
}

/**
 * The calls to the fleet services the Cloud pages use. Every address comes from the fleet declaration ([services]); a route is
 * tried in the declared policy order, and the fleet bearer ([bearer], Cloud Account's, read on use and never kept) goes to a public
 * origin only (core's FleetEndpoint). Every answer says what came of it in [Kind]: a service that is not declared, does not answer
 * or refuses is reported with its own reasons, never replaced by a stand-in.
 */
class KgCalls(
    private val http: Http,
    val services: Map<String, KgService>,
    private val cgc: CgcConfig?,
    private val timeoutMs: Int,
    private val mcpPath: String,
    private val bearer: () -> String?,
) {
    /** [kind] says what the page shows; [detail] is the service's or the transport's own words. */
    enum class Kind { OK, EMPTY, UNAVAILABLE, REFUSED, NOTE }
    data class Result<T>(val items: List<T> = emptyList(), val kind: Kind = Kind.OK, val detail: String = "", val via: String = "")
    data class Reply(val code: Int, val body: String)
    data class Tool(val service: String, val name: String, val description: String, val inputs: List<String>)

    private fun <T> unknown(id: String): Result<T> = Result(kind = Kind.UNAVAILABLE, detail = "$id is not in the fleet endpoints")

    /** One GET or POST on [service] at [path] (the console). A non-2xx answer is [Kind.REFUSED] with the reply kept, so it can be read. */
    fun request(service: String, method: String, path: String, body: String): Result<Reply> {
        val s = services[service] ?: return unknown(service)
        val p = if (path.startsWith("/")) path else "/$path"
        return when (val o = s.endpoint.attempt(bearer()) { r ->
            val url = r.base + p
            val res = if (method == METHOD_POST) http.post(url, r.headers, body, timeoutMs) else http.get(url, r.headers, timeoutMs)
            Reply(res.code, res.body)
        }) {
            is Outcome.Down -> Result(kind = Kind.UNAVAILABLE, detail = o.reasons.joinToString("; "))
            is Outcome.Ok -> Result(listOf(o.value), if (o.value.code in 200..299) Kind.OK else Kind.REFUSED, if (o.value.code in 200..299) "" else "HTTP ${o.value.code}", o.via)
        }
    }

    /** The tools an MCP [service] lists (MCP tools/list over its streamable-HTTP endpoint). */
    fun tools(service: String): Result<Tool> {
        val s = services[service] ?: return unknown(service)
        val token = bearer()
        if (cgc?.indexes?.any { it.service == service && it.needsAccount } == true && token == null) {
            return Result(kind = Kind.UNAVAILABLE, detail = "Cloud Account has no fleet sign-in")
        }
        return when (val o = s.endpoint.attempt(token) { r -> listTools(service, r.base + mcpPath, r.headers) }) {
            is Outcome.Down -> Result(kind = Kind.UNAVAILABLE, detail = o.reasons.joinToString("; "))
            is Outcome.Ok -> Result(o.value, if (o.value.isEmpty()) Kind.EMPTY else Kind.OK, via = o.via)
        }
    }

    private fun listTools(service: String, url: String, headers: Map<String, String>): List<Tool> {
        val h = headers + mapOf("Accept" to "application/json, text/event-stream")
        val init = post(url, h, rpc(1, "initialize", JSONObject().put("protocolVersion", "2025-03-26").put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "cloud-kg-store").put("version", "1"))))
        McpClient.result(init.body, 1)
        val sid = init.headers["mcp-session-id"]
        val h2 = if (sid.isNullOrBlank()) h else h + ("Mcp-Session-Id" to sid)
        post(url, h2, JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized").toString())
        val list = McpClient.result(post(url, h2, rpc(2, "tools/list", JSONObject())).body, 2).optJSONArray("tools") ?: JSONArray()
        return (0 until list.length()).map { list.getJSONObject(it) }.map { t ->
            val props = t.optJSONObject("inputSchema")?.optJSONObject("properties")
            Tool(service, t.getString("name"), t.optString("description").trim(), props?.keys()?.asSequence()?.toList().orEmpty())
        }
    }

    private fun post(url: String, h: Map<String, String>, body: String): Http.Response {
        val r = http.post(url, h, body, timeoutMs)
        if (r.code !in 200..299) throw IllegalStateException("HTTP ${r.code} " + r.body.take(160).replace('\n', ' '))
        return r
    }

    private fun rpc(id: Int, method: String, params: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params).toString()

    /** The service's health path, as one honest line (the code and, for a CGC service, its own checks). */
    fun health(ix: CgcConfig.Index): Result<String> {
        val s = services[ix.service] ?: return unknown(ix.service)
        val token = bearer()
        if (ix.needsAccount && token == null) return Result(kind = Kind.UNAVAILABLE, detail = "Cloud Account has no fleet sign-in")
        return when (val o = s.endpoint.attempt(token) { r -> http.get(r.base + ix.healthPath, r.headers, timeoutMs) }) {
            is Outcome.Down -> Result(kind = Kind.UNAVAILABLE, detail = o.reasons.joinToString("; "))
            is Outcome.Ok ->
                if (o.value.code in 200..299) Result(listOf(Cgc.health(o.value.body)), via = o.via)
                else Result(kind = Kind.REFUSED, detail = "HTTP ${o.value.code}", via = o.via)
        }
    }

    fun semantic(ix: CgcConfig.Index, repo: String, q: String): Result<CgcHit> =
        ask(ix, Cgc.SEARCH_TOOL, Cgc.semanticArgs(repo, q, cgc?.threshold ?: 0.3)) { Cgc.hits(repo, it, cgc?.limit ?: 25) }

    fun graph(ix: CgcConfig.Index, repo: String, q: String): Result<CgcNode> =
        ask(ix, Cgc.GRAPH_TOOL, Cgc.graphSearchArgs(repo, q)) { Cgc.nodes(repo, it, cgc?.limit ?: 25) }

    fun related(ix: CgcConfig.Index, repo: String, node: String): Result<CgcEdge> =
        ask(ix, Cgc.GRAPH_TOOL, Cgc.relationshipsArgs(repo, node)) { Cgc.edges(it) }

    private fun <T> ask(ix: CgcConfig.Index, tool: String, args: JSONObject, read: (String) -> CgcAnswer<T>): Result<T> {
        val s = services[ix.service] ?: return unknown(ix.service)
        val token = bearer()
        if (ix.needsAccount && token == null) return Result(kind = Kind.UNAVAILABLE, detail = "Cloud Account has no fleet sign-in")
        val ok = when (val o = s.endpoint.attempt(token) { r -> McpClient(http, r.base + ix.mcpPath, r.headers, timeoutMs).call(tool, args) }) {
            is Outcome.Down -> return Result(kind = Kind.UNAVAILABLE, detail = o.reasons.joinToString("; "))
            is Outcome.Ok -> o
        }
        val reply = ok.value
        if (reply.isError) return Result(kind = Kind.REFUSED, detail = reply.text.trim().take(300), via = ok.via)
        val a = runCatching { read(reply.text) }.getOrElse { return Result(kind = Kind.NOTE, detail = "unreadable answer: " + it.message, via = ok.via) }
        if (a.items.isNotEmpty()) return Result(a.items, Kind.OK, via = ok.via)
        if (a.note.isNotBlank()) return Result(kind = Kind.NOTE, detail = a.note, via = ok.via)
        return Result(kind = Kind.EMPTY, via = ok.via)
    }

    /**
     * The relevance score of each of [docs] for [query], from the fleet reranker (embeddings-api, bge-reranker-v2-m3; mesh only).
     * UNAVAILABLE carries the reasons when the service is absent or does not answer: the caller then keeps the order the code index gave.
     */
    fun rerank(r: KgPage.Rerank, query: String, docs: List<String>): Result<Double> {
        if (docs.isEmpty()) return Result(kind = Kind.EMPTY)
        val s = services[r.service] ?: return unknown(r.service)
        val body = JSONObject().put("model", r.model).put("query", query).put("documents", JSONArray(docs)).put("top_n", docs.size).put("return_documents", false)
        return when (val o = s.endpoint.attempt(bearer()) { route ->
            val res = http.post(route.base + r.path, route.headers, body.toString(), timeoutMs)
            if (res.code !in 200..299) throw IllegalStateException("HTTP ${res.code}")
            res.body
        }) {
            is Outcome.Down -> Result(kind = Kind.UNAVAILABLE, detail = o.reasons.joinToString("; "))
            is Outcome.Ok -> {
                val scores = runCatching { scores(o.value, docs.size) }.getOrNull()
                    ?: return Result(kind = Kind.NOTE, detail = "unreadable rerank answer", via = o.via)
                Result(scores, via = o.via)
            }
        }
    }

    companion object {
        const val METHOD_GET = "GET"
        const val METHOD_POST = "POST"

        /** `{results:[{index, relevance_score}]}` -> one score per document, in document order; throws when a document has none. */
        fun scores(body: String, count: Int): List<Double> {
            val res = JSONObject(body).getJSONArray("results")
            val out = arrayOfNulls<Double>(count)
            for (i in 0 until res.length()) {
                val o = res.getJSONObject(i)
                val at = o.getInt("index")
                require(at in 0 until count) { "rerank index out of range" }
                out[at] = o.getDouble("relevance_score")
            }
            return out.map { requireNotNull(it) { "rerank left a document without a score" } }
        }
    }
}
