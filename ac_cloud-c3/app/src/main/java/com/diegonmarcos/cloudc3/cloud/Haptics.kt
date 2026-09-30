package com.diegonmarcos.cloudc3.cloud

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * The tap haptic the stack rows fire — MOVED from the SuperApp's ui package with the pages
 * (#648), trimmed to the one primitive they call ([tap]); the gesture-pattern helpers stayed
 * with the launcher chrome that fires them. ONE adaptation beyond package/R: the SuperApp
 * gates haptics behind its Configs ▸ Launcher toggle (LauncherSettingsPrefs), a launcher
 * settings store this app does not carry — here the gate is always open, which is that
 * store's own default.
 */
object Haptics {

    /** Lightweight single-tick "press" — for tile clicks and lighter
     *  gestures where firing a whole gesture-pattern would be too
     *  heavy and stack up under rapid input. */
    fun tap(view: View) {
        runCatching {
            view.isHapticFeedbackEnabled = true
            view.performHapticFeedback(
                HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
            )
        }
        fire(view.context, intensity = 0.45f, durationMs = 18)
    }

    /** Direct-to-vibrator: composition primitive on API 31+, predefined
     *  EFFECT_TICK on 29+, plain one-shot on older. Wrapped in
     *  runCatching because PRIMITIVE_LOW_TICK throws on OEMs that
     *  don't expose composition primitives — the silent failure is
     *  better than a crash. */
    private fun fire(ctx: Context, intensity: Float, durationMs: Long) {
        val v = vibrator(ctx) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(
                        VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                        intensity.coerceIn(0f, 1f),
                        0,
                    ).compose()
                v.vibrate(effect)
            }.onFailure { /* fall through to fallback below */ }
                .onSuccess { return }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            }.onSuccess { return }
        }
        runCatching {
            @Suppress("DEPRECATION")
            v.vibrate(durationMs)
        }
    }

    private fun vibrator(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}
