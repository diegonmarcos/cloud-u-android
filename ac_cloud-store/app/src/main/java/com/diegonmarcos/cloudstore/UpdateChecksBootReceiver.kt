package com.diegonmarcos.cloudstore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.diegonmarcos.superapp.appstore.ConstellationWorker

/**
 * #1078 After a reboot or an update of this app, re-arms the periodic fleet check. WorkManager keeps its own jobs across
 * a reboot; this is the explicit second line for a phone that dropped them. start() is idempotent (unique periodic work).
 */
class UpdateChecksBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching { ConstellationWorker.start(context.applicationContext) }
            .onFailure { Log.w("CloudStore", "fleet pass not re-armed", it) }
    }
}
