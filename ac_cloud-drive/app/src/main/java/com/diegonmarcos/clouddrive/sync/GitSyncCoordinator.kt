package com.diegonmarcos.clouddrive.sync

import android.content.Context
import com.diegonmarcos.clouddrive.DriveDebugLog
import com.diegonmarcos.clouddrive.GitSyncWorker
import com.diegonmarcos.clouddrive.SharedStore
import com.diegonmarcos.cloudlib.gitsync.GitBranchInfo
import com.diegonmarcos.cloudlib.gitsync.GitCommitInfo
import com.diegonmarcos.cloudlib.gitsync.GitCredentialStore
import com.diegonmarcos.cloudlib.gitsync.GitEngine
import com.diegonmarcos.cloudlib.gitsync.GitOpResult
import com.diegonmarcos.cloudlib.gitsync.GitRemoteInfo
import com.diegonmarcos.cloudlib.gitsync.ManagedRepo
import com.diegonmarcos.cloudlib.gitsync.RepoRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #579 the chrome's reading of the git engine for the Sync ▸ Git cards: the managed
 * repositories (the SAME registry file the engine screen and the worker write), a live
 * glance per repository (branch, upstream, ahead/behind, dirty, conflicts, state), the
 * stepped one-tap sync, and the history. App → lib only: it calls GitEngine and never
 * re-implements a verb.
 */
class GitSyncCoordinator(private val ctx: Context, private val scope: CoroutineScope) {

    private val registry = RepoRegistry(File(ctx.filesDir, GitSyncWorker.REGISTRY_FILE))
    private val credentials = GitCredentialStore(ctx)
    val history = SyncHistory(File(ctx.filesDir, SyncHistory.FILE))

    data class Glance(
        val branch: String? = null,
        val upstream: String? = null,
        val ahead: Int = 0,
        val behind: Int = 0,
        val changed: Int = 0,
        val conflicts: Int = 0,
        val repositoryState: String = "SAFE",
        val error: String? = null,
        val gone: Boolean = false,
        val read: Boolean = false,
    )

    enum class Step { STAGING, COMMITTING, PULLING, PUSHING }

    data class Running(val repoId: String, val step: Step)

    val repos = MutableStateFlow<List<ManagedRepo>>(emptyList())
    val glances = MutableStateFlow<Map<String, Glance>>(emptyMap())
    val running = MutableStateFlow<Map<String, Running>>(emptyMap())
    val events = MutableStateFlow<List<SyncEvent>>(emptyList())

    fun refresh() {
        scope.launch {
            val list = withContext(Dispatchers.IO) { registry.load() }
            repos.value = list
            events.value = withContext(Dispatchers.IO) { history.load() }
            val read = withContext(Dispatchers.IO) {
                list.associate { r ->
                    val dir = File(r.path)
                    r.id to when {
                        !dir.isDirectory -> Glance(gone = true, read = true)
                        else -> runCatching {
                            GitEngine(dir).use { e ->
                                val s = e.status()
                                Glance(s.branch, s.upstream, s.ahead, s.behind, s.files.size - s.conflicts.size, s.conflicts.size, s.repositoryState, read = true)
                            }
                        }.getOrElse { Glance(error = it.message ?: it.toString(), read = true) }
                    }
                }
            }
            glances.value = read
        }
    }

    fun save(repo: ManagedRepo) { scope.launch { withContext(Dispatchers.IO) { registry.upsert(repo) }; refresh() } }

    fun remove(repo: ManagedRepo) { scope.launch { withContext(Dispatchers.IO) { registry.remove(repo.id); credentials.clear(repo.id) }; refresh() } }

    fun setSecret(repo: ManagedRepo, secret: String) { credentials.setSecret(repo.id, secret) }
    fun hasSecret(repo: ManagedRepo): Boolean = credentials.hasSecret(repo.id)

    /**
     * GitSync's one-tap sync with the step the card shows: stage → commit → pull → push.
     * The engine's own sync() is one call; the steps are re-run here through the same
     * verbs so the card can name the step in flight. The outcome lands in the history
     * and the registry, and the card re-reads its glance.
     */
    fun syncNow(repo: ManagedRepo) {
        if (running.value.containsKey(repo.id)) return
        running.update { it + (repo.id to Running(repo.id, Step.STAGING)) }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    GitEngine(File(repo.path)).use { e ->
                        val steps = mutableListOf<String>()
                        e.stageAll()
                        running.update { it + (repo.id to Running(repo.id, Step.COMMITTING)) }
                        if (e.status().staged.isNotEmpty()) {
                            val c = e.commit(repo.syncMessage, repo.authorName.ifBlank { DEFAULT_AUTHOR }, repo.authorEmail.ifBlank { DEFAULT_EMAIL })
                            steps += "committed ${c.shortSha}"
                        } else steps += "nothing to commit"
                        if (e.remotes().none { it.name == "origin" }) return@use GitOpResult(true, steps.joinToString(", ") + ", no remote 'origin'")
                        running.update { it + (repo.id to Running(repo.id, Step.PULLING)) }
                        val pulled = e.pull(rebase = repo.pullRebase, auth = credentials.authFor(repo))
                        steps += pulled.summary
                        if (!pulled.ok) return@use GitOpResult(false, steps.joinToString(", "), pulled.details)
                        running.update { it + (repo.id to Running(repo.id, Step.PUSHING)) }
                        val pushed = e.push(auth = credentials.authFor(repo))
                        steps += pushed.summary
                        GitOpResult(pushed.ok, steps.joinToString(", "), pushed.details)
                    }
                }.getOrElse { GitOpResult(false, it.message ?: it.toString()) }
            }
            val now = System.currentTimeMillis() / 1000
            withContext(Dispatchers.IO) {
                registry.upsert(repo.copy(lastSyncEpochSeconds = now, lastSyncSummary = result.summary))
                history.append(SyncEvent(now, repo.id, repo.name, SyncHistory.TRIGGER_MANUAL, result.ok, result.summary, result.details))
                // On-device debug log: the SAME sentence the history holds — the engine's own
                // summary, never a credential (the token lives in GitCredentialStore alone).
                if (result.ok) DriveDebugLog.i(ctx, TAG, "sync ${repo.name}: ${result.summary}")
                else DriveDebugLog.e(ctx, TAG, "sync ${repo.name} FAILED: ${result.summary} ${result.details}".trim())
            }
            running.update { it - repo.id }
            refresh()
        }
    }

    // ── #608 the Sync ▸ Git page's per-repository operations ────────────────

    /**
     * Everything the row's disclosure shows about ONE clone, read in one pass when it is
     * expanded: the remotes and branches and commits from [GitEngine], the hooks, the
     * workflows and the size from [GitRepoScan]. Loaded lazily on purpose — walking every
     * clone of a long list on every recomposition is how a dense page becomes unusable.
     */
    data class Details(
        val loading: Boolean = true,
        val remotes: List<GitRemoteInfo> = emptyList(),
        val branches: List<GitBranchInfo> = emptyList(),
        val commits: List<GitCommitInfo> = emptyList(),
        val hooks: List<String> = emptyList(),
        val workflows: List<String> = emptyList(),
        val size: GitRepoScan.Size = GitRepoScan.Size(0, 0, 0, 0),
        val error: String? = null,
    )

    val details = MutableStateFlow<Map<String, Details>>(emptyMap())

    /** The last outcome of an operation, per repository, in the engine's own words. */
    val opResults = MutableStateFlow<Map<String, GitOpResult>>(emptyMap())

    /** A clone in flight, keyed by the repository name the store will hold it under. */
    val cloning = MutableStateFlow<Set<String>>(emptySet())

    fun loadDetails(repo: ManagedRepo, historyMax: Int) {
        details.update { it + (repo.id to Details(loading = true)) }
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                val dir = File(repo.path)
                if (!dir.isDirectory) return@withContext Details(loading = false, error = "gone")
                runCatching {
                    GitEngine(dir).use { e ->
                        Details(
                            loading = false,
                            remotes = e.remotes(),
                            branches = e.branches(),
                            commits = e.log(max = historyMax),
                            hooks = GitRepoScan.hooks(dir),
                            workflows = GitRepoScan.workflows(dir),
                            size = GitRepoScan.size(dir),
                        )
                    }
                }.getOrElse { Details(loading = false, error = it.message ?: it.toString()) }
            }
            details.update { it + (repo.id to read) }
        }
    }

    /**
     * ONE declared operation on ONE repository, every verb the engine's own (#608 added
     * forcePush/forcePull to it for exactly this page). The outcome lands in [opResults]
     * and in the shared history — a force push that rewrote a remote is not something the
     * page should be able to forget — and the glance is re-read afterwards.
     *
     * The two destructive ids are NOT special-cased here: the row confirms before it calls.
     */
    fun runOp(repo: ManagedRepo, opId: String, message: String = "") {
        if (running.value.containsKey(repo.id)) return
        val step = when (opId) {
            OP_COMMIT -> Step.COMMITTING
            OP_PULL, OP_FORCE_PULL, OP_FETCH -> Step.PULLING
            else -> Step.PUSHING
        }
        running.update { it + (repo.id to Running(repo.id, step)) }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    GitEngine(File(repo.path)).use { e ->
                        val auth = credentials.authFor(repo)
                        when (opId) {
                            OP_FETCH -> e.fetch(auth = auth)
                            OP_PULL -> e.pull(rebase = repo.pullRebase, auth = auth)
                            OP_COMMIT -> {
                                e.stageAll()
                                if (e.status().staged.isEmpty()) GitOpResult(true, "nothing to commit")
                                else {
                                    val c = e.commit(
                                        message.ifBlank { repo.syncMessage },
                                        repo.authorName.ifBlank { DEFAULT_AUTHOR },
                                        repo.authorEmail.ifBlank { DEFAULT_EMAIL },
                                    )
                                    GitOpResult(true, "committed ${c.shortSha}")
                                }
                            }
                            OP_PUSH -> e.push(auth = auth)
                            OP_FORCE_PUSH -> e.forcePush(auth = auth)
                            OP_FORCE_PULL -> e.forcePull(auth = auth)
                            else -> GitOpResult(false, "no such operation: $opId")
                        }
                    }
                }.getOrElse { GitOpResult(false, it.message ?: it.toString()) }
            }
            val now = System.currentTimeMillis() / 1000
            withContext(Dispatchers.IO) {
                registry.upsert(repo.copy(lastSyncEpochSeconds = now, lastSyncSummary = "$opId: ${result.summary}"))
                history.append(SyncEvent(now, repo.id, repo.name, SyncHistory.TRIGGER_MANUAL, result.ok, "$opId: ${result.summary}", result.details))
                if (result.ok) DriveDebugLog.i(ctx, TAG, "$opId ${repo.name}: ${result.summary}")
                else DriveDebugLog.e(ctx, TAG, "$opId ${repo.name} FAILED: ${result.summary} ${result.details}".trim())
            }
            opResults.update { it + (repo.id to result) }
            running.update { it - repo.id }
            refresh()
        }
    }

    /**
     * Point `origin` at the URL a DECLARED remote mode composes, and record which shape
     * the repository now speaks so the credential store hands the engine the matching
     * [com.diegonmarcos.cloudlib.gitsync.GitAuth]. A read-only mode keeps the HTTPS URL
     * and drops the auth kind to none: the page then refuses push and force push on it
     * rather than offering a button that always fails.
     */
    fun setRemote(repo: ManagedRepo, mode: com.diegonmarcos.clouddrive.Declarations.GitRemoteModeDecl, owner: String, name: String) {
        val url = mode.urlFor(owner, name)
        if (url.isBlank()) return
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    GitEngine(File(repo.path)).use { e ->
                        if (e.remotes().none { r -> r.name == ORIGIN }) e.addRemote(ORIGIN, url)
                        else { e.setRemoteUrl(ORIGIN, url); e.setRemoteUrl(ORIGIN, url, push = true) }
                    }
                    GitOpResult(true, "origin → $url")
                }.getOrElse { GitOpResult(false, it.message ?: it.toString()) }
            }
            if (result.ok) {
                val kind = when {
                    mode.readOnly -> AUTH_NONE
                    mode.id == com.diegonmarcos.clouddrive.Declarations.REMOTE_SSH -> AUTH_SSH
                    else -> AUTH_HTTPS
                }
                withContext(Dispatchers.IO) { registry.upsert(repo.copy(remoteUrl = url, authKind = kind)) }
            }
            opResults.update { it + (repo.id to result) }
            refresh()
        }
    }

    /**
     * Clone [url] into the store's git folder as [name] and register it — the SAME engine,
     * the SAME registry and the SAME folder the first-run seed uses, so a repository cloned
     * from the page and one seeded on first run are indistinguishable afterwards.
     *
     * [token] is the sign-in's access token for an https clone (blank for a public one) and
     * is handed to the engine and to the credential store, never written here.
     */
    fun cloneInto(name: String, url: String, authKind: String, username: String, token: String, sshKeyPath: String = "") {
        if (name.isBlank() || url.isBlank() || cloning.value.contains(name)) return
        cloning.update { it + name }
        scope.launch {
            val dir = SharedStore.repoDir(name)
            // The clone's decision point, on the device: the URL's HOST and the destination —
            // never the full URL (a listing URL could carry a credential in its userinfo) and
            // never the token, which travels only through GitCredentialStore.
            DriveDebugLog.i(ctx, TAG, "clone start: host=${hostOf(url)} dest=${dir.name} auth=$authKind")
            val result = withContext(Dispatchers.IO) {
                val id = RepoRegistry.idFor(dir.absolutePath)
                val managed = ManagedRepo(id = id, name = name, path = dir.absolutePath, remoteUrl = url, authKind = authKind, authUsername = username, sshKeyPath = sshKeyPath)
                if (token.isNotBlank()) credentials.setSecret(id, token)
                runCatching {
                    if (GitEngine.isRepository(dir)) GitOpResult(true, "already cloned")
                    else { GitEngine.clone(url, dir, auth = credentials.authFor(managed)).close(); GitOpResult(true, "cloned into ${dir.name}") }
                }.getOrElse { GitOpResult(false, it.message ?: it.toString()) }
                    .also { if (it.ok) registry.upsert(managed) }
            }
            val now = System.currentTimeMillis() / 1000
            withContext(Dispatchers.IO) {
                history.append(SyncEvent(now, RepoRegistry.idFor(dir.absolutePath), name, SyncHistory.TRIGGER_MANUAL, result.ok, "clone: ${result.summary}", result.details))
                if (result.ok) DriveDebugLog.i(ctx, TAG, "clone $name: ${result.summary}")
                else DriveDebugLog.e(ctx, TAG, "clone $name FAILED: host=${hostOf(url)} dest=${dir.name} ${result.summary} ${result.details}".trim())
            }
            opResults.update { it + (RepoRegistry.idFor(dir.absolutePath) to result) }
            cloning.update { it - name }
            refresh()
        }
    }

    companion object {
        private const val TAG = "GitSync"

        /**
         * The URL's HOST alone, for the on-device debug log: a host names which leg a clone
         * rode (fleet gitea vs github) and can never carry a credential, while a full URL's
         * userinfo could. Covers both https URLs and the scp-like ssh form (git@host:o/r).
         */
        fun hostOf(url: String): String =
            runCatching { java.net.URI(url).host }.getOrNull()
                ?: url.substringAfter('@', url).substringBefore(':').substringBefore('/').ifBlank { "?" }

        const val DEFAULT_AUTHOR = "cloud-drive"
        const val DEFAULT_EMAIL = "cloud-drive@localhost"
        const val ORIGIN = "origin"
        const val AUTH_NONE = "none"
        const val AUTH_HTTPS = "https"
        const val AUTH_SSH = "ssh"
        /** The declared op ids this coordinator performs; the page's own dispatch covers the read-only rest. */
        const val OP_FETCH = "fetch"
        const val OP_PULL = "pull"
        const val OP_COMMIT = "commit"
        const val OP_PUSH = "push"
        const val OP_FORCE_PUSH = "force_push"
        const val OP_FORCE_PULL = "force_pull"
    }
}
