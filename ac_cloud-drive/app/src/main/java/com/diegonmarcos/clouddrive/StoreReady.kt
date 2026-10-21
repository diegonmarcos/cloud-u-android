package com.diegonmarcos.clouddrive

import android.content.Context
import com.diegonmarcos.cloudlib.sharedstore.StoreAccess
import com.diegonmarcos.cloudlib.sharedstore.StoreExec
import com.diegonmarcos.cloudlib.sharedstore.StorePaths

/**
 * Is cloud-shared-store really there, and can this app reach it? Asked before the seed touches
 * it, with a BOUNDED retry and ONE actionable sentence on failure (never a silent loop).
 * [verify] is pure (probe and sleep injected) so the JVM suite exercises the bound;
 * [probe] is the on-device look: DIRECT needs the folder, EXEC creates and checks it inside
 * cld.termux, NONE is the not-installed / not-allowed answer.
 */
object StoreReady {

    const val ATTEMPTS = 3
    const val PAUSE_MS = 2_000L

    const val NOT_REACHABLE = "the shared store is in Cloud Terminal Termux (cld.termux), which is not installed or does not allow this app: open it once, then retry"

    /** Null = ready; else the last attempt's sentence. Runs [probe] at most [attempts] times. */
    fun verify(attempts: Int = ATTEMPTS, pauseMs: Long = PAUSE_MS, sleep: (Long) -> Unit = { Thread.sleep(it) }, probe: () -> String?): String? {
        var why: String? = null
        for (i in 1..attempts.coerceAtLeast(1)) {
            why = runCatching(probe).getOrElse { "store check failed: ${it.message ?: it.javaClass.simpleName}" } ?: return null
            if (i < attempts) sleep(pauseMs)
        }
        return why
    }

    fun probe(ctx: Context): String? = when (StoreAccess.mode(ctx)) {
        StorePaths.Mode.NONE -> NOT_REACHABLE
        StorePaths.Mode.DIRECT ->
            if (SharedStore.root().isDirectory) null else "the shared store folder ${SharedStore.root().name} cannot be created from this app: open Cloud Terminal Termux once, then retry"
        StorePaths.Mode.EXEC -> {
            val guest = StoreAccess.paths.guestPath(StoreAccess.root().absolutePath, StoreAccess.userId()) ?: "/root/cloud-shared-store"
            val r = StoreExec(ctx).run("mkdir -p '$guest' && chmod -R u+rwX '$guest' && test -d '$guest'", timeoutMs = 30_000)
            if (r.ok) null else "the shared store did not answer inside cld.termux (exit ${r.exit}): ${r.err.ifBlank { r.out }.take(200)}"
        }
    }
}
