package app.sterna.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
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
 * tap on a hand-off must not fire it twice).
 *
 * THE ONE implementation lives in the shared bottom-navigation module (#493); this file only
 * forwards the names this app's screens already read, so settling, the leave guard and the latch
 * are never duplicated per app. The navigation bar reaches the same shared guard directly.
 */
@Composable
internal fun rememberLeaveOnce(entry: NavBackStackEntry): (() -> Boolean) -> Unit =
    com.diegonmarcos.superapp.bottomnav.rememberLeaveOnce(entry)

/**
 * [rememberLeaveOnce] for a hand-off written deep inside a screen, where threading the destination's
 * back-stack entry would leak [NavBackStackEntry] into the caller. Shared single home (#493).
 */
@Composable
internal fun rememberLeaveOnce(): (() -> Boolean) -> Unit =
    com.diegonmarcos.superapp.bottomnav.rememberLeaveOnce()

/** The ONE latch, in the shared module (#493). This typealias keeps [LeaveLatch] resolving for this
 *  app's code and tests while the class itself lives once, where every app reaches it. */
internal typealias LeaveLatch = com.diegonmarcos.superapp.bottomnav.LeaveLatch