package com.diegonmarcos.superapp.onehand

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Entry point app/ (the Configs/Onehand toggle) calls. The overlay is hosted by
 * [OneHandOverlayService], a plain foreground service that needs ONLY "Display
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
        if (isOn(ctx) && ready(ctx)) start(ctx)
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
    ): Boolean = runCatching { ctx.startForegroundService(i) }.isSuccess
}
