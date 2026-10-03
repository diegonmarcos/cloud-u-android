package com.diegonmarcos.clouddrive.sync

import android.content.Context
import com.diegonmarcos.clouddrive.Declarations
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
import kotlinx.coroutines.joinAll
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

    /**
     * #669 THE ONE PLACE A REPOSITORY'S AUTH IS ASSEMBLED for this coordinator's
     * engine calls. An [AUTH_SESSION] repository was listed AND cloned on the fleet
     * session, which lives in PROCESS MEMORY ONLY ([FleetSession]) — the credential
     * store cannot hold it (test-drive-configs-sign-in: the drive host stores no
     * bearer), so it is attached here, on the DECLARED header, every time the
     * engine touches that repository's transport. Everything else is the store's:
     * one store, one id, unchanged.
     */
    private fun authFor(repo: ManagedRepo, token: () -> String = { declaredToken() }): com.diegonmarcos.cloudlib.gitsync.GitAuth =
        pickAuth(
            kind = repo.authKind,
            stored = { credentials.authFor(repo) },
            session = { com.diegonmarcos.cloudlib.gitsync.GitAuth.Session(FleetGit.sessionHeader(), FleetSession.cookie) },
            declaredToken = token,
            owner = Declarations.sync.git.owner,
            declaredHost = hostOf(repo.remoteUrl) == hostOf(Declarations.sync.git.cloneUrl(repo.name)),
        )

    /**
     * #850/#818 THE ONE DECLARED GIT CREDENTIAL, the same path StoreSeed clones with: the
     * vault-delivered token under the one declared id, then the declared chain. A seeded
     * repository is registered with no credential of its own, so without this a pull of a
     * private seeded clone would ride no auth at all. Blocking — call off the main thread.
     */
    private fun declaredToken(): String =
        com.diegonmarcos.clouddrive.configs.DriveAuthApply.vaultGitToken(ctx)
            .ifBlank { runCatching { com.diegonmarcos.clouddrive.configs.DriveGitChain.resolve(ctx).token.orEmpty() }.getOrDefault("") }
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
        /** #850 the reader's terminal word: clean / ahead / behind / dirty / not_cloned / error. */
        val state: String = "",
        /** #850 when this glance was read (epoch ms) — the cache's timestamp. */
        val readAtMs: Long = 0,
    )

    enum class Step { STAGING, COMMITTING, PULLING, PUSHING }

    data class Running(val repoId: String, val step: Step)

    val repos = MutableStateFlow<List<ManagedRepo>>(emptyList())
    val glances = MutableStateFlow<Map<String, Glance>>(emptyMap())
    val running = MutableStateFlow<Map<String, Running>>(emptyMap())
    val events = MutableStateFlow<List<SyncEvent>>(emptyList())

    init {
        // #850 the glances ARE the one reader's statuses, entry by entry as each ends — a
        // slow repository no longer holds every other row on "reading…".
        scope.launch { statusReader.statuses.collect { m -> glances.value = m.mapValues { (_, s) -> glanceOf(s) } } }
    }

    /**
     * Re-load the registry and ASK the one status reader for every repository. Cheap and
     * idempotent: a repository already being read, or read less than the declared ttl ago,
     * starts nothing ([force] re-reads the fresh ones too). Returns once the registry is
     * loaded and the reads are requested, with the job that ends when they have all ended.
     */
    fun refresh(force: Boolean = false): kotlinx.coroutines.Job = scope.launch {
        val list = withContext(Dispatchers.IO) { registry.load() }
        repos.value = list
        events.value = withContext(Dispatchers.IO) { history.load() }
        statusReader.request(list.map { GitStatusReader.Target(it.id, File(it.path)) }, force).join()
    }

    /** After an operation changed [repo]'s tree: its cached status is stale, re-read it. */
    private fun reread(repo: ManagedRepo) { statusReader.invalidate(repo.id); refresh() }

    // ── #850 Force pull (all / one) and Auto pull on open ─────────────────────

    /** The bulk pull in flight or last finished: progress and each repository's outcome. */
    data class BulkPull(
        val force: Boolean,
        val trigger: String,
        val total: Int,
        val done: Int = 0,
        val results: Map<String, GitOpResult> = emptyMap(),
    ) {
        val finished: Boolean get() = done >= total
        val failed: Int get() = results.count { !it.value.ok }
    }

    val bulk = MutableStateFlow<BulkPull?>(null)

    /**
     * Pull every registered clone — [force] = the engine's forcePull (fetch + reset to
     * upstream, the declared destructive op), otherwise a plain pull. Per repository, at
     * most the declared status concurrency at once, each on the ONE credential path; the
     * declared token is resolved ONCE for the whole pass. Each outcome lands in [opResults],
     * the history and the debug log exactly as a single runOp's does.
     */
    fun pullAll(force: Boolean, trigger: String = SyncHistory.TRIGGER_MANUAL, only: Set<String>? = null): kotlinx.coroutines.Job? {
        if (!bulkRunning.compareAndSet(false, true)) return null
        val opId = if (force) OP_FORCE_PULL else OP_PULL
        bulk.value = BulkPull(force, trigger, total = Int.MAX_VALUE)
        return scope.launch {
          try {
            val list = withContext(Dispatchers.IO) {
                registry.load().filter { only == null || it.name in only || it.id in only }
                    .filter { GitEngine.isRepository(File(it.path)) }
            }
            bulk.value = BulkPull(force, trigger, list.size)
            val token = lazy { declaredToken() }
            val gate = kotlinx.coroutines.sync.Semaphore(Declarations.sync.git.status.concurrency)
            list.map { repo ->
                launch {
                    gate.acquire()
                    try {
                        if (running.value.containsKey(repo.id)) {
                            bulk.update { b -> b?.copy(done = b.done + 1, results = b.results + (repo.name to GitOpResult(false, "skipped: an operation is already running"))) }
                            return@launch
                        }
                        running.update { it + (repo.id to Running(repo.id, Step.PULLING)) }
                        val result = withContext(Dispatchers.IO) { perform(repo, opId, "", { token.value }, trigger) }
                        opResults.update { it + (repo.id to result) }
                        running.update { it - repo.id }
                        statusReader.invalidate(repo.id)
                        bulk.update { b -> b?.copy(done = b.done + 1, results = b.results + (repo.name to result)) }
                    } finally { gate.release() }
                }
            }.joinAll()
            DriveDebugLog.i(ctx, TAG, "pull-all ${opId} ($trigger): ${list.size} repos, ${bulk.value?.failed ?: 0} failed")
          } finally { bulkRunning.set(false) }
            refresh().join()
        }
    }

    private val bulkRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    private val pagePrefs by lazy { ctx.getSharedPreferences(PREFS_PAGE, Context.MODE_PRIVATE) }

    /** #850 the persisted "Auto pull on open" toggle; before the owner touches it, the declared default. */
    val autoPullOnOpen = MutableStateFlow(false).also { f ->
        scope.launch(Dispatchers.IO) { f.value = pagePrefs.getBoolean(KEY_AUTO_PULL, Declarations.sync.git.autoPull.defaultOn) }
    }

    fun setAutoPullOnOpen(on: Boolean) {
        autoPullOnOpen.value = on
        scope.launch(Dispatchers.IO) { pagePrefs.edit().putBoolean(KEY_AUTO_PULL, on).apply() }
    }

    /** What the last page-open did about auto pull, in words the page shows ("" = nothing to say). */
    val autoPullNote = MutableStateFlow("")

    /** Whether the active network is unmetered (Wi-Fi / ethernet) — the declared constraint. */
    private fun unmetered(): Boolean = runCatching {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching false
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)

    /**
     * #850 the page opened: read every status (cached, bounded) and, when the toggle is on
     * and the declared network constraint holds, pull every clone (plain pull). Called from
     * the page's LaunchedEffect(Unit) — once per entry, never per recomposition.
     */
    fun onPageOpened() {
        refresh()
        scope.launch {
            val on = withContext(Dispatchers.IO) { pagePrefs.getBoolean(KEY_AUTO_PULL, Declarations.sync.git.autoPull.defaultOn) }
            autoPullOnOpen.value = on
            autoPullNote.value = when (val d = autoPullDecision(on, Declarations.sync.git.autoPull.requireUnmetered, unmetered(), bulkRunning.get())) {
                AUTO_PULL_GO -> { pullAll(force = false, trigger = TRIGGER_OPEN); "" }
                else -> d
            }
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
                        val pulled = e.pull(rebase = repo.pullRebase, auth = authFor(repo))
                        steps += pulled.summary
                        if (!pulled.ok) return@use GitOpResult(false, steps.joinToString(", "), pulled.details)
                        running.update { it + (repo.id to Running(repo.id, Step.PUSHING)) }
                        val pushed = e.push(auth = authFor(repo))
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
            reread(repo)
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
            val result = withContext(Dispatchers.IO) { perform(repo, opId, message, { declaredToken() }, SyncHistory.TRIGGER_MANUAL) }
            opResults.update { it + (repo.id to result) }
            running.update { it - repo.id }
            reread(repo)
        }
    }

    /** ONE operation on ONE repository, recorded — the shared body of [runOp] and [pullAll]. Blocking. */
    private fun perform(repo: ManagedRepo, opId: String, message: String, token: () -> String, trigger: String): GitOpResult {
            val result = run {
                runCatching {
                    GitEngine(File(repo.path)).use { e ->
                        val auth = authFor(repo, token)
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
            runCatching {
                registry.upsert(repo.copy(lastSyncEpochSeconds = now, lastSyncSummary = "$opId: ${result.summary}"))
                history.append(SyncEvent(now, repo.id, repo.name, trigger, result.ok, "$opId: ${result.summary}", result.details))
            }
            if (result.ok) DriveDebugLog.i(ctx, TAG, "$opId ${repo.name}: ${result.summary}")
            else DriveDebugLog.e(ctx, TAG, "$opId ${repo.name} FAILED: ${result.summary} ${result.details}".trim())
            return result
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
                    else { GitEngine.clone(url, dir, auth = authFor(managed)).close(); GitOpResult(true, "cloned into ${dir.name}") }
                }.getOrElse {
                    val why = it.message ?: it.toString()
                    // #669 a fleet-session clone that fails must name its NEXT STEP —
                    // a redirect means the gate, not gitea, answered (sign in again);
                    // a 401/403 means the fleet itself refused. Never a silent
                    // fall-through to another host: this is the only URL attempted.
                    GitOpResult(false, if (authKind == AUTH_SESSION) FleetGit.explainCloneFailure(why) else why)
                }
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
         * #850 THE ONE STATUS READER of this process — shared by the page's coordinator and
         * the debug route's, so the cache, the in-flight claims and the bounds are one set.
         */
        val statusReader: GitStatusReader by lazy {
            val d = Declarations.sync.git.status
            GitStatusReader(
                probe = ::probeStatus,
                timeoutMs = d.timeoutSeconds * 1000L,
                concurrency = d.concurrency,
                ttlMs = d.ttlSeconds * 1000L,
            )
        }

        /** The real probe: libs:git-sync's own status, never a second JGit call here. */
        fun probeStatus(dir: File): GitStatusReader.Probe = GitEngine(dir).use { e ->
            val s = e.status()
            GitStatusReader.Probe(s.branch, s.upstream, s.ahead, s.behind, s.files.size - s.conflicts.size, s.conflicts.size, s.repositoryState)
        }

        const val PREFS_PAGE = "git-page"
        const val KEY_AUTO_PULL = "auto_pull_on_open"
        const val TRIGGER_OPEN = "open"
        const val AUTO_PULL_GO = "go"
        const val AUTO_PULL_OFF = ""
        const val AUTO_PULL_METERED = "metered"
        const val AUTO_PULL_BUSY = "busy"

        /** #850 the pure auto-pull decision: [AUTO_PULL_GO], or why not. */
        fun autoPullDecision(enabled: Boolean, requireUnmetered: Boolean, unmetered: Boolean, busy: Boolean): String = when {
            !enabled -> AUTO_PULL_OFF
            requireUnmetered && !unmetered -> AUTO_PULL_METERED
            busy -> AUTO_PULL_BUSY
            else -> AUTO_PULL_GO
        }

        /** The row's [Glance] for one reader status — READING is the only non-read state. */
        fun glanceOf(s: GitStatusReader.Status): Glance = when (s.state) {
            GitStatusReader.State.READING -> Glance()
            GitStatusReader.State.NOT_CLONED -> Glance(gone = true, read = true, state = "not_cloned", readAtMs = s.readAtMs)
            GitStatusReader.State.ERROR -> Glance(error = s.reason ?: "unknown", read = true, state = "error", readAtMs = s.readAtMs)
            else -> s.probe!!.let { p ->
                Glance(p.branch, p.upstream, p.ahead, p.behind, p.changed, p.conflicts, p.repositoryState, read = true, state = s.state.name.lowercase(), readAtMs = s.readAtMs)
            }
        }

        /**
         * #850/#818 which credential a transport verb rides: the fleet session for a
         * session repository, the repository's own stored secret when it has one, else the
         * ONE declared credential (vault token, then the chain) — never a second mechanism,
         * and only to the declared host ([declaredHost]) the credential belongs to.
         */
        fun pickAuth(
            kind: String,
            stored: () -> com.diegonmarcos.cloudlib.gitsync.GitAuth,
            session: () -> com.diegonmarcos.cloudlib.gitsync.GitAuth,
            declaredToken: () -> String,
            owner: String,
            declaredHost: Boolean,
        ): com.diegonmarcos.cloudlib.gitsync.GitAuth {
            if (kind == AUTH_SESSION) return session()
            val own = stored()
            if (own is com.diegonmarcos.cloudlib.gitsync.GitAuth.Ssh) return own
            if (own is com.diegonmarcos.cloudlib.gitsync.GitAuth.Https && own.secret.isNotBlank()) return own
            // The declared token is the DECLARED host's: never presented to another host.
            if (!declaredHost) return own
            val token = declaredToken()
            return if (token.isNotBlank()) com.diegonmarcos.cloudlib.gitsync.GitAuth.Https(owner, token) else own
        }

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
        /**
         * #669 the repository rides the FLEET SESSION (Authelia cookie, process
         * memory only) on the declared header — never a stored secret, never a
         * bearer. Set by the fleet-listed clone path; [authFor] resolves it.
         */
        const val AUTH_SESSION = "session"
        /** The declared op ids this coordinator performs; the page's own dispatch covers the read-only rest. */
        const val OP_FETCH = "fetch"
        const val OP_PULL = "pull"
        const val OP_COMMIT = "commit"
        const val OP_PUSH = "push"
        const val OP_FORCE_PUSH = "force_push"
        const val OP_FORCE_PULL = "force_pull"
    }
}
