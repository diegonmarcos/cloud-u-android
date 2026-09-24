package com.diegonmarcos.superapp.notificationcenter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.health.HealthConnectGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * #517 — the "Health" badge: active kcal burned today and km walked today.
 *
 * A foreground service for the same reason `KdeStatusService` is one: an
 * ongoing notify from a dead process is droppable, and this badge is declared
 * persistent. It is started by [BadgeServices.ensureAll] like every other
 * persistent badge — there is no bespoke start call for it anywhere, which is
 * the whole point of #515.
 *
 * DATA ROUTE, and why. Android has four places this data could come from:
 * Health Connect, Google Fit (deprecated and shut down), Samsung Health (no
 * public read API without partner onboarding), and the raw TYPE_STEP_COUNTER
 * sensor. Only Health Connect can deliver BOTH halves truthfully:
 * TYPE_STEP_COUNTER gives steps-since-boot, from which km is a stride-length
 * guess and active kcal is not derivable at all without a body profile. This
 * app already ships `:libs:health` with a Health Connect client, the read
 * permissions for ActiveCaloriesBurnedRecord and DistanceRecord are already
 * in its canonical permission set, and on a Samsung handset Samsung Health
 * writes both into Health Connect. So: Health Connect, no new dependency.
 *
 * NOTHING HERE IS ESTIMATED. A half with no grant renders as "not available"
 * with the reason — never as 0, never as a number inferred from steps. A
 * badge that invents the owner's health data is worse than one that admits it
 * does not have it.
 */
class HealthBadgeService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private val main = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // Foreground within the first few seconds or the platform kills us —
        // so post the "reading…" face immediately and fill it in when the
        // Health Connect read returns.
        startForeground(NOTIF_ID, build(null))
        schedule()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refresh()
        return START_STICKY
    }

    override fun onDestroy() {
        tick?.let(main::removeCallbacks)
        scope.cancel()
        super.onDestroy()
    }

    /** Re-read on the declared cadence. `refresh_minutes` is a customization
     *  option, so the owner sets how hard this hits Health Connect. */
    private fun schedule() {
        tick?.let(main::removeCallbacks)
        val r = object : Runnable {
            override fun run() {
                refresh()
                main.postDelayed(this, refreshMs())
            }
        }
        tick = r
        main.postDelayed(r, refreshMs())
    }

    private fun badge(): BadgeDeclaration.Badge? =
        BadgeServices.declared.firstOrNull { it.id == BADGE_ID }

    private fun refreshMs(): Long {
        val m = badge()?.let { BadgeCustomization.text(this, it, "refresh_minutes") }
            ?.toLongOrNull() ?: DEFAULT_REFRESH_MIN
        return m.coerceIn(5L, 240L) * 60_000L
    }

    private fun refresh() {
        scope.launch {
            val today = runCatching { HealthConnectGateway.readActivityToday(applicationContext) }
                .getOrNull()
            main.post {
                runCatching {
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                        .notify(NOTIF_ID, build(today))
                }
            }
        }
    }

    /**
     * One line per half the owner asked to see. A half that could not be read
     * says so; a half that read 0 says 0, because 0 km walked is a true fact
     * about today and "not available" is not.
     */
    private fun build(today: HealthConnectGateway.ActivityToday?): android.app.Notification {
        val b = badge()
        val showKcal = b?.let { BadgeCustomization.bool(this, it, "show_kcal") } ?: true
        val showKm = b?.let { BadgeCustomization.bool(this, it, "show_km") } ?: true

        val parts = mutableListOf<String>()
        if (showKcal) parts += today?.activeKcal
            ?.let { getString(R.string.badge_health_kcal, it.toInt()) }
            ?: getString(R.string.badge_health_kcal_unavailable)
        if (showKm) parts += today?.distanceKm
            ?.let { getString(R.string.badge_health_km, String.format(Locale.US, "%.2f", it)) }
            ?: getString(R.string.badge_health_km_unavailable)

        val text = if (today == null) getString(R.string.badge_health_reading)
        else parts.joinToString("  ·  ")

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(0xFF0A0A0A.toInt())
            .setContentTitle(getString(R.string.badge_health_title))
            .setContentText(text)
            .setSubText("Cloud SA - Health")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setDeleteIntent(BadgeServices.repostOnDismiss(this, NOTIF_ID))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE) // health data stays off the lockscreen
            .apply {
                // The WHY, where the owner can actually read it, instead of a
                // badge that silently shows one half.
                today?.reason?.takeIf { it.isNotBlank() }?.let {
                    setStyle(NotificationCompat.BigTextStyle().bigText("$text\n\n$it"))
                }
            }
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Health activity", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Active calories and distance walked today."
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val NOTIF_ID = 7713
        private const val CHANNEL_ID = "health_activity"
        private const val DEFAULT_REFRESH_MIN = 30L

        /** This badge's id in build.json::ui.notification_center.producers. */
        const val BADGE_ID = "health_activity"
    }
}
