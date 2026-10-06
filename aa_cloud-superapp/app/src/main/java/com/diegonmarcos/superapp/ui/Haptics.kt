package com.diegonmarcos.superapp.ui

import android.view.View
import com.diegonmarcos.superapp.bottomnav.FleetHaptics

/**
 * Named haptic helpers — mirrors the Gemini-app feel for bottom-nav
 * presses: GESTURE_START on tap, two SEGMENT_TICK pulses while the
 * fragment transitions, GESTURE_END when the new screen settles.
 *
 * Each method picks the best primitive available on the device and
 * gracefully falls back on older APIs:
 *   GESTURE_START / GESTURE_END  → API 30+        (R)  fallback VIRTUAL_KEY
 *   SEGMENT_TICK                  → API 33+        (T)  fallback EFFECT_TICK (API 29) → KEYBOARD_TAP
 *   PRIMITIVE_LOW_TICK            → API 31+        (S)
 *   EFFECT_TICK                   → API 29+        (Q)
 *
 * Constants live in HapticFeedbackConstants + VibrationEffect; the
 * mapping is encoded here so callers stay terse: `Haptics.gestureStart(view)`.
 */
object Haptics {
    // The primitives live in libs:bottomnav (FleetHaptics) so the island buzzes the same in every
    // app; this object keeps the call sites of the shell. The "Vibration on tap" setting is
    // installed in App.onCreate, through FleetHaptics.enabled.
    fun gestureStart(view: View) = FleetHaptics.gestureStart(view)
    fun gestureEnd(view: View) = FleetHaptics.gestureEnd(view)
    fun segmentTick(view: View) = FleetHaptics.segmentTick(view)
    fun tap(view: View) = FleetHaptics.tap(view)
}
