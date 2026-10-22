package com.diegonmarcos.clouddrive.startup

import android.content.Context
import android.os.Looper
import com.diegonmarcos.superapp.crash.CrashReports
import java.util.concurrent.atomic.AtomicInteger

/**
 * #1106 CRASH-SAFE STARTUP. Every startup phase runs through [step]: a throw is reported, counted and
 * swallowed so the next phase still runs. #1122 the report itself is libs:crash's (one file per crash in
 * `files/crashes`, the phone's local C3 sink, the generic crash screen with Copy / Share / Retry in safe mode)
 * and so is the uncaught-exception handler, installed for every app by libs:core; this object keeps only what
 * is Drive's own: the safe-mode flag, the failure count and the main-thread check.
 */
object StartupGuard {

    /** Safe mode (the crash screen's "Retry in safe mode"): skip migration, network, telemetry and background scheduling; open the file list. */
    @Volatile var safeMode = false

    private val failures = AtomicInteger(0)

    /** How many [step]s failed in THIS process since launch. */
    fun failureCount(): Int = failures.get()

    /**
     * Runs one startup phase. A throw is reported through [CrashReports.report], counted and swallowed so the
     * next phase still runs; returns whether the phase completed. In [safeMode] a phase marked [optional] is skipped.
     */
    fun step(ctx: Context, phase: String, optional: Boolean = false, block: () -> Unit): Boolean {
        if (optional && safeMode) return true
        return try {
            block(); true
        } catch (t: Throwable) {
            failures.incrementAndGet()
            runCatching { CrashReports.report(ctx, phase, t) }
            false
        }
    }

    /** True on the UI thread: a socket opened there throws NetworkOnMainThreadException (and on the phone resolves `*.app` on the public internet). */
    fun onMainThread(): Boolean = runCatching { Looper.getMainLooper().thread === Thread.currentThread() }.getOrDefault(false)
}
