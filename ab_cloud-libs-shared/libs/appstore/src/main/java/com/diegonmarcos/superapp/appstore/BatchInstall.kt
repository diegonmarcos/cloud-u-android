package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.util.Log
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.apk.VerifiedApk

/**
 * #625 — DOWNLOAD EVERYTHING FIRST, THEN INSTALL ONE BY ONE.
 *
 * ## What was wrong
 *
 * [Fleet.installAllLocked] already worked this way for the constellation pass:
 * phase 1 fetches every APK, phase 2 walks the staged list and commits. The
 * Store ▸ Phone batch did NOT. It called one function per row that resolved,
 * downloaded and installed that row before looking at the next one, so a batch
 * of ten was ten interleaved fetch/prompt pairs: every confirmation dialog was
 * followed by a wait on the NEXT app's network fetch, and a network that died
 * half way left the phone half-updated with the rest never downloaded.
 *
 * This is that same two-phase shape, for BOTH kinds of row — a fleet member and
 * an external app — in one engine, so neither caller can grow its own ordering.
 *
 * ## What it guarantees
 *
 * - Every [Engine.stage] runs before ANY [Engine.install]. Not "usually": the
 *   staged list is built to completion first, and that is the property the
 *   ordering test asserts.
 * - A download failure costs its own app and nothing else. The failure is
 *   recorded as that app's [Outcome] and the other apps still install — the
 *   whole reason this returns a list rather than a count.
 * - Per-app outcomes, never a bare "N failed". A batch that reports only a
 *   count is a batch whose failures you cannot act on.
 * - Cancel is honoured between apps in both phases, and what had already been
 *   staged is NOT discarded: the bytes stay in [com.diegonmarcos.superapp.updater.cache.ApkCache]
 *   for the retry, which is the entire point of the ticket.
 */
object BatchInstall {

    private const val TAG = "Store/Batch"

    /** One row to act on. Exactly one of [fleetApp]/[external] decides the path,
     *  the same discrimination the single-row button makes. */
    class Target(
        val pkg: String,
        val label: String,
        val fleetApp: Fleet.App?,
        val external: SourceResolver.External?,
    )

    /** Per-app result. [installed] is "handed to an installer without error",
     *  which for the PackageInstaller channel means a confirmation may still be
     *  pending — [message] carries anything worth showing. */
    class Outcome(val target: Target, val downloaded: Boolean, val installed: Boolean, val message: String?)

    enum class Phase { DOWNLOAD, INSTALL }

    /**
     * The two phases, as a seam.
     *
     * Not an abstraction for its own sake: the ORDER of these calls is the
     * guarantee this object exists to make, and a guarantee about ordering can
     * only be tested if something can watch the order. [default] is the real
     * one, and it is the only one any production caller passes.
     */
    interface Engine {
        fun stage(ctx: Context, t: Target): VerifiedApk
        fun install(ctx: Context, t: Target, apk: VerifiedApk): String?
    }

    /** The real engine: [FleetInstall] for a fleet member, [ExternalInstall]
     *  for an external app. Both are already split into stage/install. */
    fun engine(cfg: SourceResolver.Config): Engine = object : Engine {
        override fun stage(ctx: Context, t: Target): VerifiedApk {
            val fleetApp = t.fleetApp
            return if (fleetApp != null) FleetInstall.stage(ctx, fleetApp)
            else ExternalInstall.stage(ctx, cfg, t.external ?: SourceResolver.resolve(cfg, t.pkg))
        }
        override fun install(ctx: Context, t: Target, apk: VerifiedApk): String? {
            val fleetApp = t.fleetApp
            return if (fleetApp != null) FleetInstall.install(ctx, fleetApp, apk)
            else ExternalInstall.install(ctx, t.external ?: SourceResolver.resolve(cfg, t.pkg), apk)
        }
    }

    /**
     * Run [targets] in two phases. Blocking; call off the main thread.
     *
     * [onPhase] is told which phase, which row and how far along, so the caller
     * can label the progress row with "↓ 3/10" and then "3/10" without
     * re-deriving the batch's shape.
     */
    fun run(
        ctx: Context,
        targets: List<Target>,
        engine: Engine,
        onPhase: (Phase, Target, Int, Int) -> Unit = { _, _, _, _ -> },
    ): List<Outcome> {
        val outcomes = LinkedHashMap<String, Outcome>()
        targets.forEach { outcomes[it.pkg] = Outcome(it, downloaded = false, installed = false, message = null) }

        // ── PHASE 1: download every one of them, install nothing ────────────
        // Up to 3 at once (JobRunner's gate); each row is its own keyed job, so no row reads another's progress.
        StoreJobs.install()
        val stagedAt = arrayOfNulls<VerifiedApk>(targets.size)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(targets.size.coerceIn(1, 3))
        try {
            targets.mapIndexed { i, t ->
                StoreJobs.board.begin(t.pkg, t.label)
                pool.submit {
                    if (UpdateProgress.cancelRequested) {
                        synchronized(outcomes) { outcomes[t.pkg] = Outcome(t, false, false, "cancelled before it was downloaded") }
                        return@submit
                    }
                    onPhase(Phase.DOWNLOAD, t, i + 1, targets.size)
                    try {
                        val apk = UpdateProgress.withKey(t.pkg) { StoreJobs.runner.download(t.pkg) { engine.stage(ctx, t) } }
                        stagedAt[i] = apk
                        synchronized(outcomes) { outcomes[t.pkg] = Outcome(t, downloaded = true, installed = false, message = null) }
                    } catch (c: java.util.concurrent.CancellationException) {
                        synchronized(outcomes) { outcomes[t.pkg] = Outcome(t, false, false, "cancelled while downloading") }
                    } catch (th: Throwable) {
                        // One dead source must not cost the other nine their install.
                        val why = th.message ?: th.javaClass.simpleName
                        Log.w(TAG, "download ${t.label}: $why")
                        StoreJobs.board.fail(t.pkg, why)
                        synchronized(outcomes) { outcomes[t.pkg] = Outcome(t, false, false, why) }
                    }
                }
            }.forEach { it.get() }
        } finally { pool.shutdown() }
        val staged = targets.mapIndexedNotNull { i, t -> stagedAt[i]?.let { t to it } }

        // ── PHASE 2: install from the cache, strictly one at a time ─────────
        // Sequential is not a style choice: PackageInstaller sessions collide,
        // and each unanswered prompt holds a session against the 50-session cap.
        staged.forEachIndexed { i, (t, apk) ->
            if (UpdateProgress.cancelRequested) {
                outcomes[t.pkg] = Outcome(t, true, false,
                    "downloaded and kept in the cache — cancelled before it was installed")
                return@forEachIndexed
            }
            onPhase(Phase.INSTALL, t, i + 1, staged.size)
            val msg = UpdateProgress.withKey(t.pkg) { StoreJobs.runner.install(t.pkg) { engine.install(ctx, t, apk) } }
            outcomes[t.pkg] = Outcome(t, downloaded = true, installed = msg == null, message = msg)
        }
        // No row stays open past the batch (a cancelled or failed one was already closed by its own event).
        targets.forEach { StoreJobs.board.settle(it.pkg) }
        return targets.mapNotNull { outcomes[it.pkg] }
    }
}
