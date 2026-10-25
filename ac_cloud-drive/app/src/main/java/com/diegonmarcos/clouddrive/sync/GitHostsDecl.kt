package com.diegonmarcos.clouddrive.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * #1149 the two declarations the Git page's Me and Cloud sub-pages read, both siblings of `git` under
 * build.json::ui.sync (the page holds no host, endpoint, owner or prefix of its own):
 *
 *  - `cloud_list`  the PUBLIC list behind Cloud: [CloudListDecl].
 *  - `git_hosts`   the hosts Me signs in to besides the GitHub and Cloud-git ways: [GitHostDecl].
 */
object GitHostsDecl {

    /** Cloud: the owner's repositories whose name starts with [namePrefix] and which are public, over the REST list [path] of the GitHub API base. */
    data class CloudListDecl(val owner: String, val namePrefix: String, val path: String, val maxPages: Int) {
        fun url(base: String, page: Int): String {
            val p = path.replace("{owner}", owner)
            return base + p + (if (p.contains('?')) "&" else "?") + "page=" + page.coerceAtLeast(1)
        }
    }

    /**
     * One host Me can sign in to. [kind] picks the listing parser and the auth header ([GitHosts]); [apiBase] + [reposPath]
     * is the list route; [tokenUrl] is where the user mints a token; [needsUser] asks for a user name (or the Azure
     * organisation, [userLabel]); [cloneUser] is the HTTPS user name a clone presents when the host fixes one.
     */
    data class GitHostDecl(
        val id: String,
        val label: String,
        val icon: String,
        val kind: String,
        val apiBase: String,
        val reposPath: String,
        val tokenUrl: String,
        val needsUser: Boolean,
        val userLabel: String,
        val cloneUser: String,
        val maxPages: Int,
    ) {
        fun reposUrl(user: String, page: Int): String {
            val p = reposPath.replace("{user}", user)
            return apiBase.replace("{user}", user) + p + (if (p.contains('?')) "&" else "?") + "page=" + page.coerceAtLeast(1)
        }
    }

    /** `ui.sync.providers`: [hosts] host -> provider id, [labels] provider id -> the column's word. */
    data class ProvidersDecl(val hosts: Map<String, String>, val labels: Map<String, String>)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private fun JsonObject.str(k: String, d: String = ""): String = (this[k] as? JsonPrimitive)?.contentOrNull ?: d
    private fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.contentOrNull == "true"

    private fun sync(text: String): JsonObject? = if (text.isBlank()) null else runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    /** `ui.sync.cloud_list` out of the decoded ui.sync text; an empty declaration (no owner, no prefix) when it is absent. */
    fun parseCloud(syncText: String): CloudListDecl {
        val o = sync(syncText)?.get("cloud_list") as? JsonObject ?: return CloudListDecl("", "", "", 0)
        return CloudListDecl(o.str("owner"), o.str("name_prefix"), o.str("path"), (o["max_pages"] as? JsonPrimitive)?.intOrNull ?: 1)
    }

    /** `ui.sync.git_hosts`, in declared order; an entry without an id, kind or list route is dropped. */
    fun parseHosts(syncText: String): List<GitHostDecl> =
        (sync(syncText)?.get("git_hosts") as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val d = GitHostDecl(
                id = o.str("id"), label = o.str("label"), icon = o.str("icon", "cloud"), kind = o.str("kind"),
                apiBase = o.str("api_base"), reposPath = o.str("repos_path"), tokenUrl = o.str("token_url"),
                needsUser = o.bool("needs_user"), userLabel = o.str("user_label"), cloneUser = o.str("clone_user"),
                maxPages = (o["max_pages"] as? JsonPrimitive)?.intOrNull ?: 1,
            )
            d.takeIf { it.id.isNotBlank() && it.kind.isNotBlank() && it.apiBase.isNotBlank() && it.reposPath.isNotBlank() }
        }

    /** `ui.sync.providers` out of the decoded ui.sync text; empty maps when it is absent (every repository is then `other`). */
    fun parseProviders(syncText: String): ProvidersDecl {
        val o = sync(syncText)?.get("providers") as? JsonObject ?: return ProvidersDecl(emptyMap(), emptyMap())
        fun map(k: String) = (o[k] as? JsonObject).orEmpty().mapNotNull { (key, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { key.lowercase() to it } }.toMap()
        return ProvidersDecl(map("hosts"), map("labels"))
    }
}
