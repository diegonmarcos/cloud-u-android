package com.diegonmarcos.superapp.core
import android.content.Context
/** #894 Store, install and update notifications are Cloud Store's alone: every poster in libs:appstore / libs:updater
 *  asks [mayPost] with the context it posts from, and the PACKAGE decides. [isStoreAlert]: FleetAlerts keys of those alerts.
 *  Guards: StoreNotifyGateTest, ac_cloud-store/test/test-store-notify-gate.sh. */
object StoreNotifyGate {
    const val SUPERAPP_PKG = "com.diegonmarcos.superapp"
    const val STORE_PKG = "com.diegonmarcos.cloudstore"
    private val STORE_ALERT_KEYS = listOf("store:", "updater:", "self-update")
    fun mayPost(ctx: Context): Boolean = mayPost(ctx.packageName)
    fun mayPost(packageName: String): Boolean = packageName != SUPERAPP_PKG
    fun isStoreAlert(dedupeKey: String): Boolean = STORE_ALERT_KEYS.any { dedupeKey.startsWith(it) }
}
