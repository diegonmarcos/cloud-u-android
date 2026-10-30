package com.diegonmarcos.clouddrive.sync.jobs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

/**
 * #1315 THE ONE EXECUTOR of the Git page's jobs: every bulk or per-repository command (pull all, sync all, fetch all, clone
 * missing, keep depth, clone bare, rename, archive, delete, fix origin, one op on one clone) is a [Spec] submitted here and
 * nowhere else. ONE set of rules, JVM-tested (GitJobsTest):
 *  - BOUNDED: at most [Spec.workers] bodies of a job run at once (a Semaphore), never one coroutine per repository.
 *  - LOCKED: a path has one job at a time; a target whose path is locked is SKIPPED with [LOCKED], never run beside the other.
 *  - EXCLUSIVE: an exclusive job (one that writes clones) refuses to start while another exclusive job runs ([refusal]);
 *    a read (check, sizes, inventory) is not exclusive and runs alongside.
 *  - CANCELLABLE: [cancel] stops the targets not started yet; a body in flight ends on its own (a git process cannot be killed
 *    safely mid-write) and its outcome still lands.
 *  - ACCOUNTED: every target ends OK / SKIP / FAIL with one line, in [jobs] (the running ones and the finished ones until
 *    [dismiss]) for the job line, the report window and each row's outcome; [onEnd] runs once per job, cancelled or not.
 * Pure over coroutines: no Android, no git here; a body does the work and answers an [Outcome].
 */
class GitJobs(private val scope: CoroutineScope, private val clock: () -> Long = System::currentTimeMillis) {

    enum class Status { OK, SKIP, FAIL }

    data class Outcome(val status: Status, val message: String, val details: String = "")

    /** One target of a job: the folder it works on (host path, the lock key) and the name the report shows. */
    data class Target(val path: String, val name: String)

    data class Spec(
        val id: String,
        val kind: String,
        val label: String,
        val targets: List<Target>,
        val workers: Int = 1,
        val exclusive: Boolean = true,
        /** Runs once when the job ended (every target done or the job cancelled), on the job's own coroutine, never cancelled. */
        val onEnd: suspend (State) -> Unit = {},
        val body: suspend (Target) -> Outcome,
    )

    data class State(
        val id: String,
        val kind: String,
        val label: String,
        val total: Int,
        val done: Int = 0,
        val ok: Map<String, String> = emptyMap(),
        val skipped: Map<String, String> = emptyMap(),
        val failed: Map<String, String> = emptyMap(),
        val startedMs: Long = 0,
        val endedMs: Long? = null,
        val cancelled: Boolean = false,
    ) {
        val finished: Boolean get() = endedMs != null
        /** The outcome line of one target, or null when this job did not touch it. */
        fun outcomeOf(name: String): String? = ok[name]?.let { "ok: $it" } ?: skipped[name]?.let { "skipped: $it" } ?: failed[name]?.let { "failed: $it" }
        /** The report lines, skipped and failed first (what needs the owner), then ok; by name inside each group, so parallel workers give one order. */
        fun lines(): List<String> = skipped.toSortedMap().map { "skipped ${it.key}: ${it.value}" } + failed.toSortedMap().map { "failed ${it.key}: ${it.value}" } + ok.toSortedMap().map { "ok ${it.key}: ${it.value}" }
    }

    private val _jobs = MutableStateFlow<List<State>>(emptyList())
    /** Every job still running, then the finished ones until dismissed (newest last). */
    val jobs: StateFlow<List<State>> = _jobs

    private val locks = HashSet<String>()
    private val handles = HashMap<String, Job>()
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    /** The paths a job holds right now (one lock each). */
    val busy: StateFlow<Set<String>> = _busy

    /** Why [spec] cannot start now, or null when it can. */
    fun refusal(spec: Spec): String? = synchronized(locks) {
        if (_jobs.value.any { it.id == spec.id && !it.finished }) return "$EXCLUSIVE_RUNNING: ${spec.id}"
        if (spec.exclusive) _jobs.value.firstOrNull { !it.finished && exclusiveIds.contains(it.id) }?.let { return "$EXCLUSIVE_RUNNING: ${it.label}" }
        null
    }

    private val exclusiveIds = HashSet<String>()

    /** True while [path] is held by a job. */
    fun locked(path: String): Boolean = synchronized(locks) { path in locks }

    /** The running job that holds [path], or null. */
    fun running(path: String): State? = if (!locked(path)) null else _jobs.value.firstOrNull { !it.finished }

    /**
     * Start [spec]. Returns the coroutine job, or null when refused ([refusal]). Targets run through the Semaphore; a target
     * whose path is already locked is skipped at once. The state is published target by target.
     */
    fun submit(spec: Spec): Job? {
        synchronized(locks) {
            if (refusal(spec) != null) return null
            if (spec.exclusive) exclusiveIds += spec.id
            _jobs.update { l -> l.filter { it.id != spec.id } + State(spec.id, spec.kind, spec.label, spec.targets.size, startedMs = clock()) }
        }
        val gate = Semaphore(spec.workers.coerceAtLeast(1))
        val job = scope.launch {
            try {
                spec.targets.map { t ->
                    launch {
                        gate.acquire()
                        val claimed = synchronized(locks) { if (t.path.isNotBlank() && t.path in locks) false else { if (t.path.isNotBlank()) locks += t.path; true } }
                        if (claimed) _busy.value = synchronized(locks) { locks.toSet() }
                        try {
                            val o = if (!claimed) Outcome(Status.SKIP, LOCKED) else runCatching { spec.body(t) }.getOrElse { e ->
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                Outcome(Status.FAIL, e.message ?: e.javaClass.simpleName)
                            }
                            record(spec.id, t.name, o)
                        } finally {
                            if (claimed) { synchronized(locks) { locks -= t.path }; _busy.value = synchronized(locks) { locks.toSet() } }
                            gate.release()
                        }
                    }
                }.joinAll()
            } finally {
                withContext(NonCancellable) {
                    synchronized(locks) { exclusiveIds -= spec.id; handles -= spec.id }
                    _jobs.update { l -> l.map { if (it.id == spec.id && !it.finished) it.copy(endedMs = clock()) else it } }
                    _jobs.value.firstOrNull { it.id == spec.id }?.let { runCatching { spec.onEnd(it) } }
                }
            }
        }
        synchronized(locks) { handles[spec.id] = job }
        return job
    }

    private fun record(id: String, name: String, o: Outcome) {
        _jobs.update { l -> l.map { s -> if (s.id != id) s else s.copy(done = s.done + 1,
            ok = if (o.status == Status.OK) s.ok + (name to o.message) else s.ok,
            skipped = if (o.status == Status.SKIP) s.skipped + (name to o.message) else s.skipped,
            failed = if (o.status == Status.FAIL) s.failed + (name to o.message) else s.failed) } }
    }

    /** Stop the job: nothing new starts; a body already running ends on its own. */
    fun cancel(id: String) {
        _jobs.update { l -> l.map { if (it.id == id && !it.finished) it.copy(cancelled = true) else it } }
        synchronized(locks) { handles[id] }?.cancel()
    }

    /** Drop a finished job from [jobs] (the job line's ×). A running job stays. */
    fun dismiss(id: String) { _jobs.update { l -> l.filterNot { it.id == id && it.finished } } }

    /** The newest job of one of [kinds] (running first), or null. */
    fun latest(vararg kinds: String): State? = _jobs.value.filter { it.kind in kinds }.let { l -> l.firstOrNull { !it.finished } ?: l.lastOrNull() }

    companion object {
        const val LOCKED = "an operation is already running"
        const val EXCLUSIVE_RUNNING = "another command is running"
    }
}
