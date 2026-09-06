package com.diegonmarcos.superapp.cloud

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tiny Gitea REST API client for the "Gitea Repos" feed card. Returns
 * [GitHubFeed.Commit] / [GitHubFeed.Feed] / [GitHubFeed.Status] rather
 * than a parallel set of types — Gitea's commit-list JSON carries the
 * same fields (sha, commit.message, commit.author.name/date, html_url)
 * under the same names, confirmed against the live server.
 *
 * Mesh-only by design: hits the container directly at 10.0.0.6:3002
 * (already cleartext-allowed for 10.0.0.x in network_security_config.xml)
 * rather than the public git.diegonmarcos.com domain, which sits behind
 * two-factor auth this app has no way to complete. Unauthenticated —
 * confirmed live that the mirrored repos' commits answer with no token,
 * same as [GitHubFeed]'s unauthenticated GitHub calls.
 */
object GiteaFeed {
    private const val BASE = "http://10.0.0.6:3002/api/v1"
    private const val PREFS = "gitea_feed_cache"
    private const val TTL_MS = 900_000L
    private const val UA = "Diego-SuperApp/1.0 (+https://diegonmarcos.com)"

    suspend fun commits(
        ctx: Context, owner: String, repo: String, limit: Int = 1,
    ): GitHubFeed.Feed<GitHubFeed.Commit> {
        val key = "$owner/$repo/commits"
        val body = fetchCached(ctx, key, "$BASE/repos/$owner/$repo/commits?limit=$limit")
            ?: return GitHubFeed.Feed(emptyList(), GitHubFeed.Status.UNREACHABLE)
        return GitHubFeed.Feed(runCatching {
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val c = o.getJSONObject("commit")
                val author = c.optJSONObject("author")?.optString("name").orEmpty()
                GitHubFeed.Commit(
                    sha      = o.optString("sha", "").take(7),
                    message  = c.optString("message", "").lineSequence().firstOrNull().orEmpty(),
                    author   = author,
                    htmlUrl  = o.optString("html_url", ""),
                    tsMillis = parseIso8601(c.optJSONObject("author")?.optString("date").orEmpty()),
                )
            }
        }.getOrDefault(emptyList()), GitHubFeed.Status.OK)
    }

    /** Same shape as [GitHubFeed]'s own cache — fresh-if-recent,
     *  stale-body-on-failure — just without the GitHub-specific
     *  rate-limit accounting Gitea's mesh-local API has no need of. */
    private suspend fun fetchCached(ctx: Context, key: String, url: String): String? {
        val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val ts = sp.getLong("$key.ts", 0L)
        if (now - ts < TTL_MS) {
            sp.getString("$key.body", null)?.let { return it }
        }
        val fresh = fetch(url) ?: return sp.getString("$key.body", null)
        sp.edit().putLong("$key.ts", now).putString("$key.body", fresh).apply()
        return fresh
    }

    private suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 5_000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode in 200..299)
                    conn.inputStream.bufferedReader().use { it.readText() }
                else null
            } finally { conn.disconnect() }
        }.getOrNull()
    }

    private fun parseIso8601(s: String): Long = runCatching {
        java.time.Instant.parse(s).toEpochMilli()
    }.getOrDefault(0L)
}
