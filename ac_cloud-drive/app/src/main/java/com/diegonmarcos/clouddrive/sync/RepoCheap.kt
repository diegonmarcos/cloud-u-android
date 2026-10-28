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

    /**
     * Ahead / behind from REFS ONLY (no worktree, no object parsing): [localSha] and [remoteSha] are the tips of the branch
     * and its upstream, [localLog] is the newest-first sha list of the branch (bounded). Equal tips = 0/0; the remote tip
     * inside the local history = ahead by its index; otherwise the remote holds commits this history lacks: behind >= 1
     * ([approx] = the exact counts need the full check). null = no upstream tip to compare with.
     */
    data class Refs(val ahead: Int, val behind: Int, val approx: Boolean)
    fun aheadBehind(localSha: String?, remoteSha: String?, localLog: List<String>): Refs? {
        if (localSha == null || remoteSha == null) return null
        if (localSha == remoteSha) return Refs(0, 0, false)
        val i = localLog.indexOf(remoteSha)
        return if (i >= 0) Refs(i, 0, false) else Refs(0, 1, true)
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

    /** The sha a ref points at: the loose file first, then packed-refs. null = no such ref. */
    fun refSha(gitDir: File, ref: String): String? {
        runCatching { File(gitDir, ref).takeIf { it.isFile }?.readText()?.trim() }.getOrNull()?.takeIf { it.length >= 40 && !it.startsWith("ref:") }?.let { return it }
        val packed = runCatching { File(gitDir, "packed-refs").takeIf { it.isFile }?.readLines() }.getOrNull() ?: return null
        return packed.firstOrNull { !it.startsWith("#") && !it.startsWith("^") && it.substringAfter(' ', "") == ref }?.substringBefore(' ')
    }

    /**
     * THE LIGHT READ, pure java.io (no git engine, no Context, no other process): branch + upstream from HEAD and
     * `.git/config`, the two tips from the ref files, and, only when the tips differ, [localLog] (newest-first shas of the
     * branch, supplied by the caller) to count ahead / behind. Never touches the working tree. Throws when [dir] has no
     * readable `.git` directory (a message naming the exact path), so the caller's row ends in ERROR rather than guessing.
     */
    fun lightProbe(dir: File, localLog: (branch: String) -> List<String> = { emptyList() }): GitStatusReader.Probe {
        val gitDir = File(dir, ".git")
        if (!gitDir.isDirectory) throw IllegalStateException("not a git repository: no readable .git directory in ${dir.absolutePath}")
        val fetched = File(gitDir, "FETCH_HEAD").lastModified()
        val h = head(dir)
        val branch = h.branch ?: return GitStatusReader.Probe(null, null, 0, 0, 0, 0, fetchedAtMs = fetched, via = "files")
        val upstream = h.upstream ?: return GitStatusReader.Probe(branch, null, 0, 0, 0, 0, fetchedAtMs = fetched, via = "files")
        val local = refSha(gitDir, "refs/heads/$branch")
        val remote = refSha(gitDir, "refs/remotes/$upstream")
        val log = if (local != null && remote != null && local != remote) localLog(branch) else emptyList()
        val r = aheadBehind(local, remote, log)
        return GitStatusReader.Probe(branch, upstream, r?.ahead ?: 0, r?.behind ?: 0, 0, 0, fetchedAtMs = fetched, via = "files")
    }

    /**
     * The same light read as ONE script for git inside cld.termux (a store repository is invisible to this app's java.io
     * when the store is owned by cld.termux). [guestDir] is the already shell-quoted guest path. Output lines: NOREPO |
     * B:<branch> U:<remote>/<branch> LR:<ahead> <behind> F:<fetch epoch seconds>.
     */
    fun lightScript(guestDirQuoted: String): String =
        "cd $guestDirQuoted 2>/dev/null && git rev-parse --git-dir >/dev/null 2>&1 || { echo NOREPO; exit 0; }; " +
            "b=\$(git symbolic-ref -q --short HEAD); echo \"B:\$b\"; " +
            "if [ -n \"\$b\" ]; then r=\$(git config branch.\"\$b\".remote); m=\$(git config branch.\"\$b\".merge); " +
            "if [ -n \"\$r\" ] && [ -n \"\$m\" ]; then u=\"\$r/\${m#refs/heads/}\"; echo \"U:\$u\"; " +
            "echo \"LR:\$(git rev-list --left-right --count HEAD...\"refs/remotes/\$u\" 2>/dev/null)\"; fi; fi; " +
            "echo \"F:\$(stat -c %Y \"\$(git rev-parse --git-dir)/FETCH_HEAD\" 2>/dev/null)\""

    /** Pure: [lightScript]'s output as a Probe; throws when the guest says it is not a repository. */
    fun parseLight(out: String): GitStatusReader.Probe {
        if (out.lineSequence().any { it.trim() == "NOREPO" }) throw IllegalStateException("not a git repository (git in cld.termux found none)")
        fun v(k: String) = out.lineSequence().firstOrNull { it.startsWith("$k:") }?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }
        val lr = v("LR")?.split(Regex("\\s+"))?.mapNotNull { it.toIntOrNull() }
        return GitStatusReader.Probe(
            v("B"), v("U"), lr?.getOrNull(0) ?: 0, lr?.getOrNull(1) ?: 0, 0, 0,
            fetchedAtMs = (v("F")?.toLongOrNull() ?: 0L) * 1000L, via = "exec",
        )
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
