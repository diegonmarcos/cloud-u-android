package com.diegonmarcos.superapp.floatingnav

import android.content.Context
import android.provider.Settings
import com.diegonmarcos.superapp.devtools.AppDebugServer
import com.diegonmarcos.superapp.onehand.OneHandAccessibilityService
import com.diegonmarcos.superapp.onehand.OneHandPrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * #775 — `/api/overlays` on the fleet debug API (libs:devtools, loopback +
 * fleet token): every overlay window this app has on screen right now, next to
 * the switch that is supposed to govern it. "The button is back although it is
 * off" is then a curl with the phone locked, not a screenshot.
 *
 * Each owner reports its OWN live state (the windows it really added), so this
 * cannot disagree with the screen the way a mirror of the prefs could.
 */
object OverlaysDebugApi {

    fun register(ctx: Context) {
        val app = ctx.applicationContext
        AppDebugServer.route(
            "overlays",
            listOf(AppDebugServer.Op("", "", "overlay windows drawn now + each owner's switch, build gate and grant")),
        ) { op, _ -> if (op.isEmpty() || op == "state") json(app).toString() else null }
    }

    fun json(ctx: Context): JSONObject {
        val windows = JSONArray()
        if (FloatingNavService.bubbleDrawn) windows.put("floating_nav.bubble")
        if (FloatingNavService.barDrawn) windows.put("floating_nav.bar")
        val handles = OneHandAccessibilityService.instance?.handleCount ?: 0
        repeat(handles) { windows.put("onehand.handle") }
        if (ScreensaverService.drawn) windows.put("screensaver.cover")
        return JSONObject()
            .put("can_draw_overlays", Settings.canDrawOverlays(ctx))
            .put("windows", windows)
            .put("floating_nav", JSONObject()
                .put("toggle", FloatingNavPrefs.enabled(ctx))
                .put("build_enabled", FloatingNavConfig.get().enabled)
                .put("allowed", FloatingNavService.overlayAllowed(ctx))
                .put("service_running", FloatingNavService.isRunning)
                .put("armed", FloatingNavService.armed)
                .put("bubble", FloatingNavService.bubbleDrawn)
                .put("bar", FloatingNavService.barDrawn))
            .put("onehand", JSONObject()
                .put("toggle", OneHandPrefs.isEnabled(ctx))
                .put("accessibility_connected", OneHandAccessibilityService.isConnected)
                .put("handles", handles))
            .put("screensaver", JSONObject()
                .put("allowed", ScreensaverService.allowed(ctx))
                .put("running", ScreensaverService.isRunning)
                .put("cover", ScreensaverService.drawn))
    }
}
