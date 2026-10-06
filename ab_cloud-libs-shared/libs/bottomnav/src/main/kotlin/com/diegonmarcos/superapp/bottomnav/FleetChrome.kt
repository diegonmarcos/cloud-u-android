package com.diegonmarcos.superapp.bottomnav

import android.app.Activity
import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat

/**
 * THE window chrome and the colour of the fleet's navigation, owned here so that no app can wear
 * it differently (#868 follow-up: "the other apps' bottom navs are not the SuperApp's").
 *
 * Cloud SuperApp is the reference. Its look came from three places that the other apps each
 * re-derived on their own: the window (edge-to-edge, transparent bars, cutout, blur flags,
 * ShellActivity.onCreate), the island's colours (the launcher theme's colorSurfaceInverse /
 * colorOnSurfaceInverse / colorOnSurfaceVariant, which on Android 12+ is the DYNAMIC dark palette
 * because App.onCreate applies DynamicColors in a forced-dark app), and the haptics. All three are
 * here. An app calls [apply] from its MainActivity and then draws the island with entries,
 * selection and callbacks only; it cannot hand the island a colour scheme, a size, an inset or a
 * typeface, because no such parameter exists.
 */
public object FleetChrome {

    /**
     * SuperApp's window, applied to [activity]: content draws behind transparent status and
     * navigation bars (the island and the strips read the insets themselves), the platform's
     * contrast scrim is off, a display cutout is drawn into on its short edges, and the window
     * carries the same background-blur flags. Call it from onCreate, after super.onCreate and
     * before setContent / setContentView. It touches the window only, never a theme, so an app
     * keeps its own page backgrounds.
     *
     * The system-bar ICON tint is left to the app's theme: SuperApp is forced dark, so its icons
     * are always light, whereas an app with a light mode (Vault) must keep legible icons there.
     */
    @JvmStatic
    public fun apply(activity: Activity) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                window.setBackgroundBlurRadius(WINDOW_BLUR_RADIUS)
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply { blurBehindRadius = BLUR_BEHIND_RADIUS }
            }
        }
    }

    internal const val WINDOW_BLUR_RADIUS: Int = 40
    internal const val BLUR_BEHIND_RADIUS: Int = 20

    /** The pill and ink colours of the island, declared in res/values/colors.xml: the static
     *  Material 3 dark palette SuperApp's launcher theme resolves to below Android 12. */
    internal fun staticScheme(ctx: Context): ColorScheme = darkColorScheme(
        inverseSurface = Color(ctx.getColor(R.color.bottom_nav_pill_fill)),
        inverseOnSurface = Color(ctx.getColor(R.color.bottom_nav_pill_ink)),
        onSurfaceVariant = Color(ctx.getColor(R.color.bottom_nav_idle_ink)),
    )

    /**
     * The island's colour scheme, the same in every app: the dark dynamic palette on Android 12+
     * (what SuperApp's Material View theme resolves to there, wallpaper-derived, so the pill and
     * ink match SuperApp's on the same phone), the static dark palette below it. Nothing an app
     * puts in its MaterialTheme reaches it.
     */
    internal fun scheme(ctx: Context): ColorScheme =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { dynamicDarkColorScheme(ctx) }.getOrNull() ?: staticScheme(ctx)
        } else {
            staticScheme(ctx)
        }

    @Composable
    internal fun islandScheme(): ColorScheme {
        val ctx = LocalContext.current
        return remember(ctx) { scheme(ctx) }
    }

    /** The pill and ink triple as ARGB ints, for an app's tests that measure the island it hosts. */
    public class Palette(public val pill: Int, public val onPill: Int, public val idle: Int)

    @JvmStatic
    public fun palette(ctx: Context): Palette = scheme(ctx).let {
        Palette(it.inverseSurface.toArgb(), it.inverseOnSurface.toArgb(), it.onSurfaceVariant.toArgb())
    }

    /** The pill and ink triple as plain colours, for tests that compare pixels. */
    internal data class Ink(val pill: Color, val onPill: Color, val idle: Color)

    @Composable
    internal fun ink(): Ink = islandScheme().let { Ink(it.inverseSurface, it.inverseOnSurface, it.onSurfaceVariant) }
}

/**
 * SuperApp's haptics for navigation, owned by the lib so the island buzzes the same everywhere.
 * Ported from superapp's ui/Haptics.kt (the Gemini-app feel: a press, a pause, four ticks, a
 * settle). SuperApp keeps its "Vibration on tap" toggle by assigning [enabled]; every other app
 * has none and always buzzes, as SuperApp does by default.
 */
public object FleetHaptics {

    /** Global on/off. SuperApp assigns its launcher setting; the default is on. */
    @Volatile
    public var enabled: (Context) -> Boolean = { true }

    private val handler = Handler(Looper.getMainLooper())

    private const val FLAGS: Int = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING

    public fun gestureStart(view: View) {
        if (!enabled(view.context)) return
        press(view, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.GESTURE_START else HapticFeedbackConstants.VIRTUAL_KEY)
        fire(view.context, 0.5f, 20)
    }

    public fun gestureEnd(view: View) {
        if (!enabled(view.context)) return
        press(view, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.KEYBOARD_TAP)
        fire(view.context, 0.7f, 25)
    }

    public fun segmentTick(view: View) {
        if (!enabled(view.context)) return
        press(view, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) HapticFeedbackConstants.CLOCK_TICK else HapticFeedbackConstants.KEYBOARD_TAP)
        fire(view.context, 0.3f, 15)
    }

    /** A single light press, for tiles and lighter gestures. */
    public fun tap(view: View) {
        if (!enabled(view.context)) return
        press(view, HapticFeedbackConstants.VIRTUAL_KEY)
        fire(view.context, 0.45f, 18)
    }

    /** press at t=0, four ticks at 250/330/410/490 ms, settle at 560 ms: SuperApp's section-change rhythm. */
    public fun geminiPattern(view: View) {
        handler.removeCallbacksAndMessages(null)
        gestureStart(view)
        for (at in longArrayOf(250, 330, 410, 490)) handler.postDelayed({ segmentTick(view) }, at)
        handler.postDelayed({ gestureEnd(view) }, 560)
    }

    private fun press(view: View, constant: Int) {
        runCatching {
            view.isHapticFeedbackEnabled = true
            view.performHapticFeedback(constant, FLAGS)
        }
    }

    private fun fire(ctx: Context, intensity: Float, durationMs: Long) {
        val v = vibrator(ctx) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val ok = runCatching {
                v.vibrate(
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, intensity.coerceIn(0f, 1f), 0)
                        .compose(),
                )
            }.isSuccess
            if (ok) return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (runCatching { v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)) }.isSuccess) return
        }
        @Suppress("DEPRECATION")
        runCatching { v.vibrate(durationMs) }
    }

    private fun vibrator(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}
