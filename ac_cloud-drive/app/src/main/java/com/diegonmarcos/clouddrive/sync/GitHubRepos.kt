package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.clouddrive.Declarations
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * #608 THE authenticated repository listing of the Sync ▸ Git page's Personal section.
 *
 * Not a git operation and therefore not libs:git-sync's: it is one GET of the provider's
 * REST list route with the token the fleet sign-in (libs:auth) just proved, which is why
 * it lives beside the page that renders it. The route, its paging and the web-link shape
 * are the DECLARATION (build.json::ui.sync.git.api) — nothing here writes an endpoint.
 *
 * The token is a parameter and is never stored, never logged and never put in a URL: it
 * travels as the Authorization header of the request it was passed for. [parse] takes the
 * response TEXT, not a connection, so the JVM suite exercises the exact parser the phone
 * runs against a recorded body.
 */
object GitHubRepos {

    /**
     * One repository as the provider describes it. [private] is the PROVIDER's flag, which
     * is what splits the two declared groups — never a guess and never a declaration, so a
     * repository that goes private between two builds moves group by itself.
     */
    data class Repo(
        val name: String,
        val owner: String,
        val private: Boolean,
        val fork: Boolean,
        val defaultBranch: String,
        val description: String,
        val sizeKb: Long,
        val updatedAt: String,
        val pushedAt: String,
        val cloneUrl: String,
        val sshUrl: String,
        val webUrl: String,
        val stars: Long,
        val language: String,
    )

    /** What one page of the listing yielded, plus whether more pages can follow. */
    data class Page(val repos: List<Repo>, val complete: Boolean)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonObject.str(key: String, default: String = ""): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: default
    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
    private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L

    /**
     * The repositories in a list response. An error document (the provider answers a
     * JSON OBJECT with `message` when the token is refused) yields an EMPTY list rather
     * than a crash — [fetch] reports the refusal from the status code, which is the
     * honest signal; a parser that invented one repository out of an error body would be
     * the "green that verified nothing" shape.
     */
    fun parse(text: String): List<Repo> {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return emptyList()
        return root.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val name = o.str("name")
            if (name.isBlank()) return@mapNotNull null
            Repo(
                name = name,
                owner = (o["owner"] as? JsonObject)?.str("login") ?: o.str("full_name").substringBefore('/'),
                private = o.bool("private"),
                fork = o.bool("fork"),
                defaultBranch = o.str("default_branch", "main"),
                description = o.str("description"),
                sizeKb = o.long("size"),
                updatedAt = o.str("updated_at"),
                pushedAt = o.str("pushed_at"),
                cloneUrl = o.str("clone_url"),
                sshUrl = o.str("ssh_url"),
                webUrl = o.str("html_url"),
                stars = o.long("stargazers_count"),
                language = o.str("language"),
            )
        }
    }

    /** The declared groups' contents: the provider's own flag decides, each side alphabetical by name. */
    fun group(repos: List<Repo>, wantPrivate: Boolean): List<Repo> =
        repos.filter { it.private == wantPrivate }.sortedBy { it.name.lowercase() }

    /**
     * Every page of the declared list route, up to the declared maximum. Blocking — the
     * caller runs it on an IO dispatcher.
     *
     * A page shorter than the previous one ends the walk; hitting [Declarations.GitApiDecl.maxPages]
     * with a full page ends it too and reports `complete = false`, so the page can SAY the
     * listing was truncated instead of silently showing a prefix of the account.
     */
    fun fetch(api: Declarations.GitApiDecl, token: String): Result<Page> {
        if (api.baseUrl.isBlank() || api.reposPath.isBlank()) return Result.failure(IllegalStateException("no repos route declared"))
        if (token.isBlank()) return Result.failure(IllegalStateException("no token: sign in first"))
        val all = mutableListOf<Repo>()
        var complete = true
        var page = 1
        while (page <= api.maxPages) {
            val batch = get(api.reposUrl(page), token).getOrElse { return Result.failure(it) }
            val parsed = parse(batch)
            all += parsed
            if (parsed.isEmpty()) break
            if (page == api.maxPages) { complete = false; break }
            page++
        }
        // De-duplicate on owner/name: a repository that moved page between two requests
        // would otherwise be listed twice.
        val unique = all.distinctBy { it.owner + "/" + it.name }
        return Result.success(Page(unique, complete))
    }

    private fun get(url: String, token: String): Result<String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                // The body can carry the token back in an echoed URL; only the code is reported.
                return Result.failure(IllegalStateException("HTTP $code from the repository listing"))
            }
            Result.success(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            conn?.disconnect()
        }
    }

    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 20000
    private const val USER_AGENT = "Cloud-Drive-GitPage/1"
}
