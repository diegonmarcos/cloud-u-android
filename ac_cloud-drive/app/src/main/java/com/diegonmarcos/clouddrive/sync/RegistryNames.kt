package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.clouddrive.Declarations

/**
 * #1317 THE REGISTRY'S NAMES FOR A FOLDER OR AN ORIGIN. A clone made before a GitHub rename keeps the OLD name in its origin URL
 * (GitHub redirects it), and a folder may carry either the registry name (`_git-repos-master`) or the GitHub name. Every match and
 * every rename target goes through here: an old name (any `renamed_from`, case-insensitive, chains folded) resolves to the CURRENT
 * registry entry; a name the registry does not know stays itself.
 */
class RegistryNames(private val sot: Declarations.RepoSotDecl) {

    private val index: Map<String, Declarations.RepoSotEntry> by lazy {
        val m = LinkedHashMap<String, Declarations.RepoSotEntry>()
        // current names first, so an old name never shadows a live repository
        sot.repos.forEach { e -> m.putIfAbsent(e.name.lowercase(), e); m.putIfAbsent(e.github.lowercase(), e) }
        sot.repos.forEach { e -> e.renamedFrom.forEach { o -> m.putIfAbsent(o.lowercase(), e) } }
        sot.renameMap.forEach { (o, n) -> m[n.lowercase()]?.let { m.putIfAbsent(o.lowercase(), it) } }
        m
    }

    fun entryOf(name: String): Declarations.RepoSotEntry? = index[name.trim().lowercase()]

    /** The folder name the registry gives [name] (its registry name); [name] itself when unknown. */
    fun currentName(name: String): String = entryOf(name)?.name ?: name

    /** The GitHub repository name of [name] (what an origin URL ends in); [name] itself when unknown. */
    fun githubName(name: String): String = entryOf(name)?.github ?: name

    /** True when [name] is a retired name of a registry repository (a `renamed_from`), not its current registry or GitHub name. */
    fun isOld(name: String): Boolean {
        val e = entryOf(name) ?: return false
        return !name.trim().equals(e.name, true) && !name.trim().equals(e.github, true)
    }

    /** A folder called [folder] already carries the right name for a repository called [repoName] (the registry name or the GitHub name, either case). */
    fun carries(folder: String, repoName: String): Boolean {
        if (folder.equals(repoName, true)) return true
        val e = entryOf(repoName) ?: return false
        return folder.equals(e.name, true) || folder.equals(e.github, true)
    }

    companion object {
        /** No registry known: every name is itself (the pre-#1317 behaviour, for callers without one). */
        @Volatile private var memo: Pair<Declarations.RepoSotDecl, RegistryNames>? = null
        /** The names of the registry the app runs with now (built once per registry object). */
        fun live(): RegistryNames {
            val s = Declarations.repoSot
            memo?.let { if (it.first === s) return it.second }
            return RegistryNames(s).also { memo = s to it }
        }
        val NONE = RegistryNames(Declarations.RepoSotDecl(emptyList()))
    }
}
