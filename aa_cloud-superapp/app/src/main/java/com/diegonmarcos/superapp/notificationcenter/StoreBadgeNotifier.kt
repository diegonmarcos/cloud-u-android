package com.diegonmarcos.superapp.notificationcenter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.R

/**
 * #812 — the Store badge of Configs ▸ Launcher ▸ Notify: "N Store updates
 * pending", declared as `store_updates` in `ui.notification_center` (group
 * `store`).
 *
 * NON-persistent on purpose: never ongoing, never FLAG_NO_CLEAR, no service
 * behind it. [StoreAuto][com.diegonmarcos.superapp.appstore.StoreAuto] tells
 * it the pending count after every chain; 0 — the updates are gone, or the
 * Store was opened — cancels it. Swiping it away is allowed and final until
 * the next chain.
 */
object StoreBadgeNotifier {

    const val BADGE_ID = "store_updates"
    /** Channel ids are the user's saved settings — never rename. */
    const val CHANNEL_ID = "store_updates"
    const val NOTIFICATION_ID = 0x5A0E

    private fun nm(ctx: Context) = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun badge() = BadgeServices.declared.firstOrNull { it.id == BADGE_ID }

    /** Post or clear for [pending]. Returns true when a badge is now posted. */
    @Synchronized
    fun update(ctx: Context, pending: Int): Boolean {
        val on = badge()?.let { BadgeCustomization.isEnabled(ctx, it) } == true
        if (pending <= 0 || !on) { runCatching { nm(ctx).cancel(NOTIFICATION_ID) }; return false }
        if (Build.VERSION.SDK_INT >= 26 && nm(ctx).getNotificationChannel(CHANNEL_ID) == null)
            nm(ctx).createNotificationChannel(NotificationChannel(CHANNEL_ID, "Store updates",
                NotificationManager.IMPORTANCE_LOW).apply { description = "Pending Store updates (clears itself)." })
        val text = "$pending Store update${if (pending == 1) "" else "s"} pending"
        val open = PendingIntent.getActivity(ctx, NOTIFICATION_ID,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val g = NotifyGroups.groupOf(BADGE_ID)
        return runCatching {
            nm(ctx).notify(NOTIFICATION_ID, NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setColor(0xFF0A0A0A.toInt())
                .setContentTitle("Store")
                .setContentText(text)
                .setNumber(pending)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .apply { if (g != null) setGroup(NotifyGroups.key(g)) }
                .build())
            true
        }.getOrDefault(false)
    }
}
