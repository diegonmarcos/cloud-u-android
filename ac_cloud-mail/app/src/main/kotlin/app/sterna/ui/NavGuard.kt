package app.sterna.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry

/** True only while this lifecycle is the resumed (settled, on-top) one. A destination
 *  mid-transition is at most STARTED, and one just navigated away from drops out of RESUMED
 *  synchronously, so the whole re-entrancy question reduces to this one read. */
private fun Lifecycle.isSettled(): Boolean = currentState == Lifecycle.State.RESUMED

private fun NavBackStackEntry.isSettled(): Boolean = lifecycle.isSettled()

/**
 * THE single decision point for every navigation action in the app. A new NavHost that forgets it is
 */
internal fun NavBackStackEntry.navigateOnce(action: () -> Unit) {
    if (isSettled()) action()
}

/**
 * The same guarantee as [navigateOnce] for an action that LEAVES the app (#106 follow-up: a double
 */
@Composable
internal fun rememberLeaveOnce(entry: NavBackStackEntry): (() -> Boolean) -> Unit =
    rememberLeaveOnce(entry.lifecycle)

/**
 * [rememberLeaveOnce] for a hand-off written deep inside a screen, where threading the destination's
 */
@Composable
internal fun rememberLeaveOnce(): (() -> Boolean) -> Unit =
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

/** The decision [rememberLeaveOnce] makes, on its own so it can be tested without a composition —
 *  which is why the caller's `startActivity` has to report back rather than swallow its exception.
 *  Plain fields, not Compose state: nothing reads the latch while composing. */
internal class LeaveLatch {
    private var left = false

    fun leave(settled: Boolean, action: () -> Boolean) {
        if (!left && settled) left = action()
    }

    fun release() {
        left = false
    }
}
