package app.sterna.ui

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * How long a screen-level push/pop slide runs, in milliseconds.
 */
internal const val SCREEN_SLIDE_MS = 220

/**
 * Whether non-essential, decorative animations should play: off when the reader has turned on the
 * "Remove animations" accessibility setting (animator duration scale 0, read below) OR the device
 * is in the SYSTEM's battery saver mode ([PowerManager.isPowerSaveMode]).
 *
 * Every animated view in this app already calls this one function — [LoadingRing],
 * [rememberMotionEnabled] callers in the inbox, search, settings and compose screens, the screen
 * transitions in [app.sterna.ui.SternaApp] — so adding the battery-saver read here, once, is what
 * makes ALL of them honour it; a second gate on just one screen would agree with this one today and
 * silently drift the day either changes.
 *
 * NOT #435's now-deleted cloud-power-saving launcher mode: that was the SuperApp's own launcher
 * state, a different app entirely. This reads the actual system/battery-saver signal.
 */
@Composable
fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        val animatorDurationScale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        val powerSaveMode = runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
        }.getOrDefault(false)
        motionEnabled(animatorDurationScale, powerSaveMode)
    }
}

/**
 * The pure decision behind [rememberMotionEnabled], so it can be executed without an Android
 * context: motion plays only when the reader has not zeroed the animator duration scale AND the
 * device is not in battery saver. Either one alone is enough to hold every animation still.
 */
internal fun motionEnabled(animatorDurationScale: Float, powerSaveMode: Boolean): Boolean =
    animatorDurationScale != 0f && !powerSaveMode
