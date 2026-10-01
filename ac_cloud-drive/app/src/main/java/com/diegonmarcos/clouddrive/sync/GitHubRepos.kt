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
                // #653 BOTH SPELLINGS, because both sources are real: GitHub's own body
                // says `size`, and the fleet proxy's projection says `size_kb` (its
                // declared contract renames the field). Reading one and not the other
                // would silently show every repository as 0 KB on the fleet path.
                sizeKb = if (o["size"] != null) o.long("size") else o.long("size_kb"),
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

    /**
     * #689 the `--json` fields `gh repo list` is asked for: exactly the ones [parseGh] reads, each
     * one in the pinned gh 2.101.0's own field list (measured).
     */
    const val GH_FIELDS = "name,owner,isPrivate,isFork,url,sshUrl,defaultBranchRef,description,diskUsage,updatedAt,pushedAt,stargazerCount,primaryLanguage"

    /**
     * #689 the repositories in `gh repo list --json` [GH_FIELDS] output, mapped onto the same [Repo]
     * the page already groups, so Public and Private split on gh's OWN isPrivate flag. gh folds any
     * warning into the same stream, so the array is the output's last line that opens one (gh prints
     * it compact on one line when no TTY is attached, measured). The clone URL is the listing's own
     * `url`, which GitHub serves git at — never re-templated from a declared owner (#669). Output
     * with no readable array is NULL, which the caller reports as unreadable: an empty account (`[]`)
     * and a garbled answer are two different facts.
     */
    fun parseGh(text: String): List<Repo>? {
        val line = text.lineSequence().lastOrNull { it.trimStart().startsWith("[") } ?: return null
        val root = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonArray ?: return null
        return root.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val name = o.str("name")
            if (name.isBlank()) return@mapNotNull null
            Repo(
                name = name,
                owner = (o["owner"] as? JsonObject)?.str("login").orEmpty(),
                private = o.bool("isPrivate"),
                fork = o.bool("isFork"),
                defaultBranch = (o["defaultBranchRef"] as? JsonObject)?.str("name").orEmpty().ifBlank { "main" },
                description = o.str("description"),
                sizeKb = o.long("diskUsage"),
                updatedAt = o.str("updatedAt"),
                pushedAt = o.str("pushedAt"),
                cloneUrl = o.str("url"),
                sshUrl = o.str("sshUrl"),
                webUrl = o.str("url"),
                stars = o.long("stargazerCount"),
                language = (o["primaryLanguage"] as? JsonObject)?.str("name").orEmpty(),
            )
        }
    }

    /** #689 gh's own word on one host: the account it holds and gh's state for it ("" = none held). */
    data class GhStatus(val login: String, val state: String) {
        /** Signed in only in gh's own state "success": a stored but unconfirmed login is not. */
        val signedIn: Boolean get() = state == "success" && login.isNotBlank()
    }

    /**
     * #689 what `gh auth status --json hosts` says about [host]: the active entry's login and state.
     * Signed out, gh still exits 0 with `{"hosts":{}}` (measured), so this reads the JSON and never
     * the exit code. A login gh holds but GitHub did not confirm (offline, revoked) keeps its state,
     * so the page can say THAT instead of "not signed in". The JSON carries no token.
     */
    fun ghStatus(text: String, host: String): GhStatus {
        val none = GhStatus("", "")
        val line = text.lineSequence().lastOrNull { it.trimStart().startsWith("{") } ?: return none
        val root = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: return none
        val entries = ((root["hosts"] as? JsonObject)?.get(host) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val entry = entries.firstOrNull { it.bool("active") } ?: entries.firstOrNull() ?: return none
        return GhStatus(entry.str("login"), entry.str("state"))
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
