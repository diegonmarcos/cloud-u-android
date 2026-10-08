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
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.battery.BatteryCapacity
import com.diegonmarcos.superapp.battery.BatterySessionStats
import java.util.concurrent.Executors

/**
 * The persistent "Battery" badge: the time to full / to empty first, then the
 * rest of what the battery reports, in the shade.
 *
 * What it shows is [BatteryBadgeModel]'s card and what the estimates do is
 * [BatteryEstimator]; this class only gathers. The unplug/plug anchors, the
 * rated/peak capacity and the manual cycle count come from libs:battery
 * ([BatterySessionStats], [BatteryCapacity]) - nothing of that is re-tracked
 * here. The one thing this adds is the short-term rate (an EMA), kept in the
 * "battery_badge" prefs.
 *
 * Refresh is EVENT-DRIVEN: a dynamic ACTION_BATTERY_CHANGED receiver (it cannot
 * be manifest-registered) and nothing else - no timer polls the battery. The
 * system fires it at most every few seconds while charging, so events are
 * coalesced to one read per [MIN_GAP_MS].
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
        ContextCompat.registerReceiver(this, r, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
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
        if (!canPost(this)) return
        val snap = gather() ?: return
        lastPostMs = System.currentTimeMillis()
        runCatching { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, build(snap)) }
    }

    private fun gather(): BatteryBadgeModel.Snapshot? {
        val now = System.currentTimeMillis()
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val temp = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val volt = sticky.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        val cycles = if (Build.VERSION.SDK_INT >= 34)
            sticky.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1).takeIf { it > 0 } else null

        val bm = getSystemService(BATTERY_SERVICE) as? BatteryManager
        fun prop(id: Int): Int? = runCatching { bm?.getIntProperty(id) }.getOrNull()
        val counter = runCatching { bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) }.getOrNull()
            ?.takeIf { it != Long.MIN_VALUE && it > 0L }
        val systemRemaining = if (Build.VERSION.SDK_INT >= 28 && status == BatteryManager.BATTERY_STATUS_CHARGING)
            runCatching { bm?.computeChargeTimeRemaining() }.getOrNull() ?: -1L else -1L

        // libs:battery owns the anchors, the capacities and the manual cycle count.
        val lib = runCatching { BatterySessionStats.read(this, now) }.getOrNull()
        val cap = runCatching { BatteryCapacity.read(this, lib?.peakChargeCounterUah ?: 0L) }.getOrNull()
        val capMah = cap?.fullNowMah?.takeIf { it > 0 } ?: cap?.ratedMah?.takeIf { it > 0 }
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING

        val rate = trackRate(now, pct, counter ?: 0L, (capMah ?: 0) * 1000L, charging)

        return BatteryBadgeModel.Snapshot(
            nowMs = now, levelPct = pct, status = status, plugged = plugged,
            health = sticky.getIntExtra(BatteryManager.EXTRA_HEALTH, 1),
            tempC = temp.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 },
            voltageMv = volt.takeIf { it > 0 },
            currentNowMa = BatteryEstimator.intoBatteryMa(prop(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW), status),
            currentAvgMa = BatteryEstimator.intoBatteryMa(prop(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE), status),
            cycleCount = cycles, cycleEstimate = lib?.cycleCount?.takeIf { it >= 0.0 },
            chargeCounterUah = counter, capacityMah = capMah,
            capacityIsRated = (cap?.fullNowMah ?: -1) <= 0 && (cap?.ratedMah ?: -1) > 0,
            anchorMs = (if (charging) lib?.plugTs else lib?.unplugTs) ?: 0L,
            anchorPct = (if (charging) lib?.plugPct else lib?.unplugPct) ?: -1,
            systemChargeRemainingMs = systemRemaining,
            recentPctPerMin = rate,
        )
    }

    /** Advance the persisted EMA tracker one event; returns the rate now in force (%/min, 0 = none). */
    private fun trackRate(now: Long, pct: Int, counterUah: Long, capacityUah: Long, charging: Boolean): Double {
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        val prev = if (sp.contains(K_TS)) BatteryEstimator.RateState(
            ts = sp.getLong(K_TS, 0L), levelPct = sp.getInt(K_LEVEL, -1), counterUah = sp.getLong(K_COUNTER, 0L),
            charging = sp.getBoolean(K_CHARGING, false), emaPctPerMin = sp.getFloat(K_EMA, 0f).toDouble()) else null
        val next = BatteryEstimator.track(prev, now, pct, counterUah, capacityUah, charging) ?: return 0.0
        if (next != prev) sp.edit().putLong(K_TS, next.ts).putInt(K_LEVEL, next.levelPct)
            .putLong(K_COUNTER, next.counterUah).putBoolean(K_CHARGING, next.charging)
            .putFloat(K_EMA, next.emaPctPerMin.toFloat()).apply()
        return next.emaPctPerMin
    }

    // ── drawing ──────────────────────────────────────────────────────────

    private fun placeholder() = BatteryBadgeModel.Snapshot(
        nowMs = System.currentTimeMillis(), levelPct = -1, status = -1)

    private fun build(s: BatteryBadgeModel.Snapshot): Notification {
        val card = BatteryBadgeModel.card(s)
        val pinned = BadgeServices.pinned(this, BADGE_ID)
        val open = PendingIntent.getActivity(
            this, RC_OPEN,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
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

        /** The rate tracker's store (declared in fleet-config.json). */
        const val PREFS = "battery_badge"
        private const val K_TS = "rate_ts"
        private const val K_LEVEL = "rate_level"
        private const val K_COUNTER = "rate_counter_uah"
        private const val K_CHARGING = "rate_charging"
        private const val K_EMA = "rate_ema_pct_per_min"

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
