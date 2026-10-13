package com.diegonmarcos.superapp.extinstall

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.diegonmarcos.superapp.R

/**
 * The install progress notification, drawn from [ExtAppInstalls]' store via
 * [ExtInstallViews.notice]. Its own channel, LOW importance and silent (the
 * fleet's quiet rule, SilentChannels.quiet), setOnlyAlertOnce, and at most a
 * few updates a second ([PostThrottle]).
 */
object ExtInstallNotifier {

    const val CHANNEL = "extapp_install_progress"
    const val ACTION = "com.diegonmarcos.superapp.extinstall.ACTION"
    const val EXTRA_PKG = "pkg"
    const val EXTRA_OP = "op"
    const val EXTRA_URL = "url"
    const val EXTRA_LABEL = "label"

    private val throttle = PostThrottle(333)

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            com.diegonmarcos.superapp.core.SilentChannels.quiet(
                NotificationChannel(CHANNEL, "App installs", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Download and install progress for Cloud apps opened from SuperApp"
                    setShowBadge(false)
                }))
    }

    private fun id(pkg: String) = 0x4558_0000 or (pkg.hashCode() and 0xFFFF)

    fun post(ctx: Context, pkg: String) {
        val s = ExtAppInstalls.snapshot(pkg) ?: return
        if (!throttle.allow(pkg, s.phase, System.currentTimeMillis())) return
        val n = ExtInstallViews.notice(s)
        runCatching {
            ensureChannel(ctx)
            val b = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(n.title)
                .setContentText(n.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(n.text))
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(n.ongoing)
                .setAutoCancel(!n.ongoing)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            if (n.showProgress) b.setProgress(n.max, n.progress, n.indeterminate)
            for (a in n.actions) {
                val label = when (a) {
                    ExtInstallViews.Action.CANCEL -> "Cancel"
                    ExtInstallViews.Action.RETRY -> "Retry"
                    ExtInstallViews.Action.RESTART -> "Restart"
                    ExtInstallViews.Action.OPEN -> "Open"
                }
                b.addAction(0, label, pending(ctx, pkg, a))
            }
            if (n.actions.contains(ExtInstallViews.Action.OPEN)) b.setContentIntent(pending(ctx, pkg, ExtInstallViews.Action.OPEN))
            NotificationManagerCompat.from(ctx).notify(id(pkg), b.build())
        }
    }

    private fun pending(ctx: Context, pkg: String, a: ExtInstallViews.Action): PendingIntent =
        PendingIntent.getBroadcast(ctx, (pkg.hashCode() * 31) + a.ordinal,
            Intent(ctx, ExtInstallActionReceiver::class.java).setAction(ACTION)
                .putExtra(EXTRA_PKG, pkg).putExtra(EXTRA_OP, a.name)
                // Carried so Retry still works after the process (and the in-memory row) is gone.
                .putExtra(EXTRA_URL, ExtAppInstalls.tracker.urlOf(pkg).orEmpty())
                .putExtra(EXTRA_LABEL, ExtAppInstalls.tracker.labelOf(pkg).orEmpty()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

/** Cancel / Retry / Restart / Open from the notification. */
class ExtInstallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(ExtInstallNotifier.EXTRA_PKG) ?: return
        ExtAppInstalls.init(context)
        when (intent.getStringExtra(ExtInstallNotifier.EXTRA_OP)) {
            ExtInstallViews.Action.CANCEL.name -> ExtAppInstalls.cancel(context, pkg)
            ExtInstallViews.Action.RETRY.name, ExtInstallViews.Action.RESTART.name -> {
                val url = intent.getStringExtra(ExtInstallNotifier.EXTRA_URL).orEmpty()
                if (ExtAppInstalls.tracker.tracked(pkg)) ExtAppInstalls.restart(context, pkg)
                else if (url.isNotBlank()) ExtAppInstalls.start(context, pkg,
                    intent.getStringExtra(ExtInstallNotifier.EXTRA_LABEL).orEmpty().ifEmpty { pkg }, url)
            }
            ExtInstallViews.Action.OPEN.name -> ExtAppInstalls.launch(context, pkg)
        }
    }
}
