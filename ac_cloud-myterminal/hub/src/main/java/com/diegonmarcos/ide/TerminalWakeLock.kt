package com.diegonmarcos.ide

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * #787 The partial wake lock an open terminal holds, so a shell keeps running with the screen
 * locked. This app has no terminal service: its sessions are the SSH shells [SshBackend] holds
 * open, and it calls [sync] whenever that count changes. [CloudWakeLock] (the class cld.termux
 * and cld.termux.nix run) decides; this only does the PowerManager work it asks for.
 */
object TerminalWakeLock {

    private var lock: PowerManager.WakeLock? = null
    @Volatile private var sessions = 0

    /** [count] shells are open now: take or drop the lock to match it and the user's choice. */
    @Synchronized
    fun sync(ctx: Context, count: Int = sessions) {
        sessions = count
        val app = ctx.applicationContext
        when (CloudWakeLock.STATE.update(IdePrefs.wakeLockWanted(app), count, System.currentTimeMillis())) {
            CloudWakeLock.Transition.ACQUIRE -> acquire(app)
            CloudWakeLock.Transition.RELEASE -> lock?.release().also { lock = null }
            else -> Unit
        }
    }

    @SuppressLint("WakelockTimeout", "BatteryLife")
    private fun acquire(app: Context) {
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cloud-myterminal:terminal-sessions")
            .apply { acquire() }
        // The standard exemption prompt, ONCE: a lock taken on every shell must not re-ask every time.
        if (!pm.isIgnoringBatteryOptimizations(app.packageName) && !IdePrefs.batteryExemptionAsked(app)) {
            IdePrefs.setBatteryExemptionAsked(app)
            try {
                app.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) { /* no such screen on this ROM */ }
        }
    }
}
