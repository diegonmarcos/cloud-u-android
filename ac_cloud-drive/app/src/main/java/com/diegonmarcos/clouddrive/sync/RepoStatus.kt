package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.clouddrive.ui.StatusLight
import com.diegonmarcos.cloudlib.gitsync.GitCommitInfo

/**
 * The repository row's status, as pure rules (JVM-tested): what the leading light means, why it can be "?", the
 * STATUS block at the top of the open repository panel, a probe error as a human sentence, and the unpushed list.
 *
 * THE "?" ROOT CAUSE this replaces: the light was UNKNOWN ("?") for a repository that is not cloned (most listed
 * repositories), for one whose status is not read yet, for one whose probe FAILED (the reason was dropped, nothing
 * on the row said so) and, worst, for a cloned repository whose status read fine but which the scheduler had
 * never synced (`lastSyncEpochSeconds == 0`). The light now follows the real status; every remaining "?" has a
 * named kind, a legend entry and a tooltip.
 */
object RepoStatus {
    enum class Kind { NOT_CLONED, READING, MISSING, ERROR, CONFLICT, DIRTY, BEHIND, AHEAD, CLEAN }

    /** The kind of one row: [cloned] says whether a clone is registered, [g] is the status reader's last answer for it. */
    fun kindOf(cloned: Boolean, g: GitSyncCoordinator.Glance?): Kind = when {
        !cloned -> Kind.NOT_CLONED
        g == null || !g.read -> Kind.READING
        g.gone -> Kind.MISSING
        g.error != null -> Kind.ERROR
        g.conflicts > 0 -> Kind.CONFLICT
        g.changed > 0 -> Kind.DIRTY
        g.behind > 0 -> Kind.BEHIND
        g.ahead > 0 -> Kind.AHEAD
        else -> Kind.CLEAN
    }

    /** The light: a read status is green when nothing local is at risk, hollow when something needs the owner; "?" only when nothing can be said. */
    fun light(kind: Kind): StatusLight.State = when (kind) {
        Kind.CLEAN, Kind.AHEAD, Kind.BEHIND -> StatusLight.State.ON
        Kind.DIRTY, Kind.CONFLICT, Kind.ERROR -> StatusLight.State.OFF
        Kind.MISSING -> StatusLight.State.UNVERIFIABLE
        Kind.NOT_CLONED, Kind.READING -> StatusLight.State.UNKNOWN
    }

    /** Why a status probe failed, in classes a sentence can be chosen for. */
    enum class ErrorKind { TIMEOUT, NOT_A_REPOSITORY, PERMISSION, AUTH, ENGINE, OTHER }

    fun errorKind(reason: String?): ErrorKind {
        val r = reason.orEmpty().lowercase()
        return when {
            r.startsWith("timeout") -> ErrorKind.TIMEOUT
            "not a git" in r || "repository not found" in r || "no such repository" in r || "invalid git" in r -> ErrorKind.NOT_A_REPOSITORY
            "permission" in r || "eacces" in r || "read-only" in r -> ErrorKind.PERMISSION
            "401" in r || "403" in r || "not authorized" in r || "auth" in r -> ErrorKind.AUTH
            "no coordinator" in r || "engine" in r || "connect" in r || "refused" in r || "binder" in r || "service" in r -> ErrorKind.ENGINE
            else -> ErrorKind.OTHER
        }
    }

    /** The STATUS block's facts for one repository. Counts are null when they cannot be known (not cloned, no upstream, not read). */
    data class Block(
        val kind: Kind,
        val cloned: Boolean,
        val branch: String?,
        val upstream: String?,
        val modified: Int?,
        val untracked: Int?,
        val conflicts: Int?,
        val ahead: Int?,
        val behind: Int?,
        /** Commits not pushed: the ahead count; null while there is no upstream to compare with. */
        val unpushed: Int?,
        /** When the remote was last fetched (epoch ms); null = never / not known. */
        val lastFetchMs: Long?,
        val error: ErrorKind?,
        val errorDetail: String?,
    ) {
        val dirty: Int? get() = if (modified == null || untracked == null) null else modified + untracked + (conflicts ?: 0)
    }

    fun block(cloned: Boolean, g: GitSyncCoordinator.Glance?): Block {
        val kind = kindOf(cloned, g)
        val read = cloned && g != null && g.read && !g.gone && g.error == null
        return Block(
            kind = kind, cloned = cloned,
            branch = if (read) g!!.branch else null,
            upstream = if (read) g!!.upstream else null,
            modified = if (read) (g!!.changed - g.untracked).coerceAtLeast(0) else null,
            untracked = if (read) g!!.untracked else null,
            conflicts = if (read) g!!.conflicts else null,
            ahead = if (read && g!!.upstream != null) g.ahead else null,
            behind = if (read && g!!.upstream != null) g.behind else null,
            unpushed = if (read && g!!.upstream != null) g.ahead else null,
            lastFetchMs = if (read && g!!.fetchedAtMs > 0) g.fetchedAtMs else null,
            error = if (kind == Kind.ERROR) errorKind(g?.error) else null,
            errorDetail = if (kind == Kind.ERROR) g?.error else null,
        )
    }

    /** #1238 what the status window's last section offers: Clone for a repository with no clone, a progress word while it clones, nothing for a clone that exists. */
    enum class CloneAction { CLONE, CLONING, NONE }

    fun cloneAction(cloned: Boolean, cloning: Boolean): CloneAction = when {
        cloned -> CloneAction.NONE
        cloning -> CloneAction.CLONING
        else -> CloneAction.CLONE
    }

    /** The commits a push would send: the newest [ahead] entries of the branch's log (the log is newest first). */
    fun unpushed(log: List<GitCommitInfo>, ahead: Int?): List<GitCommitInfo> = if (ahead == null || ahead <= 0) emptyList() else log.take(ahead)
}
