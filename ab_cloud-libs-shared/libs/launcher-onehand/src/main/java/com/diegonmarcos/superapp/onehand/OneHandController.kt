package com.diegonmarcos.superapp.onehand

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Entry point app/ (the Configs/Onehand toggle) calls. The overlay is hosted by
 * [OneHandOverlayService], a plain started service (no notification, ever) that needs ONLY "Display
 * over other apps" — no Accessibility. Enabling persists the flag and starts it;
 * disabling persists the flag and stops it. GRANTING the permission is the
 * centralized Configs > Permissions page's job — this controller never
 * deep-links.
 */
object OneHandController {

    fun canDrawOverlay(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun ready(ctx: Context): Boolean = canDrawOverlay(ctx)

    fun isOn(ctx: Context): Boolean = OneHandPrefs.isEnabled(ctx)

    fun enable(ctx: Context) {
        if (!ready(ctx)) return
        OneHandPrefs.setEnabled(ctx, true)
        start(ctx)
    }

    fun disable(ctx: Context) {
        OneHandPrefs.setEnabled(ctx, false)
        ctx.stopService(Intent(ctx, OneHandOverlayService::class.java))
    }

    /** Re-read config after a per-swipe edit so a running overlay picks it up. */
    fun refresh(ctx: Context) {
        startIfEnabled(ctx) // the service's onStartCommand re-runs showHandles()
    }

    /** App / boot start: host the handles iff the switch is ON (#775: OFF stays
     *  off) and the overlay permission is held. A no-op otherwise. */
    fun startIfEnabled(ctx: Context) {
        retireLegacyNotification(ctx)
        if (isOn(ctx) && ready(ctx)) start(ctx)
    }

    /**
     * Any SuperApp activity resumed: bring the strips back if they are not on screen. An update kills the service and the
     * home activity is not always recreated, so a resume anywhere in the app is the other door. Cheap when all is well
     * (no rebuild, unlike [startIfEnabled]).
     */
    fun ensureRunning(ctx: Context) {
        if (!isOn(ctx)) return
        if (!ready(ctx)) { EdgeEventLog.recordIfChanged("service", "not started: overlay permission missing"); return }
        val svc = OneHandOverlayService.instance
        if (svc != null && svc.handleCount > 0 && svc.handleViews.all { it.isAttachedToWindow }) return
        EdgeEventLog.recordIfChanged("service", "restart: strips not on screen (service ${if (svc == null) "dead" else "up"})")
        start(ctx)
    }

    /** Turn the handles on for [ms] then auto-off — the safe way to test. */
    fun testActivate(ctx: Context, ms: Long = 30_000L): Boolean {
        if (!ready(ctx)) return false
        return start(ctx, Intent(ctx, OneHandOverlayService::class.java)
            .setAction(OneHandOverlayService.ACTION_TEST)
            .putExtra(OneHandOverlayService.EXTRA_MS, ms))
    }

    private fun start(
        ctx: Context,
        i: Intent = Intent(ctx, OneHandOverlayService::class.java),
    ): Boolean = runCatching { ctx.startService(i) }.isSuccess

    /** Pre-fix builds ran the overlay as a foreground service with an "Edge
     *  menus on" notification on two channels. Remove all trace on upgrade:
     *  the posted notification and both channels. Idempotent, never throws. */
    fun retireLegacyNotification(ctx: Context) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(LEGACY_NOTIF_ID)
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                LEGACY_CHANNELS.forEach { nm.deleteNotificationChannel(it) }
            }
        }
    }

    private const val LEGACY_NOTIF_ID = 7750
    private val LEGACY_CHANNELS = listOf("onehand_overlay", "onehand_overlay_min")
}
