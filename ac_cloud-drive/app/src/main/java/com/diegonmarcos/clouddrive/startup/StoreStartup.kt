package com.diegonmarcos.clouddrive.startup

import android.content.Context
import com.diegonmarcos.clouddrive.DriveDebugLog
import com.diegonmarcos.clouddrive.SharedStore
import com.diegonmarcos.cloudlib.sharedstore.StoreAccess
import com.diegonmarcos.cloudlib.sharedstore.StoreExec
import com.diegonmarcos.cloudlib.sharedstore.StorePaths
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * #1106 THE STARTUP SEQUENCE, as a state machine. Drive's storage lives in cld.termux's home (same shared uid),
 * so opening the file list used to assume Termux was installed, started and its $HOME ready. On a phone where
 * that was not true, the failure surfaced wherever the first File call happened. Now:
 *
 *   CheckingTerminal -> StartingTerminal -> WaitingForHome -> Mounting -> Ready
 *
 * with every way out typed ([StartupError]): TermuxMissing, TermuxNotStarted, HomeUnavailable, PermissionDenied,
 * MountFailed(stderr), Timeout. Nothing here runs on the main thread (the controller dispatches to IO), every
 * environment call is bounded, and nothing throws out of [StartupSequence.run]: a throw from the environment is
 * mapped to the typed error of the step it came from.
 */
enum class StartupStep { CHECK, START, WAIT_HOME, MOUNT }

sealed class StartupError(val step: StartupStep) {
    /** cloud-terminal is not installed (Install it). */
    data class TermuxMissing(val pkg: String) : StartupError(StartupStep.CHECK)
    /** It is installed but its service could not be started / bound (Open it, Retry). */
    data class TermuxNotStarted(val why: String) : StartupError(StartupStep.START)
    /** Started, but its $HOME never became readable within the wait. */
    data class HomeUnavailable(val path: String) : StartupError(StartupStep.WAIT_HOME)
    /** A grant is missing (the fleet exec permission, or all-files). */
    data class PermissionDenied(val permission: String, val at: StartupStep) : StartupError(at)
    /** The store could not be prepared; [stderr] is the platform's own words. */
    data class MountFailed(val stderr: String) : StartupError(StartupStep.MOUNT)
    /** A bounded call did not answer in time. */
    data class Timeout(val at: StartupStep, val afterMs: Long) : StartupError(at)
}

sealed class StartupState {
    object Idle : StartupState()
    object CheckingTerminal : StartupState()
    object StartingTerminal : StartupState()
    data class WaitingForHome(val attempt: Int) : StartupState()
    object Mounting : StartupState()
    object Ready : StartupState()
    data class Failed(val error: StartupError) : StartupState()

    val finished: Boolean get() = this is Ready || this is Failed
}

/** Everything the sequence needs from the device. Each call blocks at most [StartupLimits] and may throw: the sequence types it. */
interface StartupEnv {
    fun terminalPackage(): String
    fun terminalInstalled(): Boolean
    fun startTerminal(timeoutMs: Long): TerminalStart
    fun homePath(): String
    fun homeReady(): Boolean
    fun mount(timeoutMs: Long): MountResult
    fun sleep(ms: Long)
    fun nowMs(): Long
    fun log(msg: String)
}

sealed class TerminalStart {
    object Started : TerminalStart()
    data class Denied(val permission: String) : TerminalStart()
    data class Failed(val why: String) : TerminalStart()
}

sealed class MountResult {
    object Mounted : MountResult()
    data class Failed(val stderr: String) : MountResult()
}

/** Thrown by an environment call that outlived its bound. */
class StartupTimeout(val afterMs: Long) : RuntimeException("no answer in ${afterMs}ms")

data class StartupLimits(
    val startMs: Long = 15_000,
    val homeWaitMs: Long = 20_000,
    val mountMs: Long = 15_000,
    val backoffStartMs: Long = 250,
    val backoffMaxMs: Long = 2_000,
)

class StartupSequence(private val env: StartupEnv, private val limits: StartupLimits = StartupLimits()) {

    /** Runs the whole sequence, reporting each state; returns the finishing state ([StartupState.Ready] or [StartupState.Failed]). */
    fun run(onState: (StartupState) -> Unit = {}): StartupState {
        fun enter(s: StartupState): StartupState { env.log("state=$s"); onState(s); return s }
        fun fail(e: StartupError) = enter(StartupState.Failed(e))

        enter(StartupState.CheckingTerminal)
        val installed = try { env.terminalInstalled() } catch (t: Throwable) { false }
        if (!installed) return fail(StartupError.TermuxMissing(runCatching { env.terminalPackage() }.getOrDefault("")))

        enter(StartupState.StartingTerminal)
        var startError: StartupError? = null
        try {
            when (val r = env.startTerminal(limits.startMs)) {
                is TerminalStart.Started -> Unit
                is TerminalStart.Denied -> startError = StartupError.PermissionDenied(r.permission, StartupStep.START)
                is TerminalStart.Failed -> startError = StartupError.TermuxNotStarted(r.why)
            }
        } catch (t: StartupTimeout) {
            startError = StartupError.Timeout(StartupStep.START, t.afterMs)
        } catch (t: SecurityException) {
            startError = StartupError.PermissionDenied(t.message.orEmpty(), StartupStep.START)
        } catch (t: Throwable) {
            startError = StartupError.TermuxNotStarted(t.message ?: t.javaClass.simpleName)
        }
        if (startError != null) env.log("start failed: $startError")

        // Poll the home with backoff. A failed start is only fatal if the home is not there anyway
        // (the shared uid reaches the files of a stopped terminal).
        val begin = env.nowMs()
        var wait = limits.backoffStartMs
        var attempt = 0
        var ready = false
        while (true) {
            attempt++
            enter(StartupState.WaitingForHome(attempt))
            ready = try { env.homeReady() } catch (t: Throwable) { false }
            if (ready) break
            if (env.nowMs() - begin >= limits.homeWaitMs) break
            if (startError != null && attempt >= 2) break
            env.sleep(wait)
            wait = minOf(wait * 2, limits.backoffMaxMs)
        }
        if (!ready) return fail(startError ?: StartupError.HomeUnavailable(runCatching { env.homePath() }.getOrDefault("")))

        enter(StartupState.Mounting)
        try {
            when (val m = env.mount(limits.mountMs)) {
                is MountResult.Mounted -> Unit
                is MountResult.Failed -> return fail(StartupError.MountFailed(m.stderr))
            }
        } catch (t: StartupTimeout) {
            return fail(StartupError.Timeout(StartupStep.MOUNT, t.afterMs))
        } catch (t: SecurityException) {
            return fail(StartupError.PermissionDenied(t.message.orEmpty(), StartupStep.MOUNT))
        } catch (t: Throwable) {
            return fail(StartupError.MountFailed(t.message ?: t.javaClass.simpleName))
        }
        return enter(StartupState.Ready)
    }
}

/** The device implementation: every call is bounded by [TimeLimited] and nothing here runs on the UI thread. */
class AndroidStartupEnv(private val ctx: Context) : StartupEnv {
    private val owner = StoreAccess.ownerPackage()

    override fun terminalPackage(): String = owner
    override fun terminalInstalled(): Boolean = StoreAccess.ownerUid(ctx) != null

    override fun startTerminal(timeoutMs: Long): TerminalStart = try {
        // Binding CLOUD_EXEC (signature permission, one constellation key) starts cld.termux's process and its
        // service: the declared, granted way in. "true" is the cheapest script that proves the service answers.
        val r = TimeLimited.call(timeoutMs) { StoreExec(ctx).runHost("true", 10_000) }
        if (r.ok) TerminalStart.Started else TerminalStart.Failed(r.err.ifBlank { "exit ${r.exit}" })
    } catch (t: SecurityException) {
        TerminalStart.Denied("$owner.permission.CLOUD_EXEC")
    }

    override fun homePath(): String = StoreAccess.root().absolutePath

    // $HOME of the terminal is the parent of the store root; readable at once in the shared uid even when the
    // terminal is stopped, and only through the exec service otherwise.
    override fun homeReady(): Boolean {
        val home = StoreAccess.root().parentFile
        if (home != null && home.isDirectory && home.canRead()) return true
        return StoreAccess.mode(ctx) == StorePaths.Mode.EXEC
    }

    override fun mount(timeoutMs: Long): MountResult = TimeLimited.call(timeoutMs) {
        val root = StoreAccess.root()
        if (StoreAccess.mode(ctx) == StorePaths.Mode.EXEC) {
            val r = StoreExec(ctx).run("mkdir -p ~/${com.diegonmarcos.cloudlib.sharedstore.BuildConfig.STORE_DIR}", timeoutMs = 10_000)
            if (r.ok) MountResult.Mounted else MountResult.Failed(r.err.ifBlank { "exit ${r.exit}" })
        } else {
            val dir: File = SharedStore.root()
            if (dir.isDirectory && dir.canRead() && dir.canWrite()) MountResult.Mounted
            else MountResult.Failed("cannot read and write ${root.absolutePath} (exists=${dir.exists()})")
        }
    }

    override fun sleep(ms: Long) { Thread.sleep(ms) }
    override fun nowMs(): Long = System.currentTimeMillis()
    override fun log(msg: String) { DriveDebugLog.i(ctx, TAG, msg) }

    companion object { const val TAG = "DriveStartup" }
}

/** Runs [block] on a worker thread and waits at most [ms]; a miss is [StartupTimeout], a throw is the block's own. */
object TimeLimited {
    fun <T> call(ms: Long, block: () -> T): T {
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "drive-startup").apply { isDaemon = true } }
        try {
            return pool.submit(Callable { block() }).get(ms, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw StartupTimeout(ms)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } finally {
            pool.shutdownNow()
        }
    }
}

/** Owns the sequence for the UI: state as a flow, runs on IO, [retry] starts over. Never throws to the caller. */
class StoreStartupController(private val ctx: Context, private val scope: CoroutineScope, private val skip: Boolean = false) {
    private val _state = MutableStateFlow<StartupState>(StartupState.Idle)
    val state: StateFlow<StartupState> get() = _state

    @Volatile private var running = false

    fun start() {
        if (skip) { _state.value = StartupState.Ready; return }
        if (running) return
        running = true
        scope.launch(Dispatchers.IO) {
            try {
                StartupSequence(AndroidStartupEnv(ctx)).run { _state.value = it }
            } catch (t: Throwable) {
                _state.value = StartupState.Failed(StartupError.MountFailed(t.message ?: t.javaClass.simpleName))
            } finally {
                running = false
            }
        }
    }

    fun retry() { if (!running) { _state.value = StartupState.Idle; start() } }
}
