package com.diegonmarcos.cloudlib.gitsync

import kotlinx.serialization.Serializable

/** How a path differs, on one side of the index (staged) or the other (worktree). */
enum class GitChange { ADDED, MODIFIED, DELETED, UNTRACKED }

/**
 * One path in `git status`. Both sides can be set at once (a file edited after
 * it was staged), which is exactly the state a stage/unstage UI has to show.
 */
data class GitFileStatus(
    val path: String,
    val staged: GitChange? = null,
    val unstaged: GitChange? = null,
    /** In a merge/rebase with unresolved markers; `stageState` is JGit's name for the shape. */
    val conflicting: Boolean = false,
    val stageState: String? = null,
) {
    val hasStaged: Boolean get() = staged != null
    val hasUnstaged: Boolean get() = unstaged != null
}

data class GitStatusSnapshot(
    val branch: String?,
    val upstream: String?,
    val ahead: Int,
    val behind: Int,
    val files: List<GitFileStatus>,
    /** Repository state as JGit names it: SAFE, MERGING, REBASING, … */
    val repositoryState: String,
) {
    val staged: List<GitFileStatus> get() = files.filter { it.hasStaged && !it.conflicting }
    val unstaged: List<GitFileStatus> get() = files.filter { it.hasUnstaged && !it.conflicting }
    val conflicts: List<GitFileStatus> get() = files.filter { it.conflicting }
    val isClean: Boolean get() = files.isEmpty()
}

data class GitCommitInfo(
    val sha: String,
    val summary: String,
    val message: String,
    val author: String,
    val email: String,
    /** Seconds since the epoch, the author time. */
    val time: Long,
    val parents: List<String>,
) {
    val shortSha: String get() = sha.take(8)
}

data class GitBranchInfo(
    /** Short name: `main`, or `origin/main` for a remote-tracking branch. */
    val name: String,
    val fullName: String,
    val isRemote: Boolean,
    val isCurrent: Boolean,
    val sha: String,
)

data class GitRemoteInfo(
    val name: String,
    val fetchUrl: String,
    val pushUrl: String,
)

/** Which side of a conflict wins when the user resolves a path wholesale. */
enum class ConflictSide { OURS, THEIRS }

/**
 * Transport credentials. HTTPS carries a username plus a token/password (what
 * GitHub, Gitea and GitLab all take). SSH carries a private key file; the
 * known-hosts file lives in the host app's private storage so a first contact
 * is recorded there and a changed key is refused afterwards (accept-new).
 */
sealed class GitAuth {
    object None : GitAuth()
    data class Https(val username: String, val secret: String) : GitAuth()
    data class Ssh(val privateKeyPath: String, val passphrase: String?, val knownHostsPath: String) : GitAuth()
}

/** The outcome of a transport operation, in words the UI can show as-is. */
data class GitOpResult(val ok: Boolean, val summary: String, val details: String = "")

/**
 * One repository the manager knows about. Persisted by [RepoRegistry]; the
 * secret half of its auth lives in [GitCredentialStore], keyed by [id], and is
 * never serialised here.
 */
@Serializable
data class ManagedRepo(
    val id: String,
    val name: String,
    val path: String,
    val remoteUrl: String = "",
    /** "none" | "https" | "ssh" — which [GitAuth] shape the credential store holds for [id]. */
    val authKind: String = "none",
    val authUsername: String = "",
    val sshKeyPath: String = "",
    val authorName: String = "",
    val authorEmail: String = "",
    /** Pull with rebase instead of merge, GitSync's default. */
    val pullRebase: Boolean = true,
    /** Message used when Sync commits everything in one go. */
    val syncMessage: String = "sync from cloud-drive",
    val lastSyncEpochSeconds: Long = 0,
    val lastSyncSummary: String = "",
)
