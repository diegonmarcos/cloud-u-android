package com.diegonmarcos.superapp.launcher.themes.powersaving

import android.app.Activity
import android.graphics.Color

/**
 * The Cloud Power Saving mode's window.
 *
 * ── Why a window needs its own file ──────────────────────────────────────
 * A fragment can only paint inside its own bounds. The WINDOW behind it is
 * the Activity's, and it keeps whatever `android:windowBackground` the
 * Activity's theme gave it — for this app, the default mode's purple
 * gradient. That is what the owner saw as "it does draw the black screen but
 * is not full screen": black fragment, gradient window, and the gradient
 * showing through at every inset, every over-scroll and every transition.
 *
 * [com.diegonmarcos.superapp.ui.LauncherStyle] fixes that at the root by
 * installing this mode's Material3 style before the content inflates. This
 * call is the belt to that braces: it repaints the live window black on
 * resume, which also covers the case where the Activity was created under a
 * different mode and the user switched into this one.
 *
 * ── What is NOT here ─────────────────────────────────────────────────────
 * This file used to also carry a per-screen title bar with its own back
 * chevron, written for the owner's report that "as you remove the top
 * buttons nothing now has a back button". It was never called from anywhere,
 * so the report stayed live on the phone while the code read as if it were
 * fixed — worse than not having written it. The back affordance is the
 * shell's own toolbar island, which `ShellActivity.applyLauncherChrome` now
 * keeps visible on every screen except this mode's home; see the comment
 * there. The island resolves its colours through this mode's style, so it is
 * black here without any of this file's help.
 */
internal object PowerSavingChrome {

    /**
     * Repaint [activity]'s window in this mode's ground colour.
     *
     * Transparent system bars rather than black ones: the bars sit over
     * content that is already black, so colouring them adds nothing, and
     * transparent is the one value that stays right if the ground ever
     * changes.
     */
    fun applyWindow(activity: Activity) {
        val window = activity.window
        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(PowerSavingDesign.INK_BLACK),
        )
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }
}
