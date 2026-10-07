package com.diegonmarcos.cloudstore.shell

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.diegonmarcos.superapp.adbdebug.AdbPairingService
import com.diegonmarcos.superapp.adbdebug.EmbeddedAdbChannel
import com.diegonmarcos.superapp.adbdebug.RishBridge

/**
 * #894 Cloud Store's OWN privileged shell channel.
 *
 * The owner's decision (2026-10-07): the Store handles the fleet's installs and its own
 * self-update, not SuperApp, so it needs its own uid-2000 channel. The engine is the same
 * one SuperApp runs, shared by reference (libs:shizuku-adb-debug-tools): the embedded adb
 * client pairs with the phone's own Wireless debugging once, then reconnects over mDNS with
 * the stored keys; the stock Shizuku app is the declared fallback
 * (build.json::shizuku_client). Fleet.commit then installs through `pm install` as the
 * shell user with no prompt, and falls back to the PackageInstaller session only when no
 * channel is up.
 *
 * This object does the three things the engine leaves to its host: re-arm after a reboot,
 * keep Wireless debugging on once it may, and finish a pairing.
 */
object CloudStoreShell {
    private const val TAG = "CloudStoreShell"
    private const val WORK = "cloudstore-shell-channel"
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

    /** Called once from [com.diegonmarcos.cloudstore.App.onCreate]. */
    fun install(app: Context) {
        // The Shizuku-style pairing service ends with the channel up; what follows is ours.
        AdbPairingService.onConnected = { c -> onConnected(c.applicationContext) }
        runCatching { RishBridge.requestShizukuPermissionIfNeeded() }
        schedule(app, replace = false)
    }

    /** Re-arm the channel in the background: unique work, so a repeat call never stacks. */
    fun schedule(ctx: Context, replace: Boolean) {
        runCatching {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                WORK,
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ShellChannelWorker>().build())
        }.onFailure { Log.w(TAG, "channel work not scheduled", it) }
    }

    /**
     * First connect right after the one-time pairing. Grants this app WRITE_SECURE_SETTINGS
     * through the channel it just got, which is what lets every later boot turn Wireless
     * debugging back on without the user (the platform clears it on reboot).
     */
    private fun onConnected(ctx: Context) {
        Thread({
            val out = EmbeddedAdbChannel.exec(ctx,
                "pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS 2>&1")
            Log.i(TAG, "grant WRITE_SECURE_SETTINGS -> ${out?.trim()?.take(160) ?: "no output"}")
        }, "cloudstore-shell-grant").start()
    }

    /** Flip `adb_wifi_enabled` back on when this app may. True when it is (now) on. */
    fun keepWirelessDebuggingOn(ctx: Context): Boolean {
        if (ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) !=
            PackageManager.PERMISSION_GRANTED) return false
        return runCatching {
            if (Settings.Global.getInt(ctx.contentResolver, ADB_WIFI_ENABLED, 0) != 1)
                Settings.Global.putInt(ctx.contentResolver, ADB_WIFI_ENABLED, 1)
            true
        }.getOrDefault(false)
    }

    /** Backoff reconnect: adbd and its mDNS advert take a while after boot. */
    internal fun reconnect(ctx: Context, attempts: Int): Pair<Boolean, String> {
        keepWirelessDebuggingOn(ctx)
        var last = ""
        for (n in 1..attempts) {
            val (ok, msg) = EmbeddedAdbChannel.autoConnect(ctx)
            last = msg
            if (ok) { Log.i(TAG, "channel up on attempt $n: $msg"); return true to msg }
            Log.i(TAG, "attempt $n/$attempts: $msg")
            if (n < attempts) Thread.sleep(minOf(5_000L * n, 20_000L))
        }
        return false to last
    }
}

/** Re-arms the channel off the main thread: boot, app start, and after a pairing. */
class ShellChannelWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val (ok, msg) = CloudStoreShell.reconnect(applicationContext, ATTEMPTS)
        if (!ok) Log.i("CloudStoreShell", "no channel ($msg) - installs use the PackageInstaller prompt until paired")
        return Result.success()
    }
    private companion object { const val ATTEMPTS = 6 }
}

/** BOOT_COMPLETED: the embedded client's connection is process state, so it is gone after a reboot. */
class ShellBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED)
            CloudStoreShell.schedule(context.applicationContext, replace = true)
    }
}
