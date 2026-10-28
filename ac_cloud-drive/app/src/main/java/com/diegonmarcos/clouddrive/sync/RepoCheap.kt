package com.diegonmarcos.clouddrive.sync

import java.io.File

/**
 * The CHEAP facts of a clone, read with plain java.io and NO git engine: whether it is a clone (a stat of `.git`), its
 * branch and upstream (HEAD and `.git/config`, two tiny files), and a BOUNDED size guard over the working tree. The
 * expensive worktree status (every file hashed or stat-ed, a file list marshalled back from the engine) is what ate the
 * app's memory on large trees, so it only runs for a clone [tooLarge] says is small, or when the owner asks for it.
 */
object RepoCheap {
    data class Head(val branch: String?, val upstream: String?)

    fun isClone(dir: File): Boolean = File(dir, ".git").exists()

    /** HEAD's branch (null = detached / unreadable) and the configured upstream `<remote>/<branch>` (null = none). */
    fun head(dir: File): Head {
        val gitDir = File(dir, ".git").takeIf { it.isDirectory } ?: return Head(null, null)
        val branch = runCatching { File(gitDir, "HEAD").takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
            ?.takeIf { it.startsWith("ref: refs/heads/") }?.removePrefix("ref: refs/heads/")
        if (branch == null) return Head(null, null)
        return Head(branch, upstreamOf(runCatching { File(gitDir, "config").takeIf { it.isFile }?.readText() }.getOrNull().orEmpty(), branch))
    }

    /** Pure: the `[branch "<b>"]` section's remote and merge as `<remote>/<b>`, null when the section is absent or incomplete. */
    fun upstreamOf(config: String, branch: String): String? {
        var inSection = false; var remote: String? = null; var merge: String? = null
        for (raw in config.lineSequence()) {
            val l = raw.trim()
            if (l.startsWith("[")) { inSection = l.replace(" ", "").equals("[branch\"$branch\"]", ignoreCase = true); continue }
            if (!inSection) continue
            val k = l.substringBefore('=').trim(); val v = l.substringAfter('=', "").trim()
            if (k == "remote") remote = v else if (k == "merge") merge = v
        }
        if (remote.isNullOrBlank() || merge.isNullOrBlank()) return null
        return remote + "/" + merge!!.removePrefix("refs/heads/")
    }

    /**
     * True when the working tree has more than [maxFiles] entries or the count did not finish within [maxMs] (`.git`
     * is not counted, symlinks are not followed). Stops at the first bound hit, so it is cheap even on a huge tree.
     */
    fun tooLarge(dir: File, maxFiles: Int, maxMs: Long, clock: () -> Long = System::currentTimeMillis): Boolean {
        val end = clock() + maxMs
        var seen = 0
        val stack = ArrayDeque<File>().apply { add(dir) }
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val names = d.list() ?: continue
            for (n in names) {
                if (d == dir && n == ".git") continue
                if (++seen > maxFiles || clock() > end) return true
                val f = File(d, n)
                if (f.isDirectory && !java.nio.file.Files.isSymbolicLink(f.toPath())) stack.add(f)
            }
        }
        return false
    }
}
