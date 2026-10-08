package com.diegonmarcos.superapp.core

import android.content.Context

/**
 * #894 Store, install and update notifications belong to Cloud Store (ac_cloud-store) alone.
 *
 * libs:appstore, libs:updater and the fleet pass are compiled into the SuperApp as well as into
 * Cloud Store, so the code that posts "downloading 3 of 12", "update available", "tap to finish
 * installing" and the pass summary is in both APKs. Standing the SuperApp's schedules down was not
 * enough (a persisted worker, a second entry point, or another app's alert delivered INTO the
 * SuperApp's Alerts group each still posted), so the rule is held where the posting happens:
 * every Store/update post asks [mayPost] with the context it would post from, and the answer is
 * decided by the PACKAGE of that context, not by anything the caller can configure.
 *
 * Other fleet apps keep their own self-update result notices (their own package, their own
 * notification); only the SuperApp package is closed, and [isStoreAlert] keeps those apps'
 * Store-ish alerts from being collected INTO the SuperApp.
 *
 * guard: ac_cloud-store/test/test-store-notify-gate.sh (static, mutation-checked) and
 * aa_cloud-superapp StoreNotifyGateTest (JVM).
 */
object StoreNotifyGate {
    const val SUPERAPP_PKG = "com.diegonmarcos.superapp"
    const val STORE_PKG = "com.diegonmarcos.cloudstore"

    /** FleetAlerts dedupe keys that are Store / update alerts: the fleet pass summary, the
     *  updater's install results, an app's own self-update result. */
    private val STORE_ALERT_KEYS = listOf("store:", "updater:", "self-update")

    /** May a Store / install / update notification be posted from [ctx]? Never from the SuperApp. */
    fun mayPost(ctx: Context): Boolean = mayPost(ctx.packageName)

    fun mayPost(packageName: String): Boolean = packageName != SUPERAPP_PKG

    /** Is [dedupeKey] one of the Store / update alert keys? */
    fun isStoreAlert(dedupeKey: String): Boolean = STORE_ALERT_KEYS.any { dedupeKey.startsWith(it) }
}
