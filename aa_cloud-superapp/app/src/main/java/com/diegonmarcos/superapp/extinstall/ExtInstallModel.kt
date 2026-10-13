package com.diegonmarcos.superapp.extinstall

import com.diegonmarcos.superapp.appstore.JobBoard
import com.diegonmarcos.superapp.updater.UpdateProgress

/**
 * The `extapp:` install progress, as ONE state store read by both the
 * notification and the tile ring.
 *
 * NOT A SECOND INSTALLER. The bytes move through the fleet's own engine:
 * libs:updater's ApkInstallWorker (WorkManager, foreground, the shared downloader
 * with HTTP Range resume, sha256 sidecar / size / structural verify,
 * system install session + its result receiver), publishing on
 * [UpdateProgress]. The per-app state is libs:appstore's [JobBoard] — the very
 * board the Cloud Store's rows and progress panel draw from (StoreJobs.board).
 * [ExtInstallTracker] is only the adapter that files the worker's events on the
 * right row, plus the things a board row does not carry (speed, source host).
 *
 * Everything here is pure Kotlin; a JVM test drives it.
 */
enum class ExtPhase { QUEUED, DOWNLOADING, VERIFYING, INSTALLING, DONE, FAILED, CANCELLED;
    val active get() = this == QUEUED || this == DOWNLOADING || this == VERIFYING || this == INSTALLING
}

data class ExtSnapshot(
    val pkg: String,
    val label: String,
    val phase: ExtPhase,
    val bytes: Long = 0,
    val total: Long = 0,
    val percent: Int = -1,
    val bytesPerSec: Long = 0,
    val reason: String = "",
    val sourceHost: String = "",
) {
    /** Seconds left at the current speed; -1 when it cannot be said. */
    val etaSeconds: Long get() =
        if (phase == ExtPhase.DOWNLOADING && total > 0 && bytesPerSec > 0) (total - bytes).coerceAtLeast(0) / bytesPerSec else -1
}

const val CANCELLED_REASON = "cancelled"


object ExtInstallFormat {
    fun bytes(b: Long): String = when {
        b >= 1_000_000_000 -> String.format(java.util.Locale.US, "%.1f GB", b / 1e9)
        b >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f MB", b / 1e6)
        b >= 1_000 -> String.format(java.util.Locale.US, "%.0f kB", b / 1e3)
        else -> "$b B"
    }
    fun eta(s: Long): String = if (s < 0) "—" else if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s"
    fun host(url: String): String = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
}

/** What the tile ring draws. [fraction] < 0 = indeterminate. */
data class TileProgress(val fraction: Float, val label: String, val phase: ExtPhase)

object ExtInstallViews {

    fun phaseOf(row: JobBoard.Row): ExtPhase = when (row.phase) {
        JobBoard.Phase.QUEUED -> ExtPhase.QUEUED
        JobBoard.Phase.DOWNLOADING -> ExtPhase.DOWNLOADING
        JobBoard.Phase.VERIFYING -> ExtPhase.VERIFYING
        JobBoard.Phase.INSTALLING -> ExtPhase.INSTALLING
        JobBoard.Phase.DONE -> ExtPhase.DONE
        JobBoard.Phase.FAILED -> if (row.reason == CANCELLED_REASON) ExtPhase.CANCELLED else ExtPhase.FAILED
    }

    /** The tile's ring; null = nothing to draw (no job, or done). */
    fun tile(s: ExtSnapshot?): TileProgress? {
        if (s == null || s.phase == ExtPhase.DONE) return null
        val frac = if (s.phase == ExtPhase.DOWNLOADING && s.percent >= 0) s.percent / 100f else -1f
        val label = when (s.phase) {
            ExtPhase.QUEUED -> "Queued"
            ExtPhase.DOWNLOADING -> if (s.percent >= 0) "Downloading ${s.percent}%" else "Downloading…"
            ExtPhase.VERIFYING -> "Verifying"
            ExtPhase.INSTALLING -> "Installing"
            ExtPhase.FAILED -> "Failed"
            ExtPhase.CANCELLED -> "Cancelled"
            ExtPhase.DONE -> ""
        }
        val fixed = when (s.phase) { ExtPhase.FAILED, ExtPhase.CANCELLED -> 0f; else -> frac }
        return TileProgress(fixed, label, s.phase)
    }

    enum class Action { CANCEL, RETRY, RESTART, OPEN }

    data class Notice(
        val title: String,
        val text: String,
        val max: Int,
        val progress: Int,
        val indeterminate: Boolean,
        val ongoing: Boolean,
        val showProgress: Boolean,
        val actions: List<Action>,
    )

    /** The notification for one app's job, per state. */
    fun notice(s: ExtSnapshot): Notice {
        val name = s.label.ifEmpty { s.pkg }
        return when (s.phase) {
            ExtPhase.QUEUED -> Notice("Queued · $name", "Waiting to download", 0, 0, true, true, true, listOf(Action.CANCEL))
            ExtPhase.DOWNLOADING -> {
                val pct = if (s.percent >= 0) "${s.percent}%" else "…"
                val size = if (s.total > 0) "${ExtInstallFormat.bytes(s.bytes)} / ${ExtInstallFormat.bytes(s.total)}"
                           else ExtInstallFormat.bytes(s.bytes)
                val speed = if (s.bytesPerSec > 0) " · ${ExtInstallFormat.bytes(s.bytesPerSec)}/s" else ""
                Notice("Downloading $pct · $name", "$size$speed", 100, s.percent.coerceAtLeast(0),
                    s.percent < 0, true, true, listOf(Action.CANCEL))
            }
            ExtPhase.VERIFYING -> Notice("Verifying · $name", "Checking the download", 0, 0, true, true, true, listOf(Action.CANCEL))
            ExtPhase.INSTALLING -> Notice("Installing · $name", "Handing it to the system installer", 0, 0, true, true, true, emptyList())
            ExtPhase.DONE -> Notice("Done · $name", "Installed", 0, 0, false, false, false, listOf(Action.OPEN))
            ExtPhase.FAILED -> Notice("Failed · $name", s.reason.ifEmpty { "unknown error" }, 0, 0, false, false, false, listOf(Action.RETRY))
            ExtPhase.CANCELLED -> Notice("Cancelled · $name", "Download cancelled", 0, 0, false, false, false, listOf(Action.RESTART))
        }
    }
}

/**
 * At most one non-terminal notification update per [minIntervalMs] per job;
 * a phase change always goes through so Installing / Done / Failed are never lost.
 */
class PostThrottle(private val minIntervalMs: Long = 333) {
    private val last = HashMap<String, Pair<Long, ExtPhase>>()
    @Synchronized fun allow(key: String, phase: ExtPhase, now: Long): Boolean {
        val prev = last[key]
        val ok = prev == null || prev.second != phase || now - prev.first >= minIntervalMs
        if (ok) last[key] = now to phase
        return ok
    }
}

/**
 * Files the worker's [UpdateProgress] events on the [JobBoard] row of the
 * package it is installing, for the packages this launcher started.
 *
 * The worker names its job ([UpdateProgress.Job.pkg]) before it publishes, and
 * the install result receiver's Failed carries the package; a Done carries
 * none, so it lands on the one tracked row that is INSTALLING (the install
 * gate lets only one session run at a time).
 */
class ExtInstallTracker(val board: JobBoard, private val now: () -> Long = System::currentTimeMillis) {

    private class Meta(val label: String, val url: String) {
        var lastT = 0L; var lastB = 0L; var bps = 0L
    }
    private val meta = LinkedHashMap<String, Meta>()

    @Synchronized fun tracked(pkg: String) = pkg in meta
    @Synchronized fun trackedPackages(): Set<String> = meta.keys.toSet()

    /** queued: a fresh start or a restart. */
    @Synchronized fun start(pkg: String, label: String, url: String) {
        meta[pkg] = Meta(label, url)
        board.queue(pkg, label, "download")
    }

    @Synchronized fun restart(pkg: String): Boolean {
        val m = meta[pkg] ?: return false
        start(pkg, m.label, m.url); return true
    }

    @Synchronized fun cancel(pkg: String) { if (pkg in meta) board.fail(pkg, CANCELLED_REASON) }

    /** The package is on the device now (seen on resume / package-added). */
    @Synchronized fun installed(pkg: String) { if (pkg in meta) board.done(pkg) }

    @Synchronized fun urlOf(pkg: String): String? = meta[pkg]?.url
    @Synchronized fun labelOf(pkg: String): String? = meta[pkg]?.label

    /** Apply one global event. Returns the package whose row it touched, or null. */
    @Synchronized fun onEvent(state: UpdateProgress.State, job: UpdateProgress.Job?): String? {
        val jobPkg = job?.pkg?.takeIf { it in meta }
        val key = when (state) {
            is UpdateProgress.State.Failed -> state.pkg.takeIf { it in meta } ?: jobPkg
            is UpdateProgress.State.Done ->
                jobPkg?.takeIf { board.row(it)?.phase == JobBoard.Phase.INSTALLING }
                    ?: meta.keys.singleOrNull { board.row(it)?.phase == JobBoard.Phase.INSTALLING }
            else -> jobPkg
        } ?: return null
        when (state) {
            is UpdateProgress.State.Downloading -> {
                board.download(key, state.bytes, state.total)
                sample(key, state.bytes)
            }
            is UpdateProgress.State.Installing -> board.phase(key, JobBoard.Phase.INSTALLING)
            is UpdateProgress.State.Done -> board.done(key)
            is UpdateProgress.State.Failed -> board.fail(key, state.message)
            is UpdateProgress.State.Cancelled -> board.fail(key, CANCELLED_REASON)
            else -> {}
        }
        if (job != null && job.pkg == key && state !is UpdateProgress.State.Done && state !is UpdateProgress.State.Failed) {
            when (job.stage) {
                UpdateProgress.STAGE_VERIFYING -> board.phase(key, JobBoard.Phase.VERIFYING)
                UpdateProgress.STAGE_INSTALLING -> board.phase(key, JobBoard.Phase.INSTALLING)
            }
        }
        return key
    }

    private fun sample(key: String, bytes: Long) {
        val m = meta[key] ?: return
        val t = now()
        if (m.lastT == 0L || bytes < m.lastB) { m.lastT = t; m.lastB = bytes; return }
        val dt = t - m.lastT
        if (dt < 250) return
        val inst = (bytes - m.lastB) * 1000 / dt
        m.bps = if (m.bps == 0L) inst else (m.bps * 7 + inst * 3) / 10
        m.lastT = t; m.lastB = bytes
    }

    @Synchronized fun snapshot(pkg: String): ExtSnapshot? {
        val m = meta[pkg] ?: return null
        val r = board.row(pkg) ?: return null
        return ExtSnapshot(pkg, m.label.ifEmpty { r.label }, ExtInstallViews.phaseOf(r), r.bytes, r.total, r.percent,
            m.bps, r.reason, ExtInstallFormat.host(m.url))
    }
}
