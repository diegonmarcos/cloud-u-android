package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.clouddrive.sync.GitHostsDecl.GitHostDecl
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * #1149 Sync ▸ Git ▸ Me: the hosts beyond GitHub and Cloud git. What is pure lives here so the JVM suite (GitHostsTest)
 * runs the code the page runs against recorded response bodies: the auth header a host wants, how its listing parses
 * onto the SAME [GitHubRepos.Repo] the #1138 table already sorts, and where its secrets sit in the credential store.
 *
 * THE CREDENTIAL IS A PERSONAL ACCESS TOKEN (an app password for Bitbucket) the user pastes: none of these hosts has
 * an OAuth client registered for this app, and inventing one would be a second sign-in mechanism. It is kept in
 * libs:git-sync's [com.diegonmarcos.cloudlib.gitsync.GitCredentialStore] (SafeEncryptedPrefs), under a key per host,
 * and travels only as the Authorization header of the request it was passed for.
 */
object GitHosts {
    const val KIND_GITLAB = "gitlab"
    const val KIND_GITEA = "gitea"
    const val KIND_BITBUCKET = "bitbucket"
    const val KIND_AZURE = "azure"
    val KINDS = listOf(KIND_GITLAB, KIND_GITEA, KIND_BITBUCKET, KIND_AZURE)

    /** The credential-store keys of one host: the token and the user name (or Azure organisation). Distinct from any repo id (`repo-...`). */
    fun tokenKey(hostId: String) = "host:$hostId:token"
    fun userKey(hostId: String) = "host:$hostId:user"

    /** The request headers that authenticate [user] / [token] against a host of [kind]. */
    fun headers(kind: String, user: String, token: String): Map<String, String> = when (kind) {
        KIND_GITLAB -> mapOf("PRIVATE-TOKEN" to token)
        KIND_GITEA -> mapOf("Authorization" to "token $token")
        KIND_BITBUCKET -> mapOf("Authorization" to basic(user, token))
        KIND_AZURE -> mapOf("Authorization" to basic("", token))
        else -> emptyMap()
    }

    private fun basic(user: String, secret: String) = "Basic " + Base64.getEncoder().encodeToString("$user:$secret".toByteArray(Charsets.UTF_8))

    /** The HTTPS user name a clone presents: the host's fixed one, else the name the user signed in with. */
    fun cloneUser(decl: GitHostDecl, user: String): String = decl.cloneUser.ifBlank { user }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
    private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L
    private fun JsonObject.obj(k: String) = this[k] as? JsonObject

    /** The repositories in one response body of a host of [kind]; an error document or garbage is an empty list, never a crash or an invented row. */
    fun parse(kind: String, text: String): List<GitHubRepos.Repo> = when (kind) {
        KIND_GITEA -> GitHubRepos.parse(text)
        KIND_GITLAB -> parseGitlab(text)
        KIND_BITBUCKET -> parseBitbucket(text)
        KIND_AZURE -> parseAzure(text)
        else -> emptyList()
    }

    private fun parseGitlab(text: String): List<GitHubRepos.Repo> =
        (runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val name = o.str("path").ifBlank { o.str("name") }
            if (name.isBlank()) return@mapNotNull null
            GitHubRepos.Repo(
                name = name, owner = o.obj("namespace")?.str("full_path").orEmpty(),
                private = o.str("visibility").let { it.isNotBlank() && it != "public" }, fork = o["forked_from_project"] is JsonObject,
                defaultBranch = o.str("default_branch").ifBlank { "main" }, description = o.str("description"),
                sizeKb = o.obj("statistics")?.long("repository_size")?.div(1024) ?: 0L,
                updatedAt = o.str("last_activity_at"), pushedAt = o.str("last_activity_at"),
                cloneUrl = o.str("http_url_to_repo"), sshUrl = o.str("ssh_url_to_repo"), webUrl = o.str("web_url"),
                stars = o.long("star_count"), language = "",
            )
        }

    private fun parseBitbucket(text: String): List<GitHubRepos.Repo> {
        val values = ((runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject)?.get("values") as? JsonArray).orEmpty()
        return values.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val name = o.str("slug").ifBlank { o.str("name") }
            if (name.isBlank()) return@mapNotNull null
            val links = o.obj("links")
            fun clone(kind: String) = ((links?.get("clone") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.firstOrNull { it.str("name") == kind })?.str("href").orEmpty()
            GitHubRepos.Repo(
                name = name, owner = o.obj("workspace")?.str("slug").orEmpty().ifBlank { o.str("full_name").substringBefore('/') },
                private = o.bool("is_private"), fork = o["parent"] is JsonObject,
                defaultBranch = o.obj("mainbranch")?.str("name").orEmpty().ifBlank { "main" }, description = o.str("description"),
                sizeKb = o.long("size") / 1024, updatedAt = o.str("updated_on"), pushedAt = o.str("updated_on"),
                cloneUrl = clone("https"), sshUrl = clone("ssh"), webUrl = links?.obj("html")?.str("href").orEmpty(),
                stars = 0, language = o.str("language"),
            )
        }
    }

    private fun parseAzure(text: String): List<GitHubRepos.Repo> {
        val values = ((runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject)?.get("value") as? JsonArray).orEmpty()
        return values.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val name = o.str("name")
            if (name.isBlank()) return@mapNotNull null
            val project = o.obj("project")
            GitHubRepos.Repo(
                name = name, owner = project?.str("name").orEmpty(),
                private = project?.str("visibility").let { it != null && it.isNotBlank() && it != "public" }, fork = false,
                defaultBranch = o.str("defaultBranch").removePrefix("refs/heads/").ifBlank { "main" }, description = "",
                sizeKb = o.long("size") / 1024, updatedAt = "", pushedAt = "",
                cloneUrl = o.str("remoteUrl"), sshUrl = o.str("sshUrl"), webUrl = o.str("webUrl"),
                stars = 0, language = "",
            )
        }
    }

    /** What a signed-in host answered: the repositories (alphabetical), whether the walk ended on a short page, or a one-line refusal. */
    data class Listing(val repos: List<GitHubRepos.Repo>, val complete: Boolean)

    /**
     * Every page of [decl]'s list route with the host's own auth header, up to the declared maximum; a page that
     * adds nothing new ends the walk (Azure answers its whole list for any `page`). Blocking: run it on an IO dispatcher.
     */
    fun fetch(decl: GitHostDecl, user: String, token: String): Result<Listing> {
        if (token.isBlank()) return Result.failure(IllegalStateException("no token: sign in first"))
        if (decl.needsUser && user.isBlank()) return Result.failure(IllegalStateException("no ${decl.userLabel.ifBlank { "user" }}"))
        val headers = headers(decl.kind, user, token) + ("Accept" to "application/json")
        val all = LinkedHashMap<String, GitHubRepos.Repo>()
        var complete = true
        var page = 1
        while (page <= decl.maxPages) {
            val body = GitHubRepos.get(decl.reposUrl(user, page), headers).getOrElse { return Result.failure(it) }
            val before = all.size
            parse(decl.kind, body).forEach { r -> val k = r.owner + "/" + r.name; if (k !in all) all[k] = r }
            if (all.size == before) break
            if (page == decl.maxPages) complete = false
            page++
        }
        return Result.success(Listing(all.values.sortedBy { it.name.lowercase() }, complete))
    }
}
