package com.diegonmarcos.clouddrive.sync

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * #850 THE ONE STATUS READER of the Sync ▸ Git page (and of /api/git/status).
 *
 * WHY IT EXISTS. Before it, GitSyncCoordinator.refresh() read EVERY repository's
 * `git status` serially inside ONE withContext block and published the glances only
 * when ALL of them had returned, with no bound. One clone whose status never returns
 * (a large working tree on the FUSE-backed shared storage, where JGit re-hashes every
 * file whose stat it cannot trust, or a held lock) therefore left every row on
 * "reading…" for ever — the map the rows read was never written. Each refresh() also
 * started a second full serial pass beside the first, so ops and re-entries stacked
 * readers on the same trees.
 *
 * THE CONTRACT, each clause tested by GitStatusReaderTest:
 *  - BOUNDED: every probe runs on its own daemon thread and is awaited for at most
 *    [timeoutMs]; past it the repository is ERROR("timeout …") — a terminal state.
 *  - OFF THE MAIN THREAD: probes run on dedicated threads, the waits on [scope].
 *  - PER REPOSITORY, LIMITED CONCURRENCY: at most [concurrency] probes are awaited at
 *    once and each publishes its own entry the moment it ends, so a large repository
 *    never holds the others back.
 *  - CACHED WITH A TIMESTAMP: an entry younger than [ttlMs] is not re-read unless forced.
 *  - NEVER RESTARTED: a repository with a probe in flight — or one still stuck past
 *    its timeout — is never probed again until that probe returns, however often the
 *    page recomposes or asks.
 *  - ALWAYS ENDS in CLEAN / AHEAD / BEHIND / DIRTY / NOT_CLONED / ERROR(reason).
 */
class GitStatusReader(
    private val probe: (File) -> Probe,
    private val timeoutMs: Long,
    concurrency: Int,
    private val ttlMs: Long,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** What one `git status` says, before it is classified. */
    data class Probe(
        val branch: String?,
        val upstream: String?,
        val ahead: Int,
        val behind: Int,
        val changed: Int,
        val conflicts: Int,
        val repositoryState: String = "SAFE",
    )

    enum class State { READING, CLEAN, AHEAD, BEHIND, DIRTY, NOT_CLONED, ERROR }

    data class Status(
        val state: State,
        val probe: Probe? = null,
        val reason: String? = null,
        val readAtMs: Long = 0,
        val tookMs: Long = 0,
    ) {
        val terminal: Boolean get() = state != State.READING

        /** The one-line words /api/git/status and the debug log use. */
        fun label(): String = when (state) {
            State.READING -> "reading"
            State.CLEAN -> "clean"
            State.AHEAD -> "ahead ${probe?.ahead ?: 0}"
            State.BEHIND -> "behind ${probe?.behind ?: 0}" + if ((probe?.ahead ?: 0) > 0) " ahead ${probe?.ahead}" else ""
            State.DIRTY -> "dirty ${probe?.changed ?: 0}" + if ((probe?.conflicts ?: 0) > 0) " conflicts ${probe?.conflicts}" else ""
            State.NOT_CLONED -> "not cloned"
            State.ERROR -> "error(${reason ?: "unknown"})"
        }
    }

    /** One repository to read: its stable id and its working tree. */
    data class Target(val id: String, val dir: File)

    private val permits = Semaphore(concurrency.coerceAtLeast(1))
    private val inFlight = HashSet<String>()
    private val threadSeq = AtomicInteger()

    private val _statuses = MutableStateFlow<Map<String, Status>>(emptyMap())
    val statuses: StateFlow<Map<String, Status>> = _statuses

    /**
     * Ask for [targets]. Returns the job that ends when every probe THIS call started has
     * ended (already-fresh and already-in-flight repositories start nothing). Safe to call
     * on every composition: it is idempotent while a read is running.
     */
    fun request(targets: List<Target>, force: Boolean = false): Job {
        val now = clock()
        val started = mutableListOf<Job>()
        for (t in targets) {
            val claim = synchronized(inFlight) {
                if (t.id in inFlight) false
                else {
                    val cached = _statuses.value[t.id]
                    val fresh = cached != null && cached.terminal && now - cached.readAtMs < ttlMs
                    if (fresh && !force) false else { inFlight += t.id; true }
                }
            }
            if (!claim) continue
            // Keep the last value visible while re-reading; only a never-read row shows READING.
            _statuses.update { m -> if (m.containsKey(t.id)) m else m + (t.id to Status(State.READING)) }
            started += scope.launch { readOne(t) }
        }
        return scope.launch { started.joinAll() }
    }

    /** Drop [id]'s cached entry so the next request reads it, e.g. after a pull. */
    fun invalidate(id: String) {
        _statuses.update { m -> m[id]?.let { m + (id to it.copy(readAtMs = 0)) } ?: m }
    }

    private suspend fun readOne(t: Target) {
        var stuck = false
        try {
            permits.withPermit {
                val begin = clock()
                if (!t.dir.isDirectory || !File(t.dir, ".git").exists()) {
                    publish(t.id, Status(State.NOT_CLONED, readAtMs = clock()))
                    return@withPermit
                }
                val done = CompletableDeferred<Result<Probe>>()
                val thread = Thread({
                    val r = runCatching { probe(t.dir) }
                    // A probe that outlived its timeout frees its repository only now; the
                    // completion and the release are one step under the reader's lock.
                    synchronized(inFlight) {
                        done.complete(r)
                        if (stuckIds.remove(t.id)) inFlight -= t.id
                    }
                }, "git-status-${threadSeq.incrementAndGet()}").apply { isDaemon = true }
                thread.start()
                val r = withTimeoutOrNull(timeoutMs) { done.await() }
                val took = clock() - begin
                val status = when {
                    r == null -> {
                        // Not killed (JGit ignores interrupts in places) — abandoned, and the
                        // repository stays claimed until the thread returns: never two probes.
                        synchronized(inFlight) {
                            if (!done.isCompleted) { stuckIds += t.id; stuck = true }
                        }
                        thread.interrupt()
                        Status(State.ERROR, reason = "timeout after " + if (timeoutMs >= 1000) "${timeoutMs / 1000}s" else "${timeoutMs}ms", readAtMs = clock(), tookMs = took)
                    }
                    r.isFailure -> Status(State.ERROR, reason = r.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName } ?: "unknown", readAtMs = clock(), tookMs = took)
                    else -> classify(r.getOrThrow(), clock(), took)
                }
                publish(t.id, status)
            }
        } finally {
            if (!stuck) synchronized(inFlight) { inFlight -= t.id }
        }
    }

    /** Repositories whose probe outlived its timeout; guarded by the [inFlight] lock. */
    private val stuckIds = HashSet<String>()

    /** True while [id] has a probe running (including one abandoned past its timeout). */
    fun busy(id: String): Boolean = synchronized(inFlight) { id in inFlight }

    private fun publish(id: String, s: Status) = _statuses.update { it + (id to s) }

    companion object {
        /** The precedence the row's light follows: an uncommitted change outranks the remote. */
        fun classify(p: Probe, now: Long, took: Long = 0): Status = Status(
            state = when {
                p.changed > 0 || p.conflicts > 0 -> State.DIRTY
                p.behind > 0 -> State.BEHIND
                p.ahead > 0 -> State.AHEAD
                else -> State.CLEAN
            },
            probe = p, readAtMs = now, tookMs = took,
        )
    }
}
