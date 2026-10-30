package com.diegonmarcos.clouddrive.sync.store

import android.content.Context
import android.os.Looper
import com.diegonmarcos.cloudlib.sharedstore.StoreExec

/**
 * #1315 THE ONE RUNNER of store scripts. Everything the Git layer does on the store (list, probe, clone, fetch, move, delete)
 * is a script handed to this shell; no other file binds CLOUD_EXEC (test-drive-git-store.sh S1). A shell that cannot run
 * answers a failed [StoreExec.Result] with the reason instead of throwing, so every caller reports one sentence and the
 * script's exit code stays the only truth about "gone" / "exists". Never on the main thread.
 */
interface StoreShell {
    val route: StoreRoute

    /** [script] in the guest with [stdin], at most [timeoutMs] (bounded by CLOUD_EXEC's own cap). */
    fun run(script: String, stdin: String? = null, timeoutMs: Int = 60_000): StoreExec.Result

    /** A long [script] (a clone, a fetch): detached in the guest, polled until it ends or [deadlineMs] passes. */
    fun job(script: String, stdin: String? = null, deadlineMs: Long = 30 * 60_000L): StoreExec.Result

    companion object {
        fun of(ctx: Context): StoreShell = TermuxShell(ctx.applicationContext)

        /** Single-quote [s] for sh (the one quoting rule every script builder uses). */
        fun q(s: String): String = StoreExec.q(s)

        const val UNREACHABLE = "cld.termux is not reachable (not installed, or CLOUD_EXEC not granted): nothing ran"
        const val MAIN_THREAD = "a store script cannot run on the main thread"
        /** The exit code of a refused run (never a real script's). */
        const val REFUSED = -1
    }
}

/** The live shell: CLOUD_EXEC in cld.termux on the live [StoreRoute]. */
class TermuxShell(private val ctx: Context) : StoreShell {
    override val route: StoreRoute get() = StoreRoute.of(ctx)

    private fun refusal(): StoreExec.Result? = when {
        runCatching { Looper.getMainLooper()?.thread === Thread.currentThread() }.getOrDefault(false) -> StoreExec.Result(StoreShell.REFUSED, "", StoreShell.MAIN_THREAD)
        !route.viaTermux -> StoreExec.Result(StoreShell.REFUSED, "", StoreShell.UNREACHABLE)
        else -> null
    }

    override fun run(script: String, stdin: String?, timeoutMs: Int): StoreExec.Result =
        refusal() ?: runCatching { StoreExec(ctx).run(script, stdin, timeoutMs) }.getOrElse { StoreExec.Result(StoreShell.REFUSED, "", it.message ?: it.toString()) }

    override fun job(script: String, stdin: String?, deadlineMs: Long): StoreExec.Result =
        refusal() ?: runCatching { StoreExec(ctx).job(script, stdin, deadlineMs) }.getOrElse { StoreExec.Result(StoreShell.REFUSED, "", it.message ?: it.toString()) }
}
