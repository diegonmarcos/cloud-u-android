package com.diegonmarcos.clouddrive.sync

/**
 * #1282 CLONE BARE's PURE RULES (build.json ui.sync.git.depth_actions kind `clone_bare`, root ui.sync.git.bare_root),
 * JVM-tested in BareClonesTest.
 *
 *  - PATH: a bare mirror lives at <store git>/<bare_root>/<owner>/<repo>.git, the repository's CURRENT (canonical) name.
 *  - ONE BARE PER REPOSITORY: identity is [BulkDepth.identity] of the mirror's origin URL (renamed_from chains folded in),
 *    so a mirror made under an old name still counts. A target already mirrored is UPDATED (git remote update --prune),
 *    never cloned twice; two targets naming the same repository yield one action.
 *  - The working clone is never touched: the bare root is its own tree and only `clone --mirror` / `remote update` run there.
 *  - Depth: the declared depth, 0 = full history (the declared default for the bare backup form).
 */
object BareClones {

    /** A bare mirror found on the device: its folder and its origin URL. */
    data class Mirror(val path: String, val origin: String)
    data class Target(val name: String, val url: String)
    data class Plan(val toClone: List<Target>, /** name -> existing mirror path to update */ val toUpdate: Map<String, String>)

    private val SAFE = Regex("[A-Za-z0-9._-]+")

    /** The owner segment of a clone URL (https, ssh or scp form); "" when it has none. */
    fun ownerOf(url: String): String {
        var u = url.trim().substringBefore('?').substringBefore('#').trimEnd('/')
        if (u.endsWith(".git", ignoreCase = true)) u = u.dropLast(4)
        val parts = u.substringAfter("://", u).replace(':', '/').split('/').filter { it.isNotEmpty() }
        return if (parts.size >= 3 || (parts.size == 2 && "://" !in url)) parts[parts.size - 2].substringAfterLast('@') else ""
    }

    /** The declared bare root must be a plain relative folder. */
    fun validRoot(root: String): Boolean = root.isNotBlank() && !root.startsWith("/") && root.split('/').all { SAFE.matches(it) && it != ".." && it != "." }

    /** <gitRoot>/<root>/<owner>/<canonical repo>.git; null when a segment is unsafe. */
    fun path(gitRoot: String, root: String, url: String, name: String, canonical: (String) -> String): String? {
        val owner = ownerOf(url).ifBlank { "_" }
        val repo = canonical(name.ifBlank { LocalRepos.repoNameOf(url) })
        if (!validRoot(root) || !SAFE.matches(owner) || !SAFE.matches(repo) || owner == ".." || repo == "..") return null
        return gitRoot.trimEnd('/') + "/" + root + "/" + owner + "/" + repo + ".git"
    }

    /** The `[remote "origin"] url` of a bare repository's config text; "" when none. */
    fun originOfConfig(config: String): String {
        var inOrigin = false
        for (raw in config.lineSequence()) {
            val l = raw.trim()
            if (l.startsWith("[")) inOrigin = l.replace(" ", "").equals("[remote\"origin\"]", ignoreCase = true)
            else if (inOrigin && l.substringBefore('=').trim().equals("url", ignoreCase = true)) return l.substringAfter('=').trim()
        }
        return ""
    }

    fun plan(targets: List<Target>, mirrors: List<Mirror>, canonical: (String) -> String): Plan {
        val have = LinkedHashMap<String, String>()
        mirrors.forEach { m ->
            val id = BulkDepth.identity(m.origin.ifBlank { m.path.substringAfterLast('/').removeSuffix(".git") }, canonical)
            if (id.isNotEmpty()) have.putIfAbsent(id, m.path)
        }
        val seen = HashSet<String>()
        val clone = ArrayList<Target>(); val update = LinkedHashMap<String, String>()
        targets.forEach { t ->
            val id = BulkDepth.identity(t.url.ifBlank { t.name }, canonical).ifEmpty { BulkDepth.identity(t.name, canonical) }
            if (id.isEmpty() || !seen.add(id)) return@forEach
            val at = have[id]
            if (at != null) update[t.name] = at else clone += t
        }
        return Plan(clone, update)
    }

    /** The shell for one bare clone (run in cld.termux): the mirror at [guestDir], [depth] 0 = full history. */
    fun cloneArgs(url: String, guestDir: String, depth: Int, q: (String) -> String): String =
        "clone --mirror " + (if (depth > 0) "--depth $depth " else "") + q(url) + " " + q(guestDir)

    fun updateArgs(guestDir: String, q: (String) -> String): String = "-C " + q(guestDir) + " remote update --prune"

    /** Identity -> mirror path, for the page's "bare ✓" indicator. */
    fun index(mirrors: List<Mirror>, canonical: (String) -> String): Map<String, String> =
        mirrors.mapNotNull { m -> BulkDepth.identity(m.origin.ifBlank { m.path.substringAfterLast('/').removeSuffix(".git") }, canonical).takeIf { it.isNotEmpty() }?.let { it to m.path } }.toMap()
}
