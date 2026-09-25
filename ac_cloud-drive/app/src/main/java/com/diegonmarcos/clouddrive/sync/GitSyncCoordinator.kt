package com.diegonmarcos.clouddrive.sync

import android.content.Context
import com.diegonmarcos.clouddrive.GitSyncWorker
import com.diegonmarcos.cloudlib.gitsync.GitCredentialStore
import com.diegonmarcos.cloudlib.gitsync.GitEngine
import com.diegonmarcos.cloudlib.gitsync.GitOpResult
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
            }
            running.update { it - repo.id }
            refresh()
        }
    }

    companion object {
        const val DEFAULT_AUTHOR = "cloud-drive"
        const val DEFAULT_EMAIL = "cloud-drive@localhost"
    }
}
