package com.diegonmarcos.superapp.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * #777 — THE way a fleet app tells the user something: one call, and the alert
 * lands in the SuperApp's Alerts group (G4 of Configs ▸ Launcher ▸ Notify)
 * instead of in a channel of its own.
 *
 *     FleetAlerts.raise(ctx, FleetAlerts.Alert(
 *         title = "Backup failed", text = "3 files could not be uploaded",
 *         severity = FleetAlerts.ERROR, dedupeKey = "backup"))
 *
 * Before this every app that had something to say opened its own channel —
 * Store "updates installed", the updater's install results, Media Center's
 * backup result, the camera's save error — so the user's alerts were spread
 * over a dozen channels in a dozen apps, and the one place meant to collect
 * them (the old "Alerts" badge) showed sample data.
 *
 * DELIVERY is a `ContentProvider.call` on the SuperApp's [AUTHORITY]:
 *  - a provider call STARTS the SuperApp's process when it is not running
 *    (a broadcast would not reach a stopped app, and a bound service would
 *    need the caller to wait for a connection);
 *  - it is SYNCHRONOUS, so the caller knows whether the alert was taken and
 *    can fall back honestly instead of guessing;
 *  - the SuperApp declares it with `android:permission=CONSTELLATION_DATA`,
 *    the fleet's signature permission (declared in this lib's manifest), so
 *    only an APK signed with the fleet key can raise one;
 *  - the provider reads WHO raised it from `getCallingPackage()`, never from
 *    the bundle, so one fleet app cannot raise an alert as another.
 *
 * FALLBACK: no SuperApp installed (or it refused) → the alert is posted as this
 * app's own notification on [FALLBACK_CHANNEL]. An alert is never dropped just
 * because its collector is missing.
 *
 * [dedupeKey]: raising again with the same key REPLACES the earlier alert of
 * the same app (one "2 updates available" line, not one per pass); [withdraw]
 * removes it once it no longer holds.
 *
 * test: aa_cloud-superapp FleetAlertsTest; CI guard: fleet-alerts-guard.yml.
 */
object FleetAlerts {

    private const val TAG = "FleetAlerts"

    /** The SuperApp's collector. A constant, not `${applicationId}`: callers
     *  are OTHER apps, which have to name the SuperApp's authority. */
    const val AUTHORITY = "com.diegonmarcos.superapp.fleetalerts"

    const val METHOD_RAISE = "raise"
    const val METHOD_WITHDRAW = "withdraw"

    const val KEY_TITLE = "title"
    const val KEY_TEXT = "text"
    const val KEY_SEVERITY = "severity"
    const val KEY_DEEP_LINK = "deep_link"
    const val KEY_DEDUPE = "dedupe_key"
    const val KEY_OK = "ok"

    /** Same vocabulary as [NotificationStore.Sev] — one set of words. */
    const val INFO = NotificationStore.Sev.INFO
    const val WARN = NotificationStore.Sev.WARN
    const val ERROR = NotificationStore.Sev.ERROR
    val SEVERITIES = listOf(INFO, WARN, ERROR)

    /** This app's own channel, used ONLY when the SuperApp cannot take it. */
    const val FALLBACK_CHANNEL = "fleet_alerts"

    /**
     * @param deepLink where a tap goes. `page:` / `section:` / `action:` is
     *   the SuperApp's own target grammar; `intent:` is an Intent URI; any
     *   other URI is opened with ACTION_VIEW. Blank = open the raising app.
     * @param dedupeKey same key from the same app = replace, not add.
     */
    data class Alert(
        val title: String,
        val text: String = "",
        val severity: String = INFO,
        val deepLink: String = "",
        val dedupeKey: String = "",
    ) {
        fun toBundle() = Bundle().apply {
            putString(KEY_TITLE, title)
            putString(KEY_TEXT, text)
            putString(KEY_SEVERITY, severity)
            putString(KEY_DEEP_LINK, deepLink)
            putString(KEY_DEDUPE, dedupeKey)
        }

        companion object {
            /** Severity outside the vocabulary reads as INFO rather than
             *  being dropped: a typo must not lose the alert. */
            fun fromBundle(b: Bundle) = Alert(
                title = b.getString(KEY_TITLE).orEmpty(),
                text = b.getString(KEY_TEXT).orEmpty(),
                severity = b.getString(KEY_SEVERITY).takeIf { it in SEVERITIES } ?: INFO,
                deepLink = b.getString(KEY_DEEP_LINK).orEmpty(),
                dedupeKey = b.getString(KEY_DEDUPE).orEmpty(),
            )
        }
    }

    enum class Delivery { SUPERAPP, LOCAL, DROPPED }

    /** Raise [alert]. Never throws; says where it went. [authority] is
     *  there for the test that proves the fallback; callers leave it. */
    fun raise(ctx: Context, alert: Alert, authority: String = AUTHORITY): Delivery {
        if (alert.title.isBlank()) return Delivery.DROPPED
        // #894 A Store / update alert stays in the app that did the work; the SuperApp drops it.
        if (StoreNotifyGate.isStoreAlert(alert.dedupeKey))
            return if (StoreNotifyGate.mayPost(ctx) && postLocally(ctx, alert)) Delivery.LOCAL else Delivery.DROPPED
        if (call(ctx, authority, METHOD_RAISE, alert.toBundle())) return Delivery.SUPERAPP
        return if (postLocally(ctx, alert)) Delivery.LOCAL else Delivery.DROPPED
    }

    /** Take back the alert this app raised under [dedupeKey]. */
    fun withdraw(ctx: Context, dedupeKey: String) {
        if (dedupeKey.isBlank()) return
        if (StoreNotifyGate.isStoreAlert(dedupeKey)) { runCatching { nm(ctx).cancel(dedupeKey, LOCAL_ID) }; return }
        if (!call(ctx, AUTHORITY, METHOD_WITHDRAW, Bundle().apply { putString(KEY_DEDUPE, dedupeKey) }))
            runCatching { nm(ctx).cancel(dedupeKey, LOCAL_ID) }
    }

    private fun call(ctx: Context, authority: String, method: String, extras: Bundle): Boolean = runCatching {
        // Unstable: a SuperApp crash mid-call must not take the caller down.
        val client = ctx.contentResolver.acquireUnstableContentProviderClient(authority)
            ?: return false // not installed, or not visible to this app
        try {
            client.call(method, null, extras)?.getBoolean(KEY_OK) == true
        } finally {
            client.close()
        }
    }.onFailure { Log.w(TAG, "$method not delivered to the SuperApp: ${it.javaClass.simpleName}") }
        .getOrDefault(false)

    private const val LOCAL_ID = 0xA1E7

    private fun nm(ctx: Context) = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** The fallback: this app's own notification, tagged by dedupe key so a
     *  repeat replaces it here exactly as it would in the SuperApp. */
    private fun postLocally(ctx: Context, a: Alert): Boolean = runCatching {
        val nm = nm(ctx)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(FALLBACK_CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(
                FALLBACK_CHANNEL, ctx.getString(R.string.fleet_alerts_channel),
                NotificationManager.IMPORTANCE_DEFAULT))
        val tag = a.dedupeKey.ifBlank { a.title }
        val pi = intentFor(ctx, ctx.packageName, a.deepLink, null)?.let {
            PendingIntent.getActivity(ctx, tag.hashCode(), it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        nm.notify(tag, LOCAL_ID, NotificationCompat.Builder(ctx, FALLBACK_CHANNEL)
            .setSmallIcon(R.drawable.core_ic_fleet_alert)
            .setContentTitle(a.title)
            .setContentText(a.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.text))
            .setAutoCancel(true)
            .apply { if (pi != null) setContentIntent(pi) }
            .build())
        true
    }.onFailure { Log.w(TAG, "local fallback failed: ${it.javaClass.simpleName}") }.getOrDefault(false)

    /**
     * What a tap on an alert opens — shared by the SuperApp and the fallback so
     * a link means the same thing wherever the alert ended up.
     *
     * [superappActivity] is the SuperApp's shell (null outside it): the
     * `page:`/`section:`/`action:` grammar is only meaningful there. A parsed
     * link is stripped of its selector and URI grants — it is about to be
     * started with the COLLECTOR's identity, not the raiser's.
     */
    fun intentFor(ctx: Context, sourcePkg: String, deepLink: String, superappActivity: Class<*>?): Intent? {
        val link = deepLink.trim()
        val open = ctx.packageManager.getLaunchIntentForPackage(sourcePkg)
        val i = when {
            link.isEmpty() -> open
            Regex("^(page|section|action):").containsMatchIn(link) ->
                superappActivity?.let { Intent(ctx, it).putExtra("shortcut_action", link) } ?: open
            link.startsWith("intent:") -> runCatching { Intent.parseUri(link, Intent.URI_INTENT_SCHEME) }.getOrNull()
            else -> runCatching { Intent(Intent.ACTION_VIEW, Uri.parse(link)) }.getOrNull()
        } ?: return open?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        i.selector = null
        i.flags = (i.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION).inv()) or
            Intent.FLAG_ACTIVITY_NEW_TASK
        return i
    }
}
