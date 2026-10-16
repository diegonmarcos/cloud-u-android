package com.diegonmarcos.superapp.ui

import android.content.Context
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import com.diegonmarcos.superapp.system.ScreenLocker

/**
 * Double-tap anywhere on a home pane → lock the screen ([ScreenLocker.lockOrExplain]).
 *
 * Single taps still reach the children: a child that claims a pointer event consumes it, and
 * `detectTapGestures` ignores consumed events, so rows and tiles keep their own taps and only an
 * unclaimed double tap (empty space) reaches the lock. [onDoubleTap] is the seam a test replaces.
 */
fun Modifier.lockOnDoubleTap(ctx: Context, onDoubleTap: () -> Unit = { ScreenLocker.lockOrExplain(ctx) }): Modifier =
    pointerInput(Unit) { detectTapGestures(onDoubleTap = { onDoubleTap() }) }
