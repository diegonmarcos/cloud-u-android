package com.diegonmarcos.superapp.appstore

import android.content.Context
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.apk.VerifiedApk

/**
 * #571 — install an EXTERNAL app from this store, without any other store:
 * walk its declared ladder ([SourceResolver.External.direct]) — vendor, then
 * F-Droid — fetch from the first rung that serves a verified APK, and commit it
 * through [Fleet.commit], the fleet's own install channels. There is no second
 * installer here and no second downloader: the only new mechanism is WHERE the
 * bytes come from.
 *
 * A Play-only app never gets here with anything to do: its ladder has no direct
 * rung, [run] says so, and the row's Play button is the honest hand-off.
 *
 * #625 SPLIT IN TWO, like [FleetInstall]: [stage] walks the ladder and returns
 * the cached APK, [install] commits it, [run] is both for one row and
 * [BatchInstall] is all of the first followed by all of the second.
 *
 * Blocking; call off the main thread. [run] returns the message to surface, or
 * null when there is nothing to report (accepted by a channel, or the user's
 * cancel). Every failure is also published to [UpdateProgress], as
 * [FleetInstall] does, so the progress row never freezes on a download that
 * already died.
 */
object ExternalInstall {

    /**
     * Phase 1: the first rung that serves a verified APK for [app].
     *
     * Throws with EVERY rung's reason joined, because "could not download" on
     * its own names nothing the user can act on. Publishes the failure before
     * throwing so the progress row does not freeze on the last rung's bytes.
     */
    fun stage(ctx: Context, cfg: SourceResolver.Config, app: SourceResolver.External): VerifiedApk {
        val ladder = app.direct
        if (ladder.isEmpty()) error(nothingToFetch(ctx, app))
        val declined = mutableListOf<String>()
        for (src in ladder) {
            try {
                return SourceResolver.fetch(ctx, cfg, app, src)
            } catch (c: java.util.concurrent.CancellationException) {
                throw c
            } catch (t: Throwable) {
                // The next rung may still serve it; the reason survives the fall.
                declined += "${src.kind} → ${t.message ?: t.javaClass.simpleName}"
            }
        }
        val why = "${app.label}: " + declined.joinToString(" | ")
        UpdateProgress.update(UpdateProgress.State.Failed(why, appId = app.pkg, pkg = app.pkg))
        error(why)
    }

    /** Phase 2: commit bytes that are already cached and verified. */
    fun install(ctx: Context, app: SourceResolver.External, apk: VerifiedApk): String? = try {
        UpdateProgress.update(UpdateProgress.State.Installing)
        Fleet.commit(ctx, app.asFleetApp(), apk)
        null
    } catch (t: Throwable) {
        if (UpdateProgress.cancelRequested) null
        else (t.message ?: "install failed").also {
            UpdateProgress.update(UpdateProgress.State.Failed(it, appId = app.pkg, pkg = app.pkg,
                apkPath = apk.file.absolutePath))
        }
    }

    private fun nothingToFetch(ctx: Context, app: SourceResolver.External): String =
        app.unresolved?.let { ctx.getString(R.string.store_phone_state_unresolved, it) }
            ?: ctx.getString(R.string.store_phone_why_play_only, app.label)

    fun run(ctx: Context, cfg: SourceResolver.Config, app: SourceResolver.External): String? {
        if (app.direct.isEmpty()) return nothingToFetch(ctx, app)
        UpdateProgress.beginDownload()
        val apk = try {
            stage(ctx, cfg, app)
        } catch (c: java.util.concurrent.CancellationException) {
            UpdateProgress.update(UpdateProgress.State.Cancelled)
            return null
        } catch (t: Throwable) {
            return t.message ?: "download failed"
        }
        return install(ctx, app, apk)
    }
}
