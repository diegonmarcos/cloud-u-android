package com.diegonmarcos.superapp.updater

import com.diegonmarcos.superapp.updater.apk.VerifiedApk
import com.diegonmarcos.superapp.updater.install.UpdateInstaller
import com.diegonmarcos.superapp.updater.source.Download
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * WorkManager job: download a plain-HTTPS APK (a direct asset URL — e.g. a
 * GitHub release attachment) and hand it to [UpdateInstaller] for a FOREIGN
 * package. This is how the launcher installs companion apps (Cloud-Comms /
 * Cloud-IDE hubs) on first tile tap.
 *
 * Distinct from [UpdateWorker]: that one talks to GHCR's OCI API to self-update
 * Cloud-SuperApp by digest. Here the URL + target package are passed in as
 * input data (sourced from build.json::ui.external_apps), there is no manifest
 * step, and the install targets another applicationId. Progress is published on
 * the shared [UpdateProgress] bus so the existing overlay/notification surface
 * reflects the download with no extra UI.
 */
class ApkInstallWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url   = inputData.getString(KEY_URL)
        val pkg   = inputData.getString(KEY_PKG)
        val label = inputData.getString(KEY_LABEL) ?: pkg ?: "app"
        if (url.isNullOrBlank() || pkg.isNullOrBlank()) {
            Log.w(TAG, "missing url/pkg input — url=$url pkg=$pkg")
            return@withContext Result.failure()
        }
        try {
            val apk = File(applicationContext.cacheDir, "companion-$pkg.apk")
            UpdateProgress.update(UpdateProgress.State.Downloading(0, 0, -1))
            var declared = 0L
            // The private copy of this loop is gone. It was the only one of the
            // three that chased redirects by hand and the only one that could
            // not resume; [Download] now does both for every caller, so a
            // companion APK survives the same interruptions a fleet APK does.
            Download.toFile(
                url = url,
                target = apk,
                shouldCancel = { isStopped },
            ) { bytes, total ->
                if (total > declared) declared = total
                val pct = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else 0
                UpdateProgress.update(UpdateProgress.State.Downloading(pct, bytes, total))
            }
            // A companion APK comes from a plain URL, so there is no digest to
            // check it against — take the strongest evidence available: the
            // declared length when the server gave one, otherwise structure
            // alone. Either beats handing the installer an unexamined file,
            // which is how a truncated download became
            // "INSTALL_PARSE_FAILED_NOT_APK" rather than "the download stopped".
            val verified = VerifiedApk.bySize(apk, declared)
                ?: VerifiedApk.structural(apk)
                ?: error("companion APK for $pkg failed verification (${apk.length()} B)")
            Log.i(TAG, "downloaded $label (${apk.length()} bytes, ${verified.evidence}) → installing $pkg")
            // install() flips UpdateProgress to Installing and commits the
            // PackageInstaller session. Do NOT force Done here — that races the
            // async session and would overwrite the "Installing…" overlay before
            // the system installer even appears. The terminal state (Done /
            // Failed) is driven by PackageInstallerReceiver from the real
            // PackageInstaller callback, so the overlay tracks the actual
            // install lifecycle instead of flickering straight to "Done".
            UpdateInstaller(applicationContext).install(verified, pkg)
            Result.success()
        } catch (c: java.util.concurrent.CancellationException) {
            // User hit Cancel: cancelNow() already flipped state to Cancelled.
            // Don't clobber it with Failed. Worker is cancelled → success is moot.
            Log.i(TAG, "install of $pkg cancelled by user")
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "install of $pkg failed: ${t.message}", t)
            UpdateProgress.update(UpdateProgress.State.Failed(t.message ?: t.toString()))
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "Updater/ApkInstall"
        const val KEY_URL = "url"
        const val KEY_PKG = "pkg"
        const val KEY_LABEL = "label"
    }
}
