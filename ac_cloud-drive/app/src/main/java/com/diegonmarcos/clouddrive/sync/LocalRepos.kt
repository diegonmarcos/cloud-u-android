package com.diegonmarcos.clouddrive.sync

import java.io.File

/**
 * #1149 Sync ▸ Git ▸ Me ▸ Local Repos Parentless: the local folders that hold a `.git` and whose NAME is not
 * the name of the repository their `origin` points at (a clone made under another name, a folder renamed by hand).
 *
 * Pure Kotlin over [File], like [GitRepoScan]: the JVM suite (LocalReposTest) runs the exact code the page runs
 * against real temporary directories. Reading the origin does not need git: it is one section of `.git/config`.
 *
 * THE COMPARISON IS CASE-INSENSITIVE. GitHub names are case-insensitive, an origin URL typed in another case still
 * reaches the same repository, and Android's shared storage is a case-insensitive volume, so a case-only difference
 * is neither a different repository nor a rename the file system can be trusted to perform.
 */
object LocalRepos {

    /** A folder that holds a repository. [origin] is the URL of remote `origin` ("" = none), [repoName] its basename ("" = none). */
    data class Found(val dir: File, val origin: String, val repoName: String)

    /** A [Found] whose folder name is not [Found.repoName]; [target] is the folder name the rename would give it. */
    data class Parentless(val dir: File, val origin: String, val repoName: String) {
        val target: File get() = File(dir.parentFile, repoName)
    }

    sealed interface Outcome {
        val from: File
        /** [conflict] = the repository name was taken, so the folder became <name>___CONFLICT[n] (#1288). */
        data class Renamed(override val from: File, val to: File, val conflict: Boolean = false) : Outcome
        /** Every name, the conflict names included, is already there: nothing was touched. */
        data class TargetExists(override val from: File, val to: File) : Outcome
        data class Missing(override val from: File) : Outcome
        /** The repository name is not a usable folder name (a path separator, a dot name). */
        data class Invalid(override val from: File, val name: String) : Outcome
        data class Failed(override val from: File, val to: File) : Outcome
    }

    /**
     * The repository name an origin URL ends in: the last path segment with a trailing slash and a `.git` suffix
     * removed. Handles https, ssh:// and scp-like (`git@host:owner/name.git`) forms and a local path; "" when the URL
     * has no usable last segment.
     */
    fun repoNameOf(url: String): String {
        var u = url.trim().substringBefore('?').substringBefore('#').trimEnd('/')
        if (u.endsWith(".git", ignoreCase = true)) u = u.dropLast(4).trimEnd('/')
        val seg = u.substringAfterLast('/').substringAfterLast(':')
        return if (seg.isBlank() || seg == "." || seg == "..") "" else seg
    }

    /** Whether a folder called [folder] is parentless against a repository called [repoName] (a repo with no name never is). */
    fun isParentless(folder: String, repoName: String): Boolean = repoName.isNotBlank() && !folder.equals(repoName, ignoreCase = true)

    /** `remote "origin"`'s url out of git-config text; "" when the section or its url is absent. */
    fun originFromConfig(config: String): String {
        var inOrigin = false
        for (raw in config.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inOrigin = Regex("""^\[\s*remote\s+"origin"\s*]""", RegexOption.IGNORE_CASE).containsMatchIn(line)
                continue
            }
            if (inOrigin && line.startsWith("url", ignoreCase = true)) {
                val eq = line.indexOf('=')
                if (eq > 0 && line.substring(0, eq).trim().equals("url", ignoreCase = true)) return line.substring(eq + 1).trim()
            }
        }
        return ""
    }

    /** The git directory of [dir]: its `.git` folder, or the folder a `.git` FILE (`gitdir: ...`: a worktree or submodule) points at. */
    private fun gitDirOf(dir: File): File? {
        val dot = File(dir, ".git")
        if (dot.isDirectory) return dot
        if (!dot.isFile) return null
        val target = runCatching { dot.readText().lineSequence().firstOrNull { it.startsWith("gitdir:") } }.getOrNull()?.removePrefix("gitdir:")?.trim() ?: return null
        val f = File(target)
        return (if (f.isAbsolute) f else File(dir, target)).takeIf { it.isDirectory }
    }

    /** The URL of `origin` of the repository in [dir], read from its git config ("" = none / unreadable). A worktree's config is its common dir's. */
    fun originOf(dir: File): String {
        var git = gitDirOf(dir) ?: return ""
        val common = File(git, "commondir")
        if (!File(git, "config").isFile && common.isFile) {
            val c = runCatching { common.readText().trim() }.getOrDefault("")
            if (c.isNotBlank()) git = File(c).let { if (it.isAbsolute) it else File(git, c) }
        }
        val config = File(git, "config")
        return if (config.isFile) runCatching { originFromConfig(config.readText()) }.getOrDefault("") else ""
    }

    /** Every direct child of each of [roots] that holds a `.git`, name order, each folder once. */
    fun scan(roots: List<File>): List<Found> =
        roots.distinctBy { it.absolutePath }
            .flatMap { root -> root.listFiles().orEmpty().filter { it.isDirectory && File(it, ".git").exists() } }
            .distinctBy { it.absolutePath }
            .sortedBy { it.name.lowercase() }
            .map { d -> originOf(d).let { Found(d, it, repoNameOf(it)) } }

    /** The folders whose name is not their origin's repository name. */
    fun parentless(found: List<Found>): List<Parentless> =
        found.filter { isParentless(it.dir.name, it.repoName) }.map { Parentless(it.dir, it.origin, it.repoName) }

    private fun usable(name: String): Boolean = name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\u0000' !in name

    /**
     * Rename [p]'s folder to its repository's name. #1288 when anything is already called that, the folder becomes
     * <name>___CONFLICT (then ___CONFLICT2 ...: [FolderActions.conflictName]); nothing is ever overwritten or merged.
     */
    fun rename(p: Parentless): Outcome {
        val parent = p.dir.parentFile ?: return Outcome.Missing(p.dir)
        if (!p.dir.isDirectory) return Outcome.Missing(p.dir)
        if (!usable(p.repoName)) return Outcome.Invalid(p.dir, p.repoName)
        val name = FolderActions.renameTarget(p.repoName) { File(parent, it).exists() } ?: return Outcome.TargetExists(p.dir, p.target)
        val to = File(parent, name)
        return if (p.dir.renameTo(to)) Outcome.Renamed(p.dir, to, conflict = name != p.repoName) else Outcome.Failed(p.dir, to)
    }

    /** [rename] each row in order; a failed row does not stop the others, and two rows wanting one name cannot both win (the second gets ___CONFLICT). */
    fun renameAll(rows: List<Parentless>): List<Outcome> = rows.map { rename(it) }

    /**
     * Where [path] lives after the folder [old] became [new]: [new] itself, or [path] re-rooted under it. Null when
     * [path] is neither [old] nor inside it. A sibling that merely starts with the same letters is not inside.
     */
    fun repointPath(path: String, old: String, new: String): String? {
        val o = old.trimEnd('/')
        val n = new.trimEnd('/')
        return when {
            path.trimEnd('/') == o -> n
            path.startsWith("$o/") -> n + path.substring(o.length)
            else -> null
        }
    }
}
