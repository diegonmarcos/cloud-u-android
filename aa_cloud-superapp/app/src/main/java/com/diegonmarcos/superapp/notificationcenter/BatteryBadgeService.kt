package com.diegonmarcos.superapp.notificationcenter

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.battery.BatteryRepository
import java.util.concurrent.Executors

/**
 * The persistent "Battery" badge: the time to full / to empty first, then the
 * rest of what the battery reports, in the shade.
 *
 * It computes nothing. Every number is the battery SoT's
 * ([BatteryRepository.report], libs:battery BatteryTruth) and what it shows is
 * [BatteryBadgeModel]'s card of that report — the same values, and for the
 * expanded body the same strings, as the home-screen battery popup and
 * Configs › About › Battery. The receiver below is also the SoT's recorder
 * while the badge runs: each coalesced event writes one history sample.
 *
 * Refresh is EVENT-DRIVEN: a dynamic ACTION_BATTERY_CHANGED receiver (it cannot
 * be manifest-registered), plus screen on/off so the history can split
 * screen-on from screen-off drain - no timer polls the battery. The system
 * fires it at most every few seconds while charging, so events are coalesced
 * to one read per [MIN_GAP_MS].
 *
 * Without the Android 13+ POST_NOTIFICATIONS grant it posts nothing.
 */
class BatteryBadgeService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "battery-badge").apply { isDaemon = true } }
    private var receiver: BroadcastReceiver? = null
    private var pending: Runnable? = null
    @Volatile private var lastPostMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        startForeground(NOTIF_ID, build(placeholder()))
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { requestRefresh() }
        }
        receiver = r
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        requestRefresh()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        requestRefresh() // restart, renotify (swipe on 14+)
        return START_STICKY
    }

    override fun onDestroy() {
        receiver?.let { runCatching { unregisterReceiver(it) } }
        pending?.let(main::removeCallbacks)
        worker.shutdownNow()
        NotifyGroups.release(this, BADGE_ID)
        super.onDestroy()
    }

    /** Coalesce a burst of events into one read, at most one per [MIN_GAP_MS]. */
    private fun requestRefresh() = main.post {
        if (pending != null) return@post
        val wait = (MIN_GAP_MS - (System.currentTimeMillis() - lastPostMs)).coerceIn(0L, MIN_GAP_MS)
        val r = Runnable { pending = null; runCatching { worker.execute { refreshNow() } } }
        pending = r
        main.postDelayed(r, wait)
    }

    // ── worker thread ────────────────────────────────────────────────────

    private fun refreshNow() {
        // The SoT records the sample even when the shade may not be posted to.
        val report = runCatching { BatteryRepository.report(this) }.getOrNull() ?: return
        lastPostMs = System.currentTimeMillis()
        if (!canPost(this)) return
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, build(BatteryBadgeModel.card(report)))
        }
    }

    // ── drawing ──────────────────────────────────────────────────────────

    private fun placeholder() = BatteryBadgeModel.Card("Battery", "reading…", "")

    private fun build(card: BatteryBadgeModel.Card): Notification {
        val pinned = BadgeServices.pinned(this, BADGE_ID)
        val open = PendingIntent.getActivity(
            this, RC_OPEN,
            com.diegonmarcos.superapp.batterystats.BatteryStatsPage.intent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(0xFF0A0A0A.toInt())
            .setContentTitle(card.title)
            .setContentText(card.text)
            .setSubText("Cloud SA - Battery")
            .setStyle(NotificationCompat.BigTextStyle().bigText(card.expanded))
            .setContentIntent(open)
            .setOngoing(pinned)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .apply { if (pinned) setDeleteIntent(BadgeServices.repostOnDismiss(this@BatteryBadgeService, NOTIF_ID)) }
        return NotifyGroups.attach(this, b, BADGE_ID).build()
            .apply { if (pinned) flags = flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT }
    }

    companion object {
        const val NOTIF_ID = 7717
        const val CHANNEL_ID = "battery_status"
        /** This badge's id in build.json::ui.notification_center.producers. */
        const val BADGE_ID = "battery_status"

        private const val RC_OPEN = 0x4231
        private const val MIN_GAP_MS = 10_000L

        /** Android 13+ gates the shade on POST_NOTIFICATIONS; below it, always allowed. */
        fun canPost(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Battery", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Battery: time to full or empty, level, current, voltage, temperature, health."
                    setShowBadge(false)
                },
            )
        }
    }
}
