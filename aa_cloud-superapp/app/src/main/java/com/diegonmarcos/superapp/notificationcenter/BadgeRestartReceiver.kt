package com.diegonmarcos.superapp.notificationcenter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * #515 — the receiver that had no equivalent anywhere in this manifest.
 *
 * Android kills every service belonging to a package when that package is
 * replaced, and it does NOT deliver BOOT_COMPLETED for an update. The only
 * BOOT_COMPLETED receiver this app had was `PrivilegedPlaneBootReceiver`,
 * which starts nothing badge-related, and there was no ACTION_MY_PACKAGE_REPLACED
 * receiver at all. So an app update was a silent badge wipe: every persistent
 * badge whose service was not started from `App.onCreate` stayed gone until
 * the owner happened to open the app shell or toggle the overlay by hand.
 *
 * Both actions are handled here because they are the same event as far as a
 * badge is concerned — "the services that were holding my badges are gone".
 * MY_PACKAGE_REPLACED and BOOT_COMPLETED are both on the platform's allowlist
 * for starting a foreground service from the background, so [ensureAll] is a
 * legal call from here.
 */
class BadgeRestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        val action = intent?.action ?: return
        if (action !in HANDLED) return
        val started = BadgeServices.ensureAll(ctx)
        Log.i(TAG, "$action → ensured ${started.size} service(s): $started")
    }

    companion object {
        private const val TAG = "BadgeRestartReceiver"

        /** Kept as a set so the manifest and the code cannot drift into each
         *  other's blind spot without this failing the intent filter test. */
        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
