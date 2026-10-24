package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.clouddrive.sync.GitHostsDecl.CloudListDecl

/**
 * #1149 Sync ▸ Git ▸ Cloud: the PUBLIC cloud* repositories of the declared owner, listed live over the GitHub REST
 * route with NO credential (the owner, the prefix and the route are build.json::ui.sync.cloud_list). [filter] is the
 * rule and is pure, so CloudReposTest runs the exact code the page runs against a recorded body.
 */
object CloudRepos {

    /** [repos] that are public and whose name starts with [prefix] (case-insensitive), alphabetical by name. */
    fun filter(repos: List<GitHubRepos.Repo>, prefix: String): List<GitHubRepos.Repo> =
        if (prefix.isBlank()) emptyList()
        else repos.filter { !it.private && it.name.startsWith(prefix, ignoreCase = true) }.sortedBy { it.name.lowercase() }

    /**
     * Every page of the declared route (no Authorization header), filtered. Blocking: run it on an IO dispatcher.
     * A short or empty page ends the walk; running out of pages on a full one is reported as incomplete.
     */
    fun fetch(base: String, decl: CloudListDecl): Result<GitHubRepos.Page> {
        if (base.isBlank() || decl.owner.isBlank() || decl.path.isBlank()) return Result.failure(IllegalStateException("no public list declared"))
        val all = LinkedHashMap<String, GitHubRepos.Repo>()
        var complete = true
        var page = 1
        while (page <= decl.maxPages.coerceAtLeast(1)) {
            val body = GitHubRepos.get(decl.url(base, page), mapOf("Accept" to "application/vnd.github+json")).getOrElse { return Result.failure(it) }
            val before = all.size
            GitHubRepos.parse(body).forEach { r -> val k = r.owner + "/" + r.name; if (k !in all) all[k] = r }
            if (all.size == before) break
            if (page == decl.maxPages) { complete = false; break }
            page++
        }
        return Result.success(GitHubRepos.Page(filter(all.values.toList(), decl.namePrefix), complete))
    }
}
