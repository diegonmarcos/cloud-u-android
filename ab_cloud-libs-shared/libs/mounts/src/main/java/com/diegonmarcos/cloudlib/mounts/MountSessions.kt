package com.diegonmarcos.cloudlib.mounts

import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-scoped owner of every live mount session. Before this existed a session was a
 * `remember`ed value in the browser composable and was closed in its DisposableEffect,
 * so leaving the screen, recreating the Activity or reopening the app always reconnected.
 *
 * Rules:
 *  - acquire() RE-ATTACHES: a live session whose health probe passes is returned as is,
 *    never reopened. Only a dead (or never opened) session is opened again, and a failed
 *    open is retried no sooner than the backoff allows (doubling, capped).
 *  - The handle the UI gets is a [Lease]: its close() releases the UI's hold and leaves
 *    the connection up. Only disconnect()/disconnectAll() really close.
 *  - Every call on a session is serialised by one lock per session, so the browser and
 *    the keeper service's health probe can share one connection (FTPClient is not
 *    thread-safe). A probe that finds the lock held treats the session as alive: a
 *    transfer is running over it.
 *  - transfers counts in-flight upload/download calls; the keeper service holds a
 *    partial wake lock only while it is above zero.
 *
 * The opener and the clock are injectable so the reattach and backoff rules are tested as
 * plain JVM code (MountSessionsTest).
 */
class MountSessions(
    private val opener: (MountSpec) -> RemoteFs,
    private val clock: () -> Long = System::currentTimeMillis,
    private val backoffInitialMs: Long = 5_000,
    private val backoffMaxMs: Long = 300_000,
) {
    enum class State { CONNECTED, CONNECTING, DEAD }
    data class Status(val id: String, val name: String, val state: State, val opens: Int, val lastError: String?, val retryAtMillis: Long)

    private inner class Session(var spec: MountSpec) {
        val lock = ReentrantLock()
        var fs: RemoteFs? = null
        var opens = 0
        var failures = 0
        var retryAt = 0L
        var lastError: String? = null
    }

    private val sessions = LinkedHashMap<String, Session>()
    private val _status = MutableStateFlow<List<Status>>(emptyList())
    val status: StateFlow<List<Status>> get() = _status
    private val _transfers = MutableStateFlow(0)
    val transfers: StateFlow<Int> get() = _transfers

    /** Ids of every mount this process keeps up (connected or waiting to reconnect). */
    fun ids(): Set<String> = synchronized(sessions) { sessions.keys.toSet() }
    fun liveCount(): Int = synchronized(sessions) { sessions.values.count { it.fs != null } }

    /**
     * The live session for [spec], re-attached when healthy, (re)opened only when dead.
     * Throws when the open fails or the backoff window has not elapsed yet.
     */
    fun acquire(spec: MountSpec, probe: Boolean = true): RemoteFs {
        val s = synchronized(sessions) { sessions.getOrPut(spec.id) { Session(spec) }.also { it.spec = spec } }
        s.lock.lock()
        try {
            val existing = s.fs
            if (existing != null && (!probe || healthy(s, existing))) return Lease(s)
            if (existing != null) { runCatching { existing.close() }; s.fs = null }
            val now = clock()
            if (now < s.retryAt) throw IllegalStateException("${spec.name}: reconnect backoff, next try in ${(s.retryAt - now) / 1000}s (${s.lastError ?: "dead"})")
            return try {
                s.fs = opener(spec); s.opens++; s.failures = 0; s.retryAt = 0; s.lastError = null
                runCatching { onOpened?.invoke(spec) }
                Lease(s)
            } catch (t: Throwable) {
                s.failures++; s.lastError = t.message ?: t.toString(); s.retryAt = now + backoffFor(s.failures)
                throw t
            }
        } finally { s.lock.unlock(); publish() }
    }

    /** Backoff after [failures] consecutive failed opens: initial, doubling, capped. */
    fun backoffFor(failures: Int): Long {
        if (failures <= 0) return 0
        var d = backoffInitialMs
        repeat(failures - 1) { d = (d * 2).coerceAtMost(backoffMaxMs) }
        return d.coerceAtMost(backoffMaxMs)
    }

    /**
     * Keeper pass: probe every session; a dead one is reopened if its backoff allows.
     * A healthy one is left alone. Returns the number of live sessions after the pass.
     */
    fun healthPass(): Int {
        val all = synchronized(sessions) { sessions.values.toList() }
        for (s in all) {
            if (!s.lock.tryLock()) continue          // a call is running over it: alive
            val fs = s.fs
            val alive = fs != null && runCatching { healthyUnlocked(s, fs) }.getOrDefault(false)
            s.lock.unlock()
            if (!alive) runCatching { acquire(s.spec, probe = false) }
        }
        publish()
        return liveCount()
    }

    fun disconnect(id: String) {
        val s = synchronized(sessions) { sessions.remove(id) } ?: return
        s.lock.lock(); try { runCatching { s.fs?.close() }; s.fs = null } finally { s.lock.unlock() }
        publish()
    }

    fun disconnectAll() { ids().forEach(::disconnect) }

    private fun healthy(s: Session, fs: RemoteFs): Boolean = runCatching { healthyUnlocked(s, fs) }.getOrDefault(false)
    private fun healthyUnlocked(s: Session, fs: RemoteFs): Boolean {
        val ok = runCatching { fs.list(s.spec.path.ifBlank { "/" }); true }.getOrDefault(false)
        if (!ok) { runCatching { fs.close() }; s.fs = null; s.lastError = "health probe failed" }
        return ok
    }

    private fun publish() {
        _status.value = synchronized(sessions) {
            sessions.values.map { s ->
                val st = when { s.fs != null -> State.CONNECTED; s.retryAt > clock() -> State.DEAD; else -> State.CONNECTING }
                Status(s.spec.id, s.spec.name, st, s.opens, s.lastError, s.retryAt)
            }
        }
    }

    /** The UI's handle: every call goes through the session lock; close() only releases. */
    private inner class Lease(private val s: Session) : RemoteFs {
        private fun <T> on(block: (RemoteFs) -> T): T {
            s.lock.lock()
            try { val fs = s.fs ?: throw IllegalStateException("${s.spec.name}: disconnected"); return block(fs) }
            catch (t: Throwable) { if (t is java.io.IOException) { runCatching { s.fs?.close() }; s.fs = null; publish() }; throw t }
            finally { s.lock.unlock() }
        }
        private fun <T> transfer(block: (RemoteFs) -> T): T {
            _transfers.value = _transfers.value + 1
            try { return on(block) } finally { _transfers.value = (_transfers.value - 1).coerceAtLeast(0) }
        }
        override fun list(path: String) = on { it.list(path) }
        override fun download(path: String, into: File, onProgress: (Long) -> Unit) = transfer { it.download(path, into, onProgress) }
        override fun upload(local: File, path: String, onProgress: (Long) -> Unit) = transfer { it.upload(local, path, onProgress) }
        override fun mkdir(path: String) = on { it.mkdir(path) }
        override fun delete(path: String, isDir: Boolean) = on { it.delete(path, isDir) }
        override fun rename(from: String, to: String) = on { it.rename(from, to) }
        override fun exec(command: String): String? = on { it.exec(command) }
        override fun close() { /* release only: the session outlives the screen */ }
    }

    companion object {
        /**
         * The one registry of this process. [configure] is called by the host app (its
         * keeper service / Application) with the declared backoff; until then defaults apply.
         */
        @Volatile private var instance: MountSessions? = null
        /** Host hook: a session was (re)opened, so the host keeps it alive (starts its keeper). */
        @Volatile var onOpened: ((MountSpec) -> Unit)? = null

        fun configure(opener: (MountSpec) -> RemoteFs, backoffInitialMs: Long, backoffMaxMs: Long): MountSessions = synchronized(this) {
            instance ?: MountSessions(opener, backoffInitialMs = backoffInitialMs, backoffMaxMs = backoffMaxMs).also { instance = it }
        }
        fun get(opener: (MountSpec) -> RemoteFs): MountSessions = instance ?: configure(opener, 5_000, 300_000)
    }
}
