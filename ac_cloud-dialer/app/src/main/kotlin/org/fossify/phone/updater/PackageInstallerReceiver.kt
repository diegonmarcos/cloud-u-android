package org.fossify.phone.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.diegonmarcos.superapp.core.FleetAlerts

/**
 * Receives the [PackageInstaller] session callbacks for the in-app updater and
 * surfaces them as a fleet alert (#777 FleetAlerts) + [Toast] — no dependency on any
 * NotificationStore (which Fossify Phone does not have).
 *
 * The three things that matter:
 *  - STATUS_PENDING_USER_ACTION: launch the system confirm dialog.
 *  - STATUS_SUCCESS: tell the user the update is installed.
 *  - STATUS_FAILURE_*: explain what went wrong (CONFLICT gets a specific hint).
 */
internal class PackageInstallerReceiver : BroadcastReceiver() {
    private val tag = "Updater/InstallRx"

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.d(tag, "PackageInstaller status=$status message=$message")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirm)
                    } catch (t: Throwable) {
                        Log.e(tag, "Failed to start confirm intent", t)
                        notify(context, "$APP_LABEL update", "Open $APP_LABEL to finish installing.", FleetAlerts.WARN)
                    }
                } else {
                    Log.e(tag, "STATUS_PENDING_USER_ACTION with no confirm intent")
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                UpdateProgress.update(UpdateProgress.State.Done)
                announce(context, "$APP_LABEL updated", "The latest version is installed.", FleetAlerts.INFO)
            }

            else -> {
                val reason = failureReason(status, message)
                UpdateProgress.update(UpdateProgress.State.Failed(reason))
                announce(context, "$APP_LABEL update failed", reason, FleetAlerts.ERROR)
            }
        }
    }

    private fun failureReason(status: Int, message: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED ->
            "Update cancelled."
        PackageInstaller.STATUS_FAILURE_BLOCKED ->
            "The install was blocked by the device. Allow installs from $APP_LABEL and retry."
        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "A conflicting copy is already installed. Uninstall the previous $APP_LABEL once, then update again."
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
            "This build is not compatible with your device."
        PackageInstaller.STATUS_FAILURE_INVALID ->
            "The downloaded package is invalid or corrupt."
        PackageInstaller.STATUS_FAILURE_STORAGE ->
            "Not enough storage to install the update."
        else ->
            message?.takeIf { it.isNotBlank() } ?: "The update could not be installed."
    }

    private fun announce(context: Context, title: String, body: String, severity: String) {
        notify(context, title, body, severity)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, body, Toast.LENGTH_LONG).show()
        }
    }

    /** #777: an update result is a fleet alert — it lands in the SuperApp's
     *  Alerts group (this app's own notification only if there is no SuperApp),
     *  and the next result replaces it. */
    private fun notify(context: Context, title: String, body: String, severity: String) {
        FleetAlerts.raise(context, FleetAlerts.Alert(
            title = title, text = body, severity = severity, dedupeKey = "self-update"))
    }

    companion object {
        private const val APP_LABEL = "Cloud Dialer"
    }
}
