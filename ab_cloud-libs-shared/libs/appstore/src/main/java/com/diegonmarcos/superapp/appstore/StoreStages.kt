package com.diegonmarcos.superapp.appstore

import android.content.Context
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
 *  - Install hands the CACHED file to the existing installer ladder
 *    ([FleetInstall.install] → shell `pm install`, else a PackageInstaller
 *    session) and never touches the network unless the file has no record to
 *    verify against. A cancel or failure leaves the file; Install again reuses it.
 *  - Clear deletes the app's cached APK(s) — on demand, or by the auto chain
 *    once [ApkCache.landed] proves the install.
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
        val note = ApkCache.noteOf(ctx, app.pkg)
        val e = cachedFor(ctx, app)
        if (e != null) {
            val ver = e.record?.versionCode?.let { " v$it" } ?: ""
            if (ApkCache.landed(ctx, e, app.pkg))
                return Stage("installed", "installed$ver · cached APK ${mb(e.bytes)} — Clear frees it",
                    listOf(CLEAR), e)
            val why = note?.takeIf { it.stage == ApkCache.STAGE_INSTALL }?.message
            return if (why != null)
                Stage("error", "cached$ver ${mb(e.bytes)} · install did not finish: $why — Install retries " +
                    "from the cache", listOf(INSTALL, CLEAR), e, failedAt = INSTALL)
            else Stage("cached", "cached$ver ${mb(e.bytes)} (ready to install)", listOf(INSTALL, CLEAR), e)
        }
        val part = partialBytes(ctx, app)
        val partNote = if (part > 0) " · ${mb(part)} kept, Download resumes" else ""
        if (note?.stage == ApkCache.STAGE_DOWNLOAD)
            return Stage("error", "download failed: ${note.message}$partNote", listOf(DOWNLOAD),
                failedAt = DOWNLOAD)
        return when (remote) {
            is Fleet.State.UpdateAvailable ->
                Stage("update_available", "update available · ${mb(remote.bytes)}$partNote", listOf(DOWNLOAD))
            is Fleet.State.Missing ->
                Stage("not_installed", "not installed · ${mb(remote.bytes)}$partNote", listOf(DOWNLOAD))
            is Fleet.State.Installed -> Stage("installed", "installed ${remote.versionName}", emptyList())
            is Fleet.State.Blocked -> Stage("blocked", "not published", emptyList())
            is Fleet.State.Error -> Stage("error", remote.message, listOf(DOWNLOAD), failedAt = DOWNLOAD)
            null -> when {
                part > 0 -> Stage("update_available", "download interrupted$partNote", listOf(DOWNLOAD))
                Fleet.installedId(ctx, app) != null ->
                    Stage("installed", "installed · not checked for an update", emptyList())
                else -> Stage("unknown", "not checked yet", listOf(DOWNLOAD))
            }
        }
    }

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

    /** Stage 2, from the cache only. Blocking. Nothing cached → says so and
     *  offers Download; never fetches behind the user's back. */
    fun install(ctx: Context, app: Fleet.App): Stage {
        val e = cachedFor(ctx, app)
            ?: return Stage("error", "nothing cached — Download first", listOf(DOWNLOAD), failedAt = INSTALL)
        if (busy.putIfAbsent(app.pkg, INSTALL) != null) return stage(ctx, app)
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
                return stage(ctx, app)
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
        return stage(ctx, app)
    }

    /** Stage 3. Deletes this app's cached APK(s) and any partial. On demand it
     *  is the user's call; the auto chain only calls it after [ApkCache.landed]. */
    fun clear(ctx: Context, app: Fleet.App): Stage {
        ApkCache.entries(ctx).filter { e ->
            e.record?.pkg?.let { it in pkgs(app) } ?: e.file.name.startsWith("fleet-${app.id}-")
        }.forEach { ApkCache.drop(it.file) }
        ApkCache.clearNote(ctx, app.pkg)
        return stage(ctx, app)
    }

    /**
     * The auto chain: Download → Install → Clear, stopping at the FIRST stage
     * that does not complete and leaving the row on it — so the user takes over
     * from exactly there (Install from the cache after a cancelled sheet,
     * Download to resume after a dropped link), never from zero.
     */
    fun auto(ctx: Context, app: Fleet.App): Stage {
        if (cachedFor(ctx, app) == null) {
            download(ctx, app)
            if (cachedFor(ctx, app) == null) return stage(ctx, app)
        }
        install(ctx, app)
        val e = cachedFor(ctx, app) ?: return stage(ctx, app)   // the receiver already reaped it
        if (!ApkCache.landed(ctx, e, app.pkg)) return stage(ctx, app)
        return clear(ctx, app)
    }
}
