package com.diegonmarcos.superapp.appstore

import android.content.Context
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.UpdateProgress

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
 * Blocking; call off the main thread. Returns the message to surface, or null
 * when there is nothing to report (accepted by a channel, or the user's cancel).
 * Every failure is also published to [UpdateProgress], as [FleetInstall] does,
 * so the progress row never freezes on a download that already died.
 */
object ExternalInstall {

    fun run(ctx: Context, cfg: SourceResolver.Config, app: SourceResolver.External): String? {
        val ladder = app.direct
        if (ladder.isEmpty()) return ctx.getString(R.string.store_phone_why_play_only, app.label)
        UpdateProgress.beginDownload()
        val declined = mutableListOf<String>()
        for (src in ladder) {
            val apk = try {
                SourceResolver.fetch(ctx, cfg, app, src)
            } catch (c: java.util.concurrent.CancellationException) {
                UpdateProgress.update(UpdateProgress.State.Cancelled)
                return null
            } catch (t: Throwable) {
                // The next rung may still serve it; the reason survives the fall.
                declined += "${src.kind} → ${t.message ?: t.javaClass.simpleName}"
                continue
            }
            return try {
                UpdateProgress.update(UpdateProgress.State.Installing)
                Fleet.commit(ctx, app.asFleetApp(), apk)
                null
            } catch (t: Throwable) {
                if (UpdateProgress.cancelRequested) null
                else (t.message ?: "install failed").also {
                    UpdateProgress.update(UpdateProgress.State.Failed(it, appId = app.pkg, pkg = app.pkg, apkPath = apk.file.absolutePath))
                }
            }
        }
        val why = "${app.label}: " + declined.joinToString(" | ")
        UpdateProgress.update(UpdateProgress.State.Failed(why, appId = app.pkg, pkg = app.pkg))
        return why
    }
}
