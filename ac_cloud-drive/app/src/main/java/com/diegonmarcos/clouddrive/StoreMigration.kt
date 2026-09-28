package com.diegonmarcos.clouddrive

import java.io.File

/**
 * #575/#606 ONE-TIME migration of stray root clones into the store's git folder.
 *
 * The store used to clone repositories at `<shared_root>/<name>` (SharedStore.root()); #606
 * moved every clone under `<shared_root>/<git_subdir>/<name>` (SharedStore.gitRoot()) so that
 * repositories are distinguishable from the user's own folders in the plain-shared-storage
 * store. A phone updated across that change is left with clones sitting at the OLD root — the
 * seed then re-clones them into the new git folder and the user sees the same repository twice.
 *
 * This relocates them, once, driven ONLY by the manifest (the declared repository names) — no
 * hardcoded repository name. It is pure java.io.File over the store so the JVM suite exercises
 * exactly the phone's logic (StoreMigrationTest), and it is idempotent: a repository already in
 * the git folder is left untouched, and a stray whose destination is taken is LEFT where it is
 * (a migration never overwrites an existing clone) and reported so the caller can log it.
 */
object StoreMigration {

    /** One stray considered. [moved] false ⇒ the destination already existed and the stray was left alone. */
    data class Move(val name: String, val from: File, val to: File, val moved: Boolean)

    /** A directory is a clone when it holds a `.git` entry (a working tree; a bare `.git` dir or file). */
    fun isClone(dir: File): Boolean = dir.isDirectory && File(dir, ".git").exists()

    /**
     * Relocate every clone directly under [root] whose folder name is one of [repoNames] into
     * `[root]/[gitSubdir]/<name>`. The git folder itself is skipped (a clone already in it is not
     * a stray), a directory that is not a clone is skipped, and a stray whose destination already
     * exists is left in place. Returns one [Move] per stray acted on, in listing order.
     */
    fun migrate(root: File, gitSubdir: String, repoNames: Set<String>): List<Move> {
        val gitRoot = File(root, gitSubdir)
        val strays = root.listFiles()?.filter { it.isDirectory && it.name != gitSubdir && it.name in repoNames && isClone(it) }
            ?: return emptyList()
        return strays.map { stray ->
            val dest = File(gitRoot, stray.name)
            if (dest.exists()) {
                Move(stray.name, stray, dest, moved = false)
            } else {
                gitRoot.mkdirs()
                val ok = stray.renameTo(dest) ||
                    (runCatching { stray.copyRecursively(dest, overwrite = false) }.getOrDefault(false) && stray.deleteRecursively())
                Move(stray.name, stray, dest, moved = ok)
            }
        }
    }
}
