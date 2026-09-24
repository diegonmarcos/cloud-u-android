package com.diegonmarcos.superapp.appstore

import android.content.Context
import com.diegonmarcos.superapp.updater.Advisory
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.UpdateProgress

/**
 * The ONE per-app install/update both Store tabs run (#564): Cloud's
 * "Install / Update" and Phone Apps' Update. Lifted out of StoreCloudFragment
 * so the Phone tab reuses the fleet path (#88/#496/#274) instead of growing a
 * second updater.
 *
 * Blocking; call off the main thread. Returns the message to surface, or null
 * when there is nothing to report (success, or the user's own cancel).
 */
object FleetInstall {
    fun run(ctx: Context, app: Fleet.App): String? {
        UpdateProgress.beginDownload()
        return try {
            // ONLY AN OBSERVED INSTALL CLEARS THE ADVISORY.
            //
            // Success CLEARS the advisory: a warning that outlives the
            // problem is noise, and noise is how the next real one is
            // ignored. But [Fleet.install] returns as soon as a channel
            // ACCEPTED the APK, and for the PackageInstaller channel
            // "accepted" only means a session was handed to the system —
            // the actual outcome lands minutes later at
            // PackageInstallerReceiver. Clearing here regardless meant a
            // commit that went on to fail wiped the very record that was
            // meant to survive it, so three consecutive failures could
            // never accumulate into the "use Direct" banner they exist to
            // raise. Now it returns whether the channel WATCHED the install
            // finish, and only that clears anything.
            if (Fleet.install(ctx, app)) Advisory.recordSuccess(ctx, app.id)
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
            else (t.message ?: "install failed").also { Advisory.recordFailure(ctx, app.id, app.label, it) }
        }
    }
}
