package com.diegonmarcos.superapp.notificationcenter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.datamanager.DataUsageProvider
import com.diegonmarcos.superapp.network.DataBadgeModel
import com.diegonmarcos.superapp.network.DataBadgeModel.Act
import java.util.concurrent.Executors

/**
 * The persistent "Data" badge: today's and this month's data usage, mobile vs
 * Wi-Fi, and the top apps, in the shade - drawn from [DataBadgeModel].
 *
 * Every figure comes from libs:datamanager's [DataUsageProvider], the engine
 * behind Configs > About > Data Usage; nothing here queries NetworkStatsManager.
 * Usage needs the Usage Access special permission; without it the badge says so
 * and offers a Grant access button.
 *
 * NetworkStats has no change events, so a read happens on the events that make
 * a fresh number worth having: the service starting, the screen coming on, the
 * Refresh button. Coalesced to one read per [MIN_GAP_MS]. No timer, nothing runs
 * while the screen is off. Without POST_NOTIFICATIONS (Android 13+) it posts and
 * reads nothing.
 */
class DataBadgeService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "data-badge").apply { isDaemon = true } }
    private var receiver: BroadcastReceiver? = null
    private var pending: Runnable? = null
    @Volatile private var lastPostMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        startForeground(NOTIF_ID, build(DataBadgeModel.Snapshot(hasAccess = true)))
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { requestRefresh(force = false) }
        }
        receiver = r
        ContextCompat.registerReceiver(this, r, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED)
        requestRefresh(force = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        requestRefresh(force = intent?.action == ACTION_REFRESH)
        return START_STICKY
    }

    override fun onDestroy() {
        receiver?.let { runCatching { unregisterReceiver(it) } }
        pending?.let(main::removeCallbacks)
        worker.shutdownNow()
        NotifyGroups.release(this, BADGE_ID)
        super.onDestroy()
    }

    /** Coalesce a burst into one read, at most one per [MIN_GAP_MS] unless [force]d by the owner. */
    private fun requestRefresh(force: Boolean) = main.post {
        if (pending != null) return@post
        val wait = if (force) 0L else (MIN_GAP_MS - (System.currentTimeMillis() - lastPostMs)).coerceIn(0L, MIN_GAP_MS)
        val r = Runnable { pending = null; runCatching { worker.execute { refreshNow() } } }
        pending = r
        main.postDelayed(r, wait)
    }

    private fun refreshNow() {
        if (!NetworkBadgeService.canPost(this)) return
        val snap = gather()
        lastPostMs = System.currentTimeMillis()
        runCatching { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, build(snap)) }
    }

    private fun gather(): DataBadgeModel.Snapshot {
        if (!DataUsageProvider.hasUsageAccess(this)) return DataBadgeModel.Snapshot(hasAccess = false)
        val now = System.currentTimeMillis()
        fun win(t: DataUsageProvider.Totals) = DataBadgeModel.Window(mobile = t.mobile, wifi = t.wifi)
        val monthStart = DataUsageProvider.startOfMonth(now)
        val top = DataUsageProvider.perApp(this, monthStart, now).take(DataBadgeModel.TOP_APPS)
            .map { DataBadgeModel.App(it.label, it.totals.total) }
        return DataBadgeModel.Snapshot(
            hasAccess = true,
            today = win(DataUsageProvider.deviceTotals(this, DataUsageProvider.startOfToday(now), now)),
            month = win(DataUsageProvider.deviceTotals(this, monthStart, now)),
            topApps = top,
        )
    }

    private fun build(s: DataBadgeModel.Snapshot): Notification {
        val card = DataBadgeModel.card(s)
        val pinned = BadgeServices.pinned(this, BADGE_ID)
        val open = openDataManager(this)
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(0xFF0A0A0A.toInt())
            .setContentTitle(card.title)
            .setContentText(card.text)
            .setSubText("Cloud SA - Data")
            .setStyle(NotificationCompat.BigTextStyle().bigText(card.expanded))
            .setContentIntent(open)
            .setOngoing(pinned)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE) // what the owner uses stays off the lockscreen
            .apply { if (pinned) setDeleteIntent(BadgeServices.repostOnDismiss(this@DataBadgeService, NOTIF_ID)) }
        for (a in card.actions) {
            val pi = when (a.act) {
                Act.GRANT -> PendingIntent.getActivity(this, RC_GRANT,
                    Intent(DataUsageProvider.USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                Act.REFRESH -> PendingIntent.getService(this, RC_REFRESH,
                    Intent(this, DataBadgeService::class.java).setAction(ACTION_REFRESH),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                Act.OPEN -> open
            }
            b.addAction(R.drawable.ic_stat_notify, a.label, pi)
        }
        return NotifyGroups.attach(this, b, BADGE_ID).build()
            .apply { if (pinned) flags = flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT }
    }

    companion object {
        const val NOTIF_ID = 7718
        const val CHANNEL_ID = "network_data"
        /** This badge's id in build.json::ui.notification_center.producers. */
        const val BADGE_ID = "network_data"

        /** The shell's home-action id that shows the Data Manager (Configs > About > Data Usage). */
        const val OPEN_ACTION = "action:open_data_manager"

        private const val ACTION_REFRESH = "com.diegonmarcos.superapp.network.DATA_REFRESH"
        private const val RC_GRANT = 0x4441
        private const val RC_REFRESH = 0x4442
        private const val RC_OPEN = 0x4443
        private const val MIN_GAP_MS = 30_000L

        private fun openDataManager(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, RC_OPEN,
            Intent(ctx, MainActivity::class.java)
                .putExtra("shortcut_action", OPEN_ACTION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Data usage", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Data used today and this month, mobile vs Wi-Fi, top apps."
                    setShowBadge(false)
                },
            )
        }
    }
}
