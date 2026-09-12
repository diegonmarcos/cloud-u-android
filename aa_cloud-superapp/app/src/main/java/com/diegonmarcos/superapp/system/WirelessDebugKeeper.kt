package com.diegonmarcos.superapp.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Holds Wireless Debugging on.
 *
 * PrivilegedPlaneWorker turns `adb_wifi_enabled` on at app start and at
 * BOOT_COMPLETED, and that is the only time anything ever turns it on. The
 * platform, meanwhile, turns it off whenever it likes: AdbDebuggingManager
 * clears the setting when the network it was armed on goes away, and on this
 * phone the network moves under it without Wi-Fi ever dropping — WireGuard is
 * an always-on device VPN, so every tunnel re-establish swaps the default
 * network and reads to the framework exactly like the network vanishing. The
 * owner sees Wi-Fi steady and Wireless Debugging off, which is the report in
 * #290. Reboot is the same story with a different trigger and is already
 * handled; mid-session was not handled at all, so the setting stayed off until
 * the next launch.
 *
 * So this runs periodically and does exactly one thing: if the setting is not
 * 1, put it back. Deliberately NOT PrivilegedPlaneWorker on a timer — that one
 * carries the pairing and autoconnect loop, up to eight attempts with sleeps
 * between them, and running it every quarter hour forever would cost real
 * battery on a phone whose owner has a page dedicated to battery hogs. Turning
 * a setting back on costs a read and a write.
 *
 * WorkManager's floor for periodic work is 15 minutes, so that is the width of
 * the worst-case window where debugging is off. Closing it completely would
 * need a ContentObserver on the setting's Uri, which fires instantly but dies
 * with the process — the opposite trade. This one survives process death, which
 * is the case that was actually failing.
 */
class WirelessDebugKeeper(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        if (ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Nothing to do and nothing to say every 15 minutes; PrivilegedPlaneWorker
            // owns acquiring the permission and already reports when it cannot.
            return Result.success()
        }
        return try {
            val on = Settings.Global.getInt(ctx.contentResolver, ADB_WIFI_ENABLED, 0)
            if (on != 1) {
                Settings.Global.putInt(ctx.contentResolver, ADB_WIFI_ENABLED, 1)
                Log.i(TAG, "adb_wifi_enabled was $on — turned back on")
            }
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "re-arm failed: ${t.message}")
            Result.success()
        }
    }

    companion object {
        const val UNIQUE_NAME = "wireless-debug-keeper"
        private const val TAG = "WirelessDebugKeeper"

        // Settings.Global key; not a public constant but stable since Android 11.
        private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    }
}
