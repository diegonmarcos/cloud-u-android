package com.diegonmarcos.clouddrive.sync

import android.content.SharedPreferences
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

/**
 * #1138 THE REMOTE FACTS of the Sync ▸ Git repo table, from the provider's REST API (the declared
 * api.base_url): total size (`size`, KB), the depth-1 size (the sum of the default branch's blob sizes,
 * `git/trees/<branch>?recursive=1`) and the branch count (`/branches?per_page=1`, read off the Link
 * header's `rel="last"` page). Every parser takes TEXT so the JVM suite runs the exact code the phone
 * runs; [fetch] is the only part with a socket. Results are cached per repository ([Cache]) and refreshed
 * only on pull-to-refresh — rendering the table never touches the network. The token is a parameter,
 * sent as the Authorization header of the request it was passed for, never stored or logged.
 */
object RepoRemoteStats {

    data class Stats(
        val sizeTotalKb: Long,
        val sizeMainBytes: Long,
        val branches: Int,
        val isPrivate: Boolean,
        val defaultBranch: String,
        val fetchedAtMs: Long,
    )

    /** What `GET /repos/{owner}/{name}` says that the table needs. */
    data class RepoInfo(val sizeKb: Long, val isPrivate: Boolean, val defaultBranch: String)

    fun parseRepo(text: String): RepoInfo? {
        val o = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val size = (o["size"] as? JsonPrimitive)?.longOrNull ?: return null
        return RepoInfo(size, (o["private"] as? JsonPrimitive)?.booleanOrNull ?: false, o.str("default_branch").ifBlank { "main" })
    }

    /** The depth-1 snapshot size: the sum of every `blob` entry's `size` in a recursive tree answer; null when unreadable. */
    fun parseTreeBytes(text: String): Long? {
        val o = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val tree = o["tree"] as? JsonArray ?: return null
        return tree.mapNotNull { it as? JsonObject }
            .filter { it.str("type") == "blob" }
            .sumOf { (it["size"] as? JsonPrimitive)?.longOrNull ?: 0L }
    }

    /** The page number of the Link header's `rel="last"` entry, or null when the answer was not paginated. */
    fun lastPage(link: String?): Int? {
        if (link.isNullOrBlank()) return null
        val last = link.split(',').firstOrNull { it.contains("rel=\"last\"") } ?: return null
        return Regex("""[?&]page=(\d+)""").find(last)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * The branch count from the FIRST page of `/branches?per_page=1`: with one branch per page the last
     * page's number IS the count; with no Link header the answer has at most one entry.
     */
    fun branchCount(firstPage: String, link: String?): Int? {
        lastPage(link)?.let { return it }
        val arr = runCatching { json.parseToJsonElement(firstPage) }.getOrNull() as? JsonArray ?: return null
        return arr.size
    }

    /** One workflow run as a line of the CI/CD feed: `✓ CI · main · 1a2b3c4 · 2026-10-23T10:00:00Z`. */
    fun parseRuns(text: String): List<String> {
        val o = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return emptyList()
        val runs = o["workflow_runs"] as? JsonArray ?: return emptyList()
        return runs.mapNotNull { it as? JsonObject }.map { r ->
            val mark = when (r.str("conclusion").ifBlank { r.str("status") }) {
                "success" -> "✓"; "failure", "timed_out", "startup_failure" -> "✗"; "cancelled", "skipped" -> "–"; else -> "…"
            }
            listOf(mark + " " + r.str("name"), r.str("head_branch"), r.str("head_sha").take(7), r.str("created_at")).filter { it.isNotBlank() }.joinToString(" · ")
        }
    }

    // ── the cache: one small JSON object per repository, in the app's plain prefs (nothing secret in it) ──

    object Cache {
        private const val KEY = "git.remote_stats"

        fun encode(m: Map<String, Stats>): String = buildJsonObject {
            m.forEach { (k, s) ->
                put(k, buildJsonObject {
                    put("total_kb", s.sizeTotalKb); put("main_bytes", s.sizeMainBytes); put("branches", s.branches.toLong())
                    put("private", s.isPrivate); put("default_branch", s.defaultBranch); put("at", s.fetchedAtMs)
                })
            }
        }.toString()

        fun decode(text: String?): Map<String, Stats> {
            val o = runCatching { json.parseToJsonElement(text.orEmpty()) }.getOrNull() as? JsonObject ?: return emptyMap()
            return o.entries.mapNotNull { (k, v) ->
                val s = v as? JsonObject ?: return@mapNotNull null
                val total = (s["total_kb"] as? JsonPrimitive)?.longOrNull
                val main = (s["main_bytes"] as? JsonPrimitive)?.longOrNull
                val branches = (s["branches"] as? JsonPrimitive)?.longOrNull
                val at = (s["at"] as? JsonPrimitive)?.longOrNull
                if (total == null || main == null || branches == null || at == null) null
                else k to Stats(total, main, branches.toInt(), (s["private"] as? JsonPrimitive)?.booleanOrNull ?: false, s.str("default_branch"), at)
            }.toMap()
        }

        fun load(p: SharedPreferences): Map<String, Stats> = decode(runCatching { p.getString(KEY, null) }.getOrNull())
        fun save(p: SharedPreferences, m: Map<String, Stats>) { p.edit().putString(KEY, encode(m)).apply() }
    }

    fun keyOf(owner: String, name: String) = "$owner/$name"

    // ── the network (blocking: call off the main thread) ──

    private data class Reply(val body: String, val link: String?)

    private fun get(url: String, token: String): Result<Reply> {
        if (com.diegonmarcos.clouddrive.startup.StartupGuard.onMainThread()) return Result.failure(IllegalStateException("not asked from the main thread"))
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 8000; readTimeout = 20000
                setRequestProperty("Accept", "application/vnd.github+json")
                if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("User-Agent", "Cloud-Drive-GitPage/1")
            }
            val code = conn.responseCode
            if (code !in 200..299) return Result.failure(IllegalStateException("HTTP $code"))
            Result.success(Reply(conn.inputStream.bufferedReader().use { it.readText() }, conn.getHeaderField("Link")))
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            conn?.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** The three reads for one repository; any one failing fails the whole (the cache keeps the last good answer). */
    fun fetch(baseUrl: String, owner: String, name: String, token: String, now: Long = System.currentTimeMillis()): Result<Stats> {
        val repoUrl = "$baseUrl/repos/${enc(owner)}/${enc(name)}"
        val info = get(repoUrl, token).getOrElse { return Result.failure(it) }.let { parseRepo(it.body) }
            ?: return Result.failure(IllegalStateException("unreadable repository answer"))
        val tree = get("$repoUrl/git/trees/${enc(info.defaultBranch)}?recursive=1", token).getOrElse { return Result.failure(it) }.let { parseTreeBytes(it.body) }
            ?: return Result.failure(IllegalStateException("unreadable tree answer"))
        val br = get("$repoUrl/branches?per_page=1", token).getOrElse { return Result.failure(it) }.let { branchCount(it.body, it.link) }
            ?: return Result.failure(IllegalStateException("unreadable branches answer"))
        return Result.success(Stats(info.sizeKb, tree, br, info.isPrivate, info.defaultBranch, now))
    }

    /** The newest workflow runs of a repository (the CI/CD feed), as display lines. */
    fun fetchRuns(baseUrl: String, owner: String, name: String, token: String, max: Int = 15): Result<List<String>> =
        get("$baseUrl/repos/${enc(owner)}/${enc(name)}/actions/runs?per_page=$max", token).map { parseRuns(it.body) }
}
