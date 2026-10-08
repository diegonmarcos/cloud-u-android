package com.diegonmarcos.superapp.notificationcenter

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.diegonmarcos.superapp.appstore.ConstellationWorker
import com.diegonmarcos.superapp.core.StoreNotifyGate
import com.diegonmarcos.superapp.updater.BatchForeground
import com.diegonmarcos.superapp.updater.BatchForegroundService
import com.diegonmarcos.superapp.updater.PackageInstallerReceiver
import com.diegonmarcos.superapp.updater.Updater

/**
 * #894 The SuperApp posts no Store, install or update notification: Cloud Store
 * (ac_cloud-store) is the only app that does.
 *
 * Holding that going forward is [StoreNotifyGate]'s job (every poster asks it).
 * This is the other half: what an OLDER SuperApp build left behind survives the
 * update, because WorkManager keeps periodic work across app updates and the
 * shade keeps what was posted. So on every start:
 *
 *  - the self-update job, its kick and its manual one-shot, and the fleet
 *    check, its launch one-shot and its Wi-Fi kick, are cancelled;
 *  - the Store badge, the batch-download, "tap to finish installing" and
 *    store-alert notifications are cancelled, and their channels deleted, so
 *    the user's Settings ▸ Notifications no longer lists them;
 *  - Store / update alerts already collected are dropped from the Alerts group.
 *
 * Idempotent and cheap; nothing here posts. Called from App.onCreate.
 */
object StoreRetirement {
    private const val TAG = "StoreRetirement"

    /** The retired #812 Store badge: its channel id and notification id (ids are the user's saved settings). */
    const val BADGE_CHANNEL = "store_updates"
    const val BADGE_NOTIFICATION_ID = 0x5A0E

    /** Channels this app no longer owns. */
    val RETIRED_CHANNELS = listOf(BADGE_CHANNEL, BatchForeground.CHANNEL, PackageInstallerReceiver.UPDATER_CHANNEL)

    /** Notification ids (untagged) this app no longer posts. */
    val RETIRED_IDS = listOf(BADGE_NOTIFICATION_ID, BatchForeground.ID, PackageInstallerReceiver.UPDATER_NOTIF_ID + 1)

    fun run(ctx: Context) {
        val app = ctx.applicationContext
        if (StoreNotifyGate.mayPost(app)) return   // Cloud Store (or another fleet app) owns its own
        runCatching { ConstellationWorker.cancelAll(app) }.onFailure { Log.w(TAG, "fleet work: ${it.message}") }
        runCatching { Updater.cancelAll(app) }.onFailure { Log.w(TAG, "update work: ${it.message}") }
        runCatching { app.stopService(Intent(app, BatchForegroundService::class.java)) }
        runCatching {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            RETIRED_IDS.forEach { nm.cancel(it) }
            // A store alert the fleet-alerts fallback posted from here, tagged by its dedupe key.
            nm.activeNotifications.filter { n -> n.tag?.let(StoreNotifyGate::isStoreAlert) == true }
                .forEach { nm.cancel(it.tag, it.id) }
            if (Build.VERSION.SDK_INT >= 26) RETIRED_CHANNELS.forEach { nm.deleteNotificationChannel(it) }
        }.onFailure { Log.w(TAG, "notifications: ${it.message}") }
        runCatching { if (AlertStore.dropStoreAlerts(app) > 0) AlertsNotifier.refresh(app) }
    }
}
