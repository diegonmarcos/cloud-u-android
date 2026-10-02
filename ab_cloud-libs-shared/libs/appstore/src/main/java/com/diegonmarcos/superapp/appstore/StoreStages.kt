package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.FleetIdentity
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.apk.VerifiedApk
import com.diegonmarcos.superapp.updater.cache.ApkCache
import java.util.concurrent.ConcurrentHashMap

/**
 * #774 DOWNLOAD → INSTALL → CLEAR, as three stages a Store row can show and a
 * user (or the auto chain) can drive one at a time. No UI here: the Cloud tab,
 * the debug API and the auto chain all read [stage] and call the same three
 * verbs, so the screen and `/api/store/stage` cannot disagree.
 *
 * The stage is DERIVED, never stored: the persistent cache ([ApkCache], under
 * no_backup) plus what the device has installed plus the last outcome note
 * ([ApkCache.note], written by the download path and the install receiver).
 * That is what makes it survive an install cancel, a failure, an app restart
 * and a reboot — there is no in-memory state to lose except "a verb is running
 * right now", which is exactly the part that should vanish with the process.
 *
 *  - Download fetches into the cache ([Fleet.download]: cache-hit first, then
 *    Range-resumable sources, verified by the release sha256).
 *  - Install is the WHOLE chain (#784): no installable cached APK → Download
 *    first, then hand the CACHED file to the existing installer ladder
 *    ([FleetInstall.install] → shell `pm install`, else a PackageInstaller
 *    session), then Clear once [ApkCache.landed] proves the install. It is
 *    never disabled for want of a cache. A cancel or failure stops the chain at
 *    that stage and leaves the file; Install again resumes from there.
 *  - Clear deletes the app's cached APK(s) — on demand, or by the chain once
 *    the install is proven.
 *  - [downloadAll] / [updateAll] are the same verbs over a list, with a
 *    per-app report and a dry run; Update all installs from the cache with no
 *    network at all.
 */
object StoreStages {

    private const val TAG = "StoreStages"

    const val DOWNLOAD = "download"
    const val INSTALL = "install"
    const val CLEAR = "clear"

    /**
     * One row's truth. [id] is the machine word (update_available, not_installed,
     * downloading, cached, installing, installed, error, blocked, unknown);
     * [text] is the sentence the row shows; [actions] are the verbs that make
     * sense from here, in stage order; [failedAt] names the stage an error
     * stopped at, so the user takes over from exactly there.
     */
    class Stage(
        val id: String,
        val text: String,
        val actions: List<String>,
        val cached: ApkCache.Entry? = null,
        val failedAt: String? = null,
    )

    /** pkg → the verb running right now. In-memory on purpose: see the header. */
    private val busy = ConcurrentHashMap<String, String>()

    /**
     * The installer the Install stage hands the cached file to. A seam with
     * the production ladder as its only default — the same shape as
     * [Fleet.downgradePolicy] — so a test can stand in for Android's install
     * sheet (accept / cancel) without a device. Returns the message to show,
     * or null on handover/success, exactly like [FleetInstall.install].
     */
    @Volatile
    var installer: (Context, Fleet.App, VerifiedApk) -> String? = FleetInstall::install

    private fun pkgs(app: Fleet.App) = setOfNotNull(app.pkg, app.altId)

    /** The newest complete cached APK for [app], or null. A record names the
     *  package; a record-less file (its manifest would not parse) is still the
     *  app's when the store named it `fleet-<id>-…`. */
    fun cachedFor(ctx: Context, app: Fleet.App): ApkCache.Entry? =
        ApkCache.entries(ctx).filter { e ->
            !e.partial && (e.record?.pkg?.let { it in pkgs(app) }
                ?: e.file.name.startsWith("fleet-${app.id}-"))
        }.maxWithOrNull(compareBy({ it.record?.versionCode ?: 0L }, { it.modifiedAt }))

    /** Bytes of an interrupted download waiting to be resumed, or 0. */
    fun partialBytes(ctx: Context, app: Fleet.App): Long =
        ApkCache.entries(ctx).filter { it.partial && it.file.name.startsWith("fleet-${app.id}-") }
            .sumOf { it.bytes }

    private fun mb(b: Long) = FleetIdentity.human(b)

    /**
     * The row's stage. [remote] is the network answer ([Fleet.status]) when the
     * caller has one; without it the stage still says everything the device
     * itself knows — cached, installed, last error — and only "is there an
     * update" is left unknown.
     */
    fun stage(ctx: Context, app: Fleet.App, remote: Fleet.State? = null): Stage {
        when (busy[app.pkg]) {
            DOWNLOAD -> {
                val d = UpdateProgress.state as? UpdateProgress.State.Downloading
                return Stage("downloading",
                    if (d != null) "downloading ${d.percent}% · ${mb(d.bytes)}" +
                        (if (d.total > 0) " of ${mb(d.total)}" else "") else "downloading…",
                    emptyList())
            }
            INSTALL -> return Stage("installing", "installing from cache…", emptyList(), cachedFor(ctx, app))
        }
        // #780 PRECEDENCE: busy → ACTIONABLE cache → remote → installed. A
        // cache whose build is already on the device (landed) or that the
        // remote has moved past is NOT actionable, and it must never stand in
        // front of the remote's answer: it used to return "installed · Clear"
        // before `remote` was even read, so any app whose LAST update was still
        // cached never showed its NEXT one — no Download, fleet-wide.
        var note = ApkCache.noteOf(ctx, app.pkg)
        val e = reapLanded(ctx, app)
        val act = e?.takeIf { actionable(ctx, app, it, remote) }
        // An Install-stage note with nothing installable behind it is history,
        // and left in place it holds the unattended pass (Fleet.heldAtInstall)
        // off this app forever.
        if (act == null && note?.stage == ApkCache.STAGE_INSTALL) { ApkCache.clearNote(ctx, app.pkg); note = null }
        if (act != null) {
            val ver = act.record?.versionCode?.let { " v$it" } ?: ""
            val why = note?.takeIf { it.stage == ApkCache.STAGE_INSTALL }?.message
            // Download stays live beside Install: a new download replaces the
            // cached APK, so it never has to wait for a Clear (owner rule).
            return if (why != null)
                Stage("error", "cached$ver ${mb(act.bytes)} · install did not finish: $why — Install retries " +
                    "from the cache", listOf(INSTALL, DOWNLOAD, CLEAR), act, failedAt = INSTALL)
            else Stage("cached", "cached$ver ${mb(act.bytes)} (ready to install)", listOf(INSTALL, DOWNLOAD, CLEAR), act)
        }
        // A non-actionable cache left behind: Clear is an EXTRA verb, never the only one.
        val extra = if (e != null) listOf(CLEAR) else emptyList()
        // #784 Install is live wherever Download is: it IS Download → Install →
        // Clear, so having nothing cached is no reason to grey it out.
        val fetch = listOf(INSTALL, DOWNLOAD) + extra
        val kept = e?.let { " · cached APK ${mb(it.bytes)} — Clear frees it" } ?: ""
        val part = partialBytes(ctx, app)
        val partNote = if (part > 0) " · ${mb(part)} kept, Download resumes" else ""
        if (note?.stage == ApkCache.STAGE_DOWNLOAD)
            return Stage("error", "download failed: ${note.message}$partNote", fetch,
                failedAt = DOWNLOAD)
        return when (remote) {
            is Fleet.State.UpdateAvailable ->
                Stage("update_available", "update available · ${mb(remote.bytes)}$partNote", fetch)
            is Fleet.State.Missing ->
                Stage("not_installed", "not installed · ${mb(remote.bytes)}$partNote", fetch)
            is Fleet.State.Installed -> Stage("installed", "installed ${remote.versionName}$kept", extra)
            is Fleet.State.Blocked -> Stage("blocked", "not published", extra)
            is Fleet.State.Error -> Stage("error", remote.message, fetch, failedAt = DOWNLOAD)
            null -> when {
                part > 0 -> Stage("update_available", "download interrupted$partNote", fetch)
                e != null -> Stage("installed", "installed${e.record?.versionCode?.let { " v$it" } ?: ""}$kept", extra)
                Fleet.installedId(ctx, app) != null ->
                    Stage("installed", "installed · not checked for an update", emptyList())
                else -> Stage("unknown", "not checked yet", fetch)
            }
        }
    }

    /**
     * #780 May [e] be installed from here? Only when it is NOT already what the
     * device runs ([ApkCache.landed]: installed versionCode ≥ cached, or the
     * same bytes) and the remote has not moved past it. The remote digest is
     * the APK's own sha256 on both channels (the release sidecar; the GHCR blob
     * digest) — "release" is the no-sidecar placeholder and cannot tell, so it
     * leaves the cache actionable.
     */
    private fun actionable(ctx: Context, app: Fleet.App, e: ApkCache.Entry, remote: Fleet.State?): Boolean {
        if (ApkCache.landed(ctx, e, app.pkg)) return false
        val r = remote as? Fleet.State.UpdateAvailable ?: return true
        val sha = e.record?.sha256 ?: return true
        return r.remoteDigest12 == "release" || sha.startsWith(r.remoteDigest12, ignoreCase = true)
    }

    /**
     * Owner rule (#780, held in every path by #784): a cached APK whose bytes
     * ARE the installed APK clears itself — [ApkCache.reapIfInstalled] deletes
     * only on that proof. Returns what is cached afterwards.
     * ponytail: hashes a landed file on each call until it goes; record-less or
     * different-bytes caches stay (Clear offered).
     */
    private fun reapLanded(ctx: Context, app: Fleet.App): ApkCache.Entry? {
        val e = cachedFor(ctx, app) ?: return null
        if (!ApkCache.landed(ctx, e, app.pkg)) return e
        return if (ApkCache.reapIfInstalled(ctx, e.file) is ApkCache.Retention.Reaped) cachedFor(ctx, app) else e
    }

    /** The newest cached APK that [actionable] allows, or null. */
    fun actionableFor(ctx: Context, app: Fleet.App, remote: Fleet.State? = null): ApkCache.Entry? =
        cachedFor(ctx, app)?.takeIf { actionable(ctx, app, it, remote) }

    /** Stage 1. Blocking — call off the main thread. Errors are already in the
     *  note ([Fleet.download] writes it); the returned stage shows them. */
    fun download(ctx: Context, app: Fleet.App): Stage {
        if (busy.putIfAbsent(app.pkg, DOWNLOAD) != null) return stage(ctx, app)
        try {
            UpdateProgress.beginDownload()
            Fleet.download(ctx, app)
        } catch (t: Throwable) {
            Log.w(TAG, "download ${app.id} stopped: ${t.message}")
        } finally {
            busy.remove(app.pkg)
        }
        return stage(ctx, app)
    }

    /**
     * #784 Install = the whole chain, stopping at the FIRST stage that does not
     * complete and leaving the row on it — so the next Install (or the user's
     * Download) takes over from exactly there, never from zero:
     *  1. no installable cached APK ([actionableFor], against [remote] when the
     *     caller has it) → Download (cache hit first, Range-resumable, sha-verified);
     *  2. install the cached file;
     *  3. once [ApkCache.landed] proves the install, Clear.
     * Blocking — call off the main thread.
     */
    fun install(ctx: Context, app: Fleet.App, remote: Fleet.State? = null): Stage {
        if (actionableFor(ctx, app, remote) == null) {
            download(ctx, app)
            if (actionableFor(ctx, app) == null) return stage(ctx, app)
        }
        installCached(ctx, app)
        val e = cachedFor(ctx, app) ?: return stage(ctx, app)   // the receiver already reaped it
        if (!ApkCache.landed(ctx, e, app.pkg)) return stage(ctx, app)
        return clear(ctx, app)
    }

    /** Stage 2 alone, from the cache only; the outcome lands in the note. */
    private fun installCached(ctx: Context, app: Fleet.App) {
        val e = actionableFor(ctx, app) ?: return
        if (busy.putIfAbsent(app.pkg, INSTALL) != null) return
        try {
            // Re-verify before handing bytes to the installer: against the
            // digest recorded at download time, or — record-less — against the
            // published sidecar. A record that no longer matches means the file
            // is known-bad, so it goes; anything unverifiable is KEPT and named.
            val sha = e.record?.sha256
            val v = if (sha != null) VerifiedApk.byDigest(e.file, sha)
                    else Fleet.cachedRelease(ctx, app)
            if (v == null) {
                if (sha != null) ApkCache.drop(e.file)
                ApkCache.note(ctx, app.pkg, ApkCache.STAGE_DOWNLOAD,
                    if (sha != null) "the cached APK no longer matches what was downloaded — Download again"
                    else "the cached APK cannot be verified (no record, sidecar unreachable) — kept")
                return
            }
            ApkCache.clearNote(ctx, app.pkg)
            installer(ctx, app, v)?.let { msg ->
                if (ApkCache.noteOf(ctx, app.pkg) == null) ApkCache.note(ctx, app.pkg, ApkCache.STAGE_INSTALL, msg)
            }
        } catch (t: Throwable) {
            ApkCache.note(ctx, app.pkg, ApkCache.STAGE_INSTALL, t.message ?: t.javaClass.simpleName)
        } finally {
            busy.remove(app.pkg)
        }
    }

    /** Stage 3. Deletes this app's cached APK(s) and any partial. On demand it
     *  is the user's call; the chain only calls it after [ApkCache.landed]. */
    fun clear(ctx: Context, app: Fleet.App): Stage {
        ApkCache.entries(ctx).filter { e ->
            e.record?.pkg?.let { it in pkgs(app) } ?: e.file.name.startsWith("fleet-${app.id}-")
        }.forEach { ApkCache.drop(it.file) }
        ApkCache.clearNote(ctx, app.pkg)
        return stage(ctx, app)
    }

    // ── #784 the batch verbs: Download all, Update all ───────────────────

    /** Per-app results. A dry run reports the verb it WOULD run instead. */
    const val INSTALLED = "installed"
    const val PENDING = "pending"
    const val DOWNLOADED = "downloaded"
    const val CACHED = "cached"
    const val SKIPPED = "skipped"
    const val NEEDS_DOWNLOAD = "needs_download"
    const val NO_ROOM = "no_room"
    const val FAILED = "failed"

    class Outcome(val app: Fleet.App, val result: String, val text: String, val failedAt: String? = null)

    class Batch(
        val op: String, val dryRun: Boolean, val online: Boolean, val outcomes: List<Outcome>,
        /** Bytes the downloads need, and the room there was for them (Download all). */
        val needBytes: Long = 0, val roomBytes: Long = 0,
    ) {
        fun count(result: String) = outcomes.count { it.result == result }
        val summary: String get() = outcomes.groupingBy { it.result }.eachCount().entries
            .joinToString(" · ") { "${it.value} ${it.key.replace('_', ' ')}" }
    }

    /** Is there a network that claims internet? Batch callers pass it in, so a
     *  test (or `/api/store/updateAll?offline=1`) can take the network away. */
    fun isOnline(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    /**
     * Bytes Download all may still write: the disk's free space, and the cache's
     * own declared bound ([ApkCache.evict] deletes past it — oldest first, i.e.
     * what this same batch fetched a minute ago). A seam like [installer] so a
     * test can stand in for a full disk.
     */
    @Volatile
    var room: (Context) -> Long = { c ->
        minOf(ApkCache.dir(c).usableSpace,
            com.diegonmarcos.superapp.updater.BuildConfig.APK_CACHE_MAX_BYTES - ApkCache.totalBytes(c)).coerceAtLeast(0)
    }

    private const val HOST = "the host — its own updater runs after the batch"

    private fun installedCode(ctx: Context, app: Fleet.App): Pair<Long, Long>? = pkgs(app).firstNotNullOfOrNull { p ->
        runCatching {
            @Suppress("DEPRECATION") val pi = ctx.packageManager.getPackageInfo(p, 0)
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
             else @Suppress("DEPRECATION") pi.versionCode.toLong()) to pi.lastUpdateTime
        }.getOrNull()
    }

    /**
     * Download every app/lib with an update (or not installed) into the cache,
     * installing nothing — pre-fetch on Wi-Fi, install later (offline if need
     * be). An app that would not fit in [room] is NOT started: filling the disk
     * or the cache bound would only evict what this batch already fetched.
     * [dryRun] checks and sizes everything and downloads nothing. Blocking.
     */
    fun downloadAll(ctx: Context, apps: List<Fleet.App>, online: Boolean = isOnline(ctx), dryRun: Boolean = false): Batch {
        val todo = apps.filter { !it.blocked }
        val startRoom = room(ctx)
        var free = startRoom
        var need = 0L
        val out = ArrayList<Outcome>()
        if (!dryRun) UpdateProgress.beginDownload()
        for ((i, app) in todo.withIndex()) {
            if (app.pkg == ctx.packageName) { out += Outcome(app, SKIPPED, HOST); continue }
            if (!dryRun && UpdateProgress.cancelRequested) { out += Outcome(app, SKIPPED, "cancelled"); continue }
            if (!dryRun) reapLanded(ctx, app)
            if (!online) {
                out += if (actionableFor(ctx, app) != null) Outcome(app, CACHED, "already cached")
                       else Outcome(app, NEEDS_DOWNLOAD, "offline — not fetched")
                continue
            }
            val remote = Fleet.status(ctx, app)
            out += when {
                remote is Fleet.State.Error -> Outcome(app, FAILED, remote.message, failedAt = DOWNLOAD)
                remote !is Fleet.State.UpdateAvailable && remote !is Fleet.State.Missing ->
                    Outcome(app, SKIPPED, "already current")
                actionableFor(ctx, app, remote) != null -> Outcome(app, CACHED, "already cached")
                else -> {
                    val want = (remote.bytes - partialBytes(ctx, app)).coerceAtLeast(0)
                    need += want
                    when {
                        want > free -> Outcome(app, NO_ROOM, "needs ${mb(want)}, ${mb(free)} free — not downloaded")
                        dryRun -> { free -= want; Outcome(app, DOWNLOAD, "would download ${mb(want)}") }
                        else -> {
                            UpdateProgress.beginBatch("↓ ${app.label}", i + 1, todo.size)
                            val s = download(ctx, app)
                            free = room(ctx)
                            if (actionableFor(ctx, app) != null) Outcome(app, DOWNLOADED, s.text)
                            else Outcome(app, FAILED, s.text, failedAt = s.failedAt ?: DOWNLOAD)
                        }
                    }
                }
            }
        }
        if (!dryRun) UpdateProgress.endBatch()
        return Batch("downloadAll", dryRun, online, out, need, startRoom)
    }

    /**
     * Update every app that needs it. Every installable cached APK newer than
     * what is installed goes first and needs NO network; online, the rest get
     * the full [install] chain (updates for every entry, missing ones for libs
     * only — Install all takes missing apps, as [Fleet.Mode.AUTO] does).
     * Sequential: each install may raise the system sheet. [dryRun] reports
     * what would happen and changes nothing. Blocking.
     *
     * ponytail: no batch lease and no session-headroom cap, same as tapping each
     * row's Install in turn; a refused session reports as failed at install.
     */
    fun updateAll(ctx: Context, apps: List<Fleet.App>, online: Boolean = isOnline(ctx), dryRun: Boolean = false): Batch {
        val todo = apps.filter { !it.blocked }
        val out = ArrayList<Outcome>()
        if (!dryRun) UpdateProgress.beginDownload()
        for ((i, app) in todo.withIndex()) {
            if (app.pkg == ctx.packageName) { out += Outcome(app, SKIPPED, HOST); continue }
            if (!dryRun && UpdateProgress.cancelRequested) { out += Outcome(app, SKIPPED, "cancelled"); continue }
            if (!dryRun) reapLanded(ctx, app)
            val remote = if (online) Fleet.status(ctx, app) else null
            val act = actionableFor(ctx, app, remote)
            val wanted = act != null || remote is Fleet.State.UpdateAvailable ||
                (remote is Fleet.State.Missing && app.kind == "lib")
            out += when {
                !wanted -> when (remote) {
                    is Fleet.State.Error -> Outcome(app, FAILED, remote.message, failedAt = DOWNLOAD)
                    is Fleet.State.Missing -> Outcome(app, SKIPPED, "not installed — Install all takes missing apps")
                    null -> if (ApkCache.noteOf(ctx, app.pkg)?.stage == ApkCache.STAGE_DOWNLOAD ||
                                partialBytes(ctx, app) > 0 ||
                                (app.kind == "lib" && Fleet.installedId(ctx, app) == null))
                                Outcome(app, NEEDS_DOWNLOAD, "offline — no cached build to install; Download when online")
                            else Outcome(app, SKIPPED, "offline — nothing newer cached (not checked)")
                    else -> Outcome(app, SKIPPED, "already current")
                }
                dryRun -> Outcome(app, INSTALL,
                    if (act != null) "would install${act.record?.versionCode?.let { " v$it" } ?: ""} from the cache"
                    else "would download ${mb(remote?.bytes ?: 0L)} and install")
                else -> {
                    UpdateProgress.beginBatch(app.label, i + 1, todo.size)
                    val before = installedCode(ctx, app)
                    val s = install(ctx, app, remote)
                    val after = installedCode(ctx, app)
                    when {
                        after != null && after != before -> Outcome(app, INSTALLED, s.text)
                        s.failedAt != null -> Outcome(app, FAILED, s.text, failedAt = s.failedAt)
                        else -> Outcome(app, PENDING, "handed to the installer — confirm it on screen")
                    }
                }
            }
        }
        if (!dryRun) UpdateProgress.endBatch()
        return Batch("updateAll", dryRun, online, out)
    }
}
