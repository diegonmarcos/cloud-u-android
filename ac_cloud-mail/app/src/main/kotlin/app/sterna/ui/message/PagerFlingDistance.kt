package app.sterna.ui.message

import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/*
 * How far the FINGER must actually have travelled before a flick is allowed to change message.
 *
 */

/** The travel a fling must have covered before it may carry the reader to another message, in dp —
 *  absolute, never a share of the page, since what is bounded is a movement of the thumb, which does
 *  not grow with the display. This is `ViewPager.MIN_DISTANCE_FOR_FLING`, compared against the same
 *  quantity ViewPager compared it against. */
internal const val MIN_FLING_DISTANCE_DP = 25

/**
 * The velocity the pager is ALLOWED TO SEE, given how far the finger actually travelled. Under the
 */
internal fun flingVelocityForTravel(
    travelPx: Float,
    minDistancePx: Float,
    velocityPxPerSec: Float,
): Float = if (abs(travelPx) < minDistancePx) 0f else velocityPxPerSec

/**
 * What [advanceTravel] carries between pointer events: where the finger went down, how far it has
 * travelled since, and whether it is still down.
 */
internal data class PointerTravel(
    val downX: Float = 0f,
    val travelPx: Float = 0f,
    val fingerDown: Boolean = false,
)

/**
 * One pointer event folded into the travel being tracked.
 */
internal fun advanceTravel(
    previous: PointerTravel,
    isPress: Boolean,
    anyPressed: Boolean,
    x: Float,
): PointerTravel =
    when {
        isPress -> PointerTravel(downX = x, travelPx = 0f, fingerDown = true)
        anyPressed -> previous.copy(travelPx = x - previous.downX)
        previous.fingerDown -> previous.copy(travelPx = x - previous.downX, fingerDown = false)
        else -> previous
    }

/**
 * The finger's travel across the pager, read from the pointer stream and held for the fling.
 */
internal class PagerTouchTravel {
    private var state = PointerTravel()

    /** The travel of the gesture that just ended, signed, in px. Nothing executing can see this
     *  getter: return [PointerTravel.downX] instead — also a `Float`, also warning-free — and the guard
     *  reads the absolute x of the touch down, which clears the floor almost everywhere, with a green
     *  suite. Hence `PagerFlingWiringTest` pinning this class whole. */
    val travelPx: Float get() = state.travelPx

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                state = advanceTravel(
                    previous = state,
                    isPress = event.type == PointerEventType.Press,
                    anyPressed = event.changes.any { it.pressed },
                    x = change.position.x,
                )
            }
        }
    }
}

/** The pager's default fling, with [MIN_FLING_DISTANCE_DP] of finger travel demanded of it first. A
 *  wrapper, deliberately: everything else is left to [PagerDefaults.flingBehavior].
 *    the travel stays at 0 px and NO flick can change message. */
@Composable
internal fun rememberPageFlingBehavior(
    pagerState: PagerState,
    travel: PagerTouchTravel,
): TargetedFlingBehavior {
    val delegate = PagerDefaults.flingBehavior(state = pagerState)
    val minDistancePx = with(LocalDensity.current) { MIN_FLING_DISTANCE_DP.dp.toPx() }
    return remember(delegate, travel, minDistancePx) {
        DistanceGatedFlingBehavior(delegate, travel, minDistancePx)
    }
}

/** [rememberPageFlingBehavior]'s wrapper: filter the velocity, then let the library do the rest. */
private class DistanceGatedFlingBehavior(
    private val delegate: TargetedFlingBehavior,
    private val travel: PagerTouchTravel,
    private val minDistancePx: Float,
) : TargetedFlingBehavior {
    override suspend fun ScrollScope.performFling(
        initialVelocity: Float,
        onRemainingDistanceUpdated: (Float) -> Unit,
    ): Float {
        val allowed = flingVelocityForTravel(
            travelPx = travel.travelPx,
            minDistancePx = minDistancePx,
            velocityPxPerSec = initialVelocity,
        )
        return with(delegate) { performFling(allowed, onRemainingDistanceUpdated) }
    }
}
