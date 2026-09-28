package com.diegonmarcos.superapp.appstore

import android.content.Context
import com.diegonmarcos.superapp.updater.Advisory
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.apk.VerifiedApk

/**
 * The ONE per-app install/update both Store tabs run (#564): Cloud's
 * "Install / Update" and Phone Apps' Update. Lifted out of StoreCloudFragment
 * so the Phone tab reuses the fleet path (#88/#496/#274) instead of growing a
 * second updater.
 *
 * #625 SPLIT IN TWO, ON PURPOSE: [stage] downloads, [install] installs, and
 * [run] is the two of them for a single row. [BatchInstall] runs every [stage]
 * first and every [install] afterwards, which is the ordering Diego asked for —
 * fetch it all, cache it, then install one by one. The split lives here rather
 * than in the batch so the single-row path and the batch path cannot drift into
 * two downloaders again.
 *
 * Blocking; call off the main thread. [run] returns the message to surface, or
 * null when there is nothing to report (success, or the user's own cancel).
 */
object FleetInstall {

    /** Phase 1: fetch and verify, install nothing. Throws with the reason —
     *  [Fleet.download] has already published it to [UpdateProgress]. */
    fun stage(ctx: Context, app: Fleet.App): VerifiedApk = Fleet.download(ctx, app)

    /** Phase 2: hand already-downloaded, already-verified bytes to the
     *  installer. Returns the message to surface, or null. */
    fun install(ctx: Context, app: Fleet.App, apk: VerifiedApk): String? =
        try {
            // ONLY AN OBSERVED INSTALL CLEARS THE ADVISORY.
            //
            // Success CLEARS the advisory: a warning that outlives the
            // problem is noise, and noise is how the next real one is
            // ignored. But a PackageInstaller commit returns as soon as a
            // channel ACCEPTED the APK — the actual outcome lands minutes
            // later at PackageInstallerReceiver. Clearing here regardless
            // meant a commit that went on to fail wiped the very record that
            // was meant to survive it, so three consecutive failures could
            // never accumulate into the "use Direct" banner they exist to
            // raise. [Fleet.observesOutcome] is whether the channel WATCHED
            // the install finish, and only that clears anything.
            if (Fleet.observesOutcome(Fleet.commit(ctx, app, apk))) Advisory.recordSuccess(ctx, app.id)
            null
        } catch (c: java.util.concurrent.CancellationException) {
            UpdateProgress.update(UpdateProgress.State.Cancelled)
            null
        } catch (t: Throwable) {
            // The Toast used to be the ONLY record of this, and it said
            // "no install channel accepted <pkg>" — a permanent dead end
            // phrased as a transient error, gone in four seconds, with no
            // way out offered. It is still shown, because the reason is
            // worth showing, but it now also feeds the advisory so a third
            // consecutive failure raises the banner and the notification
            // that point at Direct install.
            // A user cancel is not a failure. Now that the store offers a
            // Cancel button, counting cancels here would let three of them
            // raise the "install is broken — use Direct" advisory, which
            // would be the app telling the user their own choice was a
            // malfunction.
            if (UpdateProgress.cancelRequested) null
            else (t.message ?: "install failed").also {
                UpdateProgress.update(UpdateProgress.State.Failed(it, appId = app.id, pkg = app.pkg,
                    apkPath = apk.file.absolutePath))
                Advisory.recordFailure(ctx, app.id, app.label, it)
            }
        }

    fun run(ctx: Context, app: Fleet.App): String? {
        UpdateProgress.beginDownload()
        val apk = try {
            stage(ctx, app)
        } catch (c: java.util.concurrent.CancellationException) {
            UpdateProgress.update(UpdateProgress.State.Cancelled)
            return null
        } catch (t: Throwable) {
            if (UpdateProgress.cancelRequested) return null
            return (t.message ?: "download failed").also {
                Advisory.recordFailure(ctx, app.id, app.label, it)
            }
        }
        return install(ctx, app, apk)
    }
}
