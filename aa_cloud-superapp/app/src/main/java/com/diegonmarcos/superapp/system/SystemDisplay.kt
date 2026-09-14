package com.diegonmarcos.superapp.system

import android.content.Context
import android.content.res.Configuration
import com.diegonmarcos.superapp.adbdebug.ShellChannels

/**
 * The display levers Samsung keeps outside any app's reach: system-wide
 * dark mode, and the Screen zoom / Font size pair.
 *
 * All three are device-wide settings, so none of them can be done from inside
 * the process. `AppCompatDelegate.setDefaultNightMode` would repaint THIS app
 * and leave the rest of the phone light, which is not what "dark mode" means
 * on the settings screen it is drawn on. Everything here therefore goes
 * through the same privileged shell channel the Battery Hunger levers use, and
 * reports honestly when there is no channel rather than pretending it worked.
 *
 * Call off the main thread — every write is a shell round-trip.
 */
object SystemDisplay {

    private fun exec(ctx: Context): ((String) -> String?)? {
        val channel = ShellChannels.active(ctx) ?: return null
        return { command -> channel.exec(ctx, command) }
    }

    /** Whether a privileged channel exists at all, for the caller's error text. */
    fun hasChannel(ctx: Context): Boolean = ShellChannels.active(ctx) != null

    // ── Dark mode ────────────────────────────────────────────────────────

    /**
     * The phone's real uiMode, not our own intention. A switch that reports
     * what we last tried to write is the failure the whole screen exists to
     * make impossible — see PowerLevers.liveState for the same rule.
     */
    fun isNight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun setNight(ctx: Context, on: Boolean): Boolean =
        exec(ctx)?.invoke("cmd uimode night ${if (on) "yes" else "no"}") != null

    // ── Scale (Screen zoom + Font size, as one knob) ─────────────────────

    /**
     * Samsung splits this in two — Screen zoom (`wm density`) and Font size
     * (`settings put system font_scale`) — and the two drift apart the moment
     * anything writes one and not the other. They take the SAME factor here.
     *
     * 1..10 maps to 0.75..1.20 in 0.05 steps, so scale 6 is exactly 1.00: the
     * phone's own size, changing nothing. That is why the default is 6 and not
     * 1. Defaulting to 1 would shrink the whole UI on first install, which is
     * the mirror image of the complaint that produced this setting.
     */
    fun factor(scale: Int): Double = 0.70 + 0.05 * scale.coerceIn(1, 10)

    /**
     * Write both levers. Factor 1.00 RESETS them instead of pinning them to
     * the value they already have: an override that happens to equal the
     * physical number still counts as an override, and leaving one behind is
     * how a later `reset` elsewhere produces a surprise.
     *
     * @return false when there is no privileged channel — the caller must say
     *         so out loud rather than leave a slider that moves and does nothing.
     */
    fun applyScale(ctx: Context, scale: Int): Boolean {
        val exec = exec(ctx) ?: return false
        val factor = factor(scale)
        if (factor == 1.0) {
            exec("wm density reset")
            exec("settings delete system font_scale")
            return true
        }
        val physical = physicalDensity(exec)
        if (physical != null) exec("wm density ${Math.round(physical * factor)}")
        // font_scale is absolute, unlike density which multiplies the panel's
        // own number — so the same factor goes in raw.
        exec("settings put system font_scale $factor")
        return true
    }

    /** The panel's own density, ignoring any override already in place. */
    private fun physicalDensity(exec: (String) -> String?): Int? =
        exec("wm density")?.lineSequence()
            ?.firstOrNull { it.contains("Physical density") }
            ?.substringAfter(':')?.trim()?.toIntOrNull()
}
