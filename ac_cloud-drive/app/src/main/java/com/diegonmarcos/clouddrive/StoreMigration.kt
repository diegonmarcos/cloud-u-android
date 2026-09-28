package com.diegonmarcos.clouddrive

import java.io.File

/**
 * #575/#606/#629 ONE-TIME migration of stray root clones into the store's git folder.
 *
 * The store used to clone repositories at `<shared_root>/<name>` (SharedStore.root()); #606
 * moved every clone under `<shared_root>/<git_subdir>/<name>` (SharedStore.gitRoot()) so that
 * repositories are distinguishable from the user's own folders in the plain-shared-storage
 * store. A phone updated across that change is left with clones sitting at the OLD root.
 *
 * #629 THE MEASURED REALITY THE FIRST DRAFT GOT WRONG. On the owner's device BOTH copies of a
 * repository exist and BOTH are partially complete, in either direction: `cloud-u-linux` was
 * complete at the root (7 files, a resolvable HEAD) while `git/cloud-u-linux` was an empty
 * husk, and `cloud` / `cloud-infra` were the exact reverse. The #606 rule — "a stray whose
 * destination is taken is left alone" — therefore left the good copy stranded at the root
 * forever, and a naive "always move" would have clobbered the good copy in the git folder.
 *
 * So the rule is COMPLETENESS, not position: [completeness] scores each side (is it a clone ·
 * does its HEAD resolve · is its worktree populated), the COMPLETE copy is the one that ends up
 * at `git/<name>`, the move is VERIFIED before anything is deleted, and a copy is removed only
 * when the survivor is proven complete — never an only copy, never on a tie of husks. Every
 * decision comes back as a [Move] the caller logs: nothing is a silent skip. Pure java.io.File
 * over the store, so StoreMigrationTest exercises exactly the logic the phone runs.
 */
object StoreMigration {

    /**
     * What was decided for one repository name. [moved] = the root copy now lives at [to];
     * [removed] = the root copy is gone (either because it moved, or because it was redundant);
     * [decision] is the sentence the caller logs.
     */
    data class Move(
        val name: String,
        val from: File,
        val to: File,
        val moved: Boolean,
        val decision: String = "",
        val removed: Boolean = false,
    )

    /** A directory is a clone when it holds a `.git` entry (a working tree; a bare `.git` dir or file). */
    fun isClone(dir: File): Boolean = dir.isDirectory && File(dir, ".git").exists()

    /**
     * Whether `HEAD` names something this checkout actually has: a raw sha, or a `ref:` whose
     * loose ref file exists or which `packed-refs` carries. An empty or dangling HEAD is what a
     * clone interrupted before its first checkout leaves behind — the husks measured on the device.
     */
    fun headResolves(dir: File): Boolean {
        val gitDir = gitDir(dir) ?: return false
        val head = File(gitDir, "HEAD").takeIf { it.isFile }?.readText()?.trim().orEmpty()
        if (head.isEmpty()) return false
        if (!head.startsWith("ref:")) return head.length >= 7 && head.all { it.isLetterOrDigit() }
        val ref = head.removePrefix("ref:").trim()
        if (ref.isEmpty()) return false
        if (File(gitDir, ref).isFile) return true
        val packed = File(gitDir, "packed-refs").takeIf { it.isFile }?.readText().orEmpty()
        return packed.lineSequence().any { it.isNotBlank() && !it.startsWith("#") && it.trimEnd().endsWith(" $ref") }
    }

    /** `.git` as a directory, following the one-line `gitdir:` pointer file a linked worktree uses. */
    private fun gitDir(dir: File): File? {
        val dot = File(dir, ".git")
        if (dot.isDirectory) return dot
        if (!dot.isFile) return null
        val target = dot.readText().trim().removePrefix("gitdir:").trim()
        if (target.isEmpty()) return null
        val abs = File(target)
        return if (abs.isAbsolute && abs.isDirectory) abs else File(dir, target).takeIf { it.isDirectory }
    }

    /** At least one entry that is not `.git` — a checkout that actually has files in it. */
    fun hasWorktree(dir: File): Boolean = dir.listFiles()?.any { it.name != ".git" } == true

    /**
     * 0 = not a clone · 1 = a clone · 2 = a clone whose HEAD resolves · 3 = [COMPLETE] (a clone,
     * a resolvable HEAD and a populated worktree). Only a [COMPLETE] survivor may cause the other
     * copy to be deleted, which is what makes "never delete an only copy" structural.
     */
    fun completeness(dir: File): Int = when {
        !isClone(dir) -> 0
        !headResolves(dir) -> 1
        !hasWorktree(dir) -> 2
        else -> COMPLETE
    }

    /** The complete copy: a clone AND a resolvable HEAD AND a populated worktree. */
    fun isComplete(dir: File): Boolean = completeness(dir) == COMPLETE

    /**
     * Settle `<[root]>/<name>` against `<[root]>/<[gitSubdir]>/<name>` for every name in
     * [repoNames], and return one [Move] per name where something at the root was considered —
     * the ones left alone included, so the caller logs why. The git folder is never a stray.
     */
    fun migrate(root: File, gitSubdir: String, repoNames: Set<String>): List<Move> {
        val gitRoot = File(root, gitSubdir)
        val strays = root.listFiles()?.filter { it.isDirectory && it.name != gitSubdir && it.name in repoNames }
            ?: return emptyList()
        return strays.mapNotNull { stray -> settle(stray, File(gitRoot, stray.name)) }
    }

    /** One name's decision. null ⇒ there was nothing at the root worth reporting. */
    private fun settle(stray: File, dest: File): Move? {
        val strayScore = completeness(stray)
        val destScore = completeness(dest)
        // Not a clone at all: a folder of the user's that happens to share a declared name. Never
        // moved and NEVER deleted — the store is plain shared storage and that folder is not ours.
        if (strayScore == 0) {
            if (!dest.exists()) return null
            return Move(stray.name, stray, dest, moved = false, decision = "left: the root copy is not a clone and git/${stray.name} exists")
        }
        if (!dest.exists()) {
            val ok = relocate(stray, dest)
            return Move(
                stray.name, stray, dest, moved = ok, removed = ok,
                decision = if (ok) "migrated: root clone (score $strayScore) → git/, nothing was there" else "FAILED: the root clone could not be relocated into git/",
            )
        }
        if (strayScore > destScore) {
            // The root copy is the better one. Park the husk, move, VERIFY, only then drop the husk.
            val parked = File(dest.parentFile, dest.name + PARKED_SUFFIX)
            if (parked.exists()) parked.deleteRecursively()
            if (!dest.renameTo(parked)) {
                return Move(stray.name, stray, dest, moved = false, decision = "left: could not park the incomplete git/ copy (score $destScore) to make room for the better root copy (score $strayScore)")
            }
            val ok = relocate(stray, dest)
            if (!ok || completeness(dest) < strayScore) {
                // Put the husk back rather than leave the destination worse than it was.
                dest.deleteRecursively()
                parked.renameTo(dest)
                return Move(stray.name, stray, dest, moved = false, decision = "left: the move of the better root copy did not verify — the git/ copy was restored")
            }
            parked.deleteRecursively()
            return Move(stray.name, stray, dest, moved = true, removed = true, decision = "replaced: the root copy (score $strayScore) is more complete than git/ (score $destScore) — the husk was removed only after the move verified")
        }
        if (destScore == COMPLETE) {
            // The git/ copy is PROVEN complete, so the root copy is genuinely redundant. This is the
            // only path that deletes a clone outright, and by construction it is never an only copy.
            val removed = stray.deleteRecursively()
            return Move(
                stray.name, stray, dest, moved = false, removed = removed,
                decision = if (removed) "removed: git/ is complete — the redundant root copy (score $strayScore) was deleted" else "left: git/ is complete but the redundant root copy could not be deleted",
            )
        }
        return Move(stray.name, stray, dest, moved = false, decision = "left: NEITHER copy is complete (root $strayScore, git/ $destScore) — nothing is deleted until one of them is")
    }

    /** rename, else copy-then-delete (a rename across shared-storage volumes fails). */
    private fun relocate(from: File, to: File): Boolean {
        to.parentFile?.mkdirs()
        if (from.renameTo(to)) return true
        val copied = runCatching { from.copyRecursively(to, overwrite = false) }.getOrDefault(false)
        return copied && from.deleteRecursively()
    }

    /** The score of a copy that has everything: a clone, a resolvable HEAD and a populated worktree. */
    const val COMPLETE = 3
    private const val PARKED_SUFFIX = ".incomplete"
}
