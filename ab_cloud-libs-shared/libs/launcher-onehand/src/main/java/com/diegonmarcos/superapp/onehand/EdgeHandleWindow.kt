package com.diegonmarcos.superapp.onehand

import android.view.WindowManager.LayoutParams as LP

/**
 * PURE geometry + flags of the idle edge-handle window (JVM-testable). The idle overlay is the ONLY
 * thing of ours that can take touches, so it is bounded here no matter what build.json declares:
 * thin (a tap on it cannot be forwarded to the app below, so the dead strip must be tiny) and short
 * (<= the 200dp system-gesture-exclusion cap).
 */
object EdgeHandleWindow {
    const val MAX_THICKNESS_DP = 12
    const val MAX_LENGTH_DP = 160

    /** NOT_TOUCH_MODAL: touches outside the strip go to the window behind. NOT_FOCUSABLE: never steals keys/IME. */
    const val FLAGS = LP.FLAG_NOT_FOCUSABLE or LP.FLAG_NOT_TOUCH_MODAL or
        LP.FLAG_LAYOUT_IN_SCREEN or LP.FLAG_LAYOUT_NO_LIMITS

    /** Flags of the transient full-screen preview veil: it must never be touchable. */
    const val PREVIEW_FLAGS = LP.FLAG_NOT_FOCUSABLE or LP.FLAG_NOT_TOUCHABLE or LP.FLAG_NOT_TOUCH_MODAL or
        LP.FLAG_LAYOUT_IN_SCREEN or LP.FLAG_LAYOUT_NO_LIMITS

    data class Size(val widthPx: Int, val heightPx: Int)

    /** [lengthPx]/[thicknessPx] are what the config asked for; the result is clamped to the caps. */
    fun size(edge: OneHandConfig.Edge, lengthPx: Int, thicknessPx: Int, density: Float): Size {
        val len = lengthPx.coerceIn(1, (MAX_LENGTH_DP * density).toInt())
        val thick = thicknessPx.coerceIn(1, (MAX_THICKNESS_DP * density).toInt())
        return if (edge == OneHandConfig.Edge.BOTTOM) Size(len, thick) else Size(thick, len)
    }
}
