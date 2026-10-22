// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * #1103: the shortcut to Android's own keyboard (input method) settings, behind the toolbar key ANDROID_SETTINGS
 * and the entry of the same name in Configs. One intent, declared once, so the two never differ.
 */
object AndroidKeyboardSettings {
    const val ACTION = Settings.ACTION_INPUT_METHOD_SETTINGS

    fun intent(): Intent = Intent(ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    /** Opens the settings page; false when the phone has no activity for it. */
    fun launch(context: Context): Boolean = runCatching { context.startActivity(intent()); true }.getOrDefault(false)
}
