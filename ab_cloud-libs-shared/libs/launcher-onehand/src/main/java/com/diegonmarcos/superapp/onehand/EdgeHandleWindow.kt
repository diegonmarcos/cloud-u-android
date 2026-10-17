package com.diegonmarcos.superapp.onehand

import android.view.WindowManager.LayoutParams as LP

/**
 * PURE geometry + flags of the idle edge-handle window (JVM-testable). The idle overlay is the ONLY
 * thing of ours that can take touches, so it is bounded here no matter what build.json declares:
 * thin (a tap on it cannot be forwarded to the app below, so the dead strip must be tiny) and short
 * (<= the 200dp system-gesture-exclusion cap).
 */
object EdgeHandleWindow {
    /**
     * 28dp: the strip must stick out past the system back-gesture band (~24dp) AND Samsung's curved-edge
     * touch-reject zone, or a thumb never lands on it and the menu "doesn't work at all" (12dp and 24dp
     * both failed on the owner's phone). Never below [MIN_HIT_DP].
     */
    const val MAX_THICKNESS_DP = 28

    /** The narrowest hit area a strip may ever have, whatever a pref or build.json says. */
    const val MIN_HIT_DP = 26
    const val MAX_LENGTH_DP = 160

    /** The widest strip the owner may ask for in Configs (Samsung's gesture zone can be wider than the default strip clears). */
    const val USER_MAX_THICKNESS_DP = 40

    /** NOT_TOUCH_MODAL: touches outside the strip go to the window behind. NOT_FOCUSABLE: never steals keys/IME. */
    const val FLAGS = LP.FLAG_NOT_FOCUSABLE or LP.FLAG_NOT_TOUCH_MODAL or
        LP.FLAG_LAYOUT_IN_SCREEN or LP.FLAG_LAYOUT_NO_LIMITS

    /** Flags of the transient full-screen preview veil: it must never be touchable. */
    const val PREVIEW_FLAGS = LP.FLAG_NOT_FOCUSABLE or LP.FLAG_NOT_TOUCHABLE or LP.FLAG_NOT_TOUCH_MODAL or
        LP.FLAG_LAYOUT_IN_SCREEN or LP.FLAG_LAYOUT_NO_LIMITS

    data class Size(val widthPx: Int, val heightPx: Int)

    /** [lengthPx]/[thicknessPx] are what the config asked for; the result is clamped to the caps. */
    fun size(edge: OneHandConfig.Edge, lengthPx: Int, thicknessPx: Int, density: Float, maxThicknessDp: Int = MAX_THICKNESS_DP): Size {
        val len = lengthPx.coerceIn(1, (MAX_LENGTH_DP * density).toInt())
        val thick = thicknessPx.coerceIn((MIN_HIT_DP * density).toInt(), (maxOf(maxThicknessDp, MIN_HIT_DP) * density).toInt())
        return if (edge == OneHandConfig.Edge.BOTTOM) Size(len, thick) else Size(thick, len)
    }
}
