package com.diegonmarcos.superapp.notificationcenter

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.notificationcenter.BadgeDeclaration.Badge

/**
 * #515 — the ONE restart path, and the ONE place that can say why a badge is
 * not there.
 *
 * Before this, a persistent badge came back after an app update only if
 * somebody had hand-written its start call into `App.onCreate`. Exactly one
 * had: `KdeStatusService`. Everything owned by `FloatingNavService` —
 * Quickmarks, Media, Alerts — was started only from three user-driven places
 * (the app shell, the Control overlay toggle, the One-Hand toggle), all of
 * them through `startIfPermitted`, which returns false in silence when the
 * overlay permission is missing. Android kills every service on package
 * replace and does not send BOOT_COMPLETED for an update, so after an update
 * KDE came back and the other three did not. One fault, two-missing-one-
 * surviving, which is exactly the shape that got reported.
 *
 * The fix is not a fourth hand-written start call. It is: the declaration says
 * which producers are persistent, and [ensureAll] starts whatever they name —
 * so a badge added to build.json tomorrow is covered by this without a Kotlin
 * edit, and no badge can be persistent-by-declaration but absent-by-mechanism.
 */
object BadgeServices {

    private const val TAG = "BadgeServices"

    /** Why a badge is not on screen. [LIVE] is the only good answer; every
     *  other one is a sentence the Push pane shows instead of a green dot. */
    enum class State { LIVE, DISABLED, BLOCKED, DEAD, NO_SERVICE }

    data class Status(val badge: Badge, val state: State, val reason: String)

    /** The declaration as this build baked it. */
    val declared: List<Badge> by lazy {
        runCatching {
            BadgeDeclaration.parse(
                String(Base64.decode(BuildConfig.UI_NOTIFICATION_CENTER_B64, Base64.NO_WRAP)),
            )
        }.getOrDefault(emptyList())
    }

    /**
     * Re-ensure every persistent badge's owning service. Called from
     * [BadgeRestartReceiver] on MY_PACKAGE_REPLACED / BOOT_COMPLETED and from
     * `App.onCreate` — the same set, resolved the same way, so a cold start
     * and an update converge on the same services running.
     *
     * Idempotent: starting an already-running service is a no-op `onStartCommand`.
     *
     * Returns the services it actually started, for the test and for the log.
     */
    fun ensureAll(ctx: Context): List<String> {
        val app = ctx.applicationContext
        val started = mutableListOf<String>()
        for (fqcn in BadgeDeclaration.restartServices(declared)) {
            // A service is ensured only if at least one badge it owns has its
            // grants. Starting FloatingNavService with no overlay permission
            // would fail the way it has been failing: silently.
            val owned = BadgeDeclaration.badgesOf(declared, fqcn)
            val startable = owned.any { missingRequirement(app, it) == null && BadgeCustomization.isEnabled(app, it) }
            if (!startable) {
                Log.i(TAG, "skip $fqcn — no enabled badge of its ${owned.size} has its grants")
                continue
            }
            if (start(app, fqcn)) started += fqcn
        }
        return started
    }

    private fun start(ctx: Context, fqcn: String): Boolean = runCatching {
        val cls = Class.forName(fqcn)
        ContextCompat.startForegroundService(ctx, Intent(ctx, cls))
        Log.i(TAG, "ensured $fqcn")
        true
    }.getOrElse {
        // A declaration naming a class that is not in this build is a data
        // error, and it must be loud rather than a badge that never appears.
        Log.w(TAG, "cannot ensure $fqcn: ${it.javaClass.simpleName}: ${it.message}")
        false
    }

    /**
     * #535 — the delete intent every persistent badge carries. Since Android 14
     * an ongoing (even foreground-service) notification is swipeable, and a
     * badge with no delete intent is then simply gone while its service keeps
     * running and the Push pane keeps saying LIVE. Re-starting the owner lands
     * in its onStartCommand, which re-posts. KDE and FloatingNav already did
     * this by hand; Health, Weather and Markets did not.
     */
    fun repostOnDismiss(svc: Service, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            svc, requestCode, Intent(svc, svc.javaClass),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    // ── What the Push pane shows ─────────────────────────────────────────

    /**
     * #535 — the badge AS IT IS IN THE SHADE: this app's own posted
     * notification on the badge's declared `channel`, or null when nothing is
     * posted there. The pane renders this instead of the declaration's `shows`
     * sentence, which described a badge rather than showing one, and it is the
     * only way to see a badge that was swiped away while its service ran on.
     *
     * The channel is the join key, so it has to be the channel the owner really
     * posts on — Quick Actions declared `floating_nav_actions` while
     * FloatingNavService posts on `floating_nav`, and nothing noticed because
     * nothing read it. A group (Infos posts a summary plus children on one
     * channel) answers with its summary.
     */
    fun live(ctx: Context, b: Badge): Notification? = runCatching {
        if (b.channel.isBlank()) return null
        val mine = (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .activeNotifications.map { it.notification }.filter { it.channelId == b.channel }
        mine.firstOrNull { it.flags and Notification.FLAG_GROUP_SUMMARY != 0 } ?: mine.firstOrNull()
    }.getOrNull()


    fun statuses(ctx: Context): List<Status> =
        BadgeDeclaration.badges(declared).map { status(ctx, it) }

    fun status(ctx: Context, b: Badge): Status {
        if (!BadgeCustomization.isEnabled(ctx, b))
            return Status(b, State.DISABLED, "Switched off below.")
        missingRequirement(ctx, b)?.let { return Status(b, State.BLOCKED, it) }
        if (b.service.isBlank())
            return Status(b, State.NO_SERVICE, "No owning service is declared for this badge.")
        return if (isServiceRunning(ctx, b.service))
            Status(b, State.LIVE, "Posted by ${simpleName(b.service)}.")
        else
            Status(b, State.DEAD, "${simpleName(b.service)} is not running, so this badge is not in the shade.")
    }

    /**
     * The grant a badge declared it needs and does not have, as a sentence, or
     * null if it has them all.
     *
     * The declaration names the requirement; this maps the name to the
     * platform call that answers it. That mapping cannot live in JSON —
     * `canDrawOverlays` is an API, not a value — but the LIST of requirements
     * per badge does, so adding a badge that needs the overlay is still only a
     * build.json edit.
     */
    private fun missingRequirement(ctx: Context, b: Badge): String? {
        for (req in b.requires) when (req) {
            "overlay" -> if (!Settings.canDrawOverlays(ctx))
                return "\"Display over other apps\" is not granted — Control ▸ Permissions."
            "health_connect" -> if (!healthConnectInstalled(ctx))
                return "Health Connect is not installed on this device."
            "location" -> if (ContextCompat.checkSelfPermission(ctx,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED)
                return "Location permission is not granted — Control ▸ Permissions."
            else -> return "Declared requirement \"$req\" is not one this build knows how to check."
        }
        return null
    }

    private fun healthConnectInstalled(ctx: Context): Boolean = runCatching {
        com.diegonmarcos.superapp.health.HealthConnectGateway.isProviderInstalled(ctx)
    }.getOrDefault(false)

    /**
     * Since API 26 `getRunningServices` returns only the caller's OWN
     * services, which is exactly the question being asked, and it answers it
     * for any declared class without each service having to publish a static
     * `isRunning` flag of its own.
     */
    @Suppress("DEPRECATION")
    private fun isServiceRunning(ctx: Context, fqcn: String): Boolean = runCatching {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.getRunningServices(Int.MAX_VALUE).any { it.service.className == fqcn }
    }.getOrDefault(false)

    private fun simpleName(fqcn: String) = fqcn.substringAfterLast('.')
}
