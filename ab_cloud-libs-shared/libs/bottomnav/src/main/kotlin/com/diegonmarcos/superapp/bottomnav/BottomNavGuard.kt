package com.diegonmarcos.superapp.bottomnav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry

/** True only while this lifecycle is the resumed (settled, on-top) one. */
internal fun Lifecycle.isSettled(): Boolean = currentState == Lifecycle.State.RESUMED

/**
 * A guard for an action that LEAVES the app: it fires at most once per time in the foreground (the
 * latch re-arms on the next ON_RESUME), so a double tap while a hand-off is in motion cannot launch
 * the target app twice. Shared so the bottom-navigation island's launch items and every screen's
 * own hand-offs use the SAME implementation (#106 follow-up, re-homed from cloud-mail for #493).
 * The caller's [action] reports whether the hand-off got off the ground, which is the only way the
 * latch knows to keep it latched.
 */
@Composable
public fun rememberLeaveOnce(entry: NavBackStackEntry): (() -> Boolean) -> Unit =
    rememberLeaveOnce(entry.lifecycle)

/** [rememberLeaveOnce] for a hand-off written deep inside a screen, where threading the
 *  destination's back-stack entry would leak [NavBackStackEntry] into the caller. */
@Composable
public fun rememberLeaveOnce(): (() -> Boolean) -> Unit =
    rememberLeaveOnce(LocalLifecycleOwner.current.lifecycle)

@Composable
private fun rememberLeaveOnce(lifecycle: Lifecycle): (() -> Boolean) -> Unit {
    val latch = remember(lifecycle) { LeaveLatch() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) latch.release()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return { action -> latch.leave(lifecycle.isSettled(), action) }
}

/** The decision [rememberLeaveOnce] makes, on its own so it can be tested without a composition.
 *  Plain fields, not Compose state: nothing reads the latch while composing. */
public class LeaveLatch {
    private var left = false

    public fun leave(settled: Boolean, action: () -> Boolean) {
        if (!left && settled) left = action()
    }

    public fun release() {
        left = false
    }
}