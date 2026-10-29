package com.diegonmarcos.clouddrive.sync

import java.io.File

/**
 * #1256 the Last commit column's pure rules, kept out of the coordinator so the JVM tests pin them:
 * one map key for a clone (canonical path, so a store symlink and its target meet), the engine's
 * seconds to the table's milliseconds, and when the table computes the column without an owner action.
 */
object LastCommits {
    /** THE key of a clone in the last-commit map: the canonical path (symlinks resolved), else the absolute one. */
    fun keyOf(path: String): String = File(path).let { f -> runCatching { f.canonicalPath }.getOrElse { f.absolutePath } }

    /** GitCommitInfo.time is epoch SECONDS (JGit epochSecond, git %at); null for an unknown or nonsensical value. */
    fun msOf(epochSeconds: Long?): Long? = epochSeconds?.takeIf { it > 0 }?.let { it * 1000 }

    /** First open with nothing cached: compute once (a local HEAD read). Otherwise only pull-to-refresh / check-all refresh it. */
    fun needsFirstFill(cachedLocal: Map<String, Long>, registered: Int): Boolean = registered > 0 && cachedLocal.isEmpty()

    /** First open with no forge pushed_at known at all (cache from before #1256, or never fetched): fetch the remote facts once. */
    fun needsRemoteFill(remote: Collection<RepoRemoteStats.Stats>): Boolean = remote.none { it.pushedAtMs > 0 }
}
