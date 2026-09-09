package app.sterna.ui.inbox

import kotlin.math.abs

/*
 * How far — or how fast — a list row must be dragged before letting go runs its swipe action (#125).
 * Drag handler and composition call this one decision, so the reveal arms exactly when releasing
 * would act. Everything is in px: the caller converts.
 */

/** The travel a swipe must reach to commit, in dp — absolute, not a share of the row. 72 dp is what
 *  K-9 / Thunderbird commit at, the reference clients #125 names. */
internal const val SWIPE_COMMIT_DISTANCE_DP = 72

/** The release speed above which a swipe commits without having travelled the distance, in dp/s.
 *  120 dp/s is androidx's `item_touch_helper_swipe_escape_velocity`, which K-9 leaves in place. */
internal const val SWIPE_ESCAPE_VELOCITY_DP_PER_SEC = 120

/** Ceiling on the commit distance, as a share of the row width: the drag clamps the offset to
 *  ±rowWidth, so a wider threshold is unreachable and the swipe dies silently on a narrow window. */
private const val SWIPE_COMMIT_MAX_FRACTION = 0.4f

/** The travel that commits a swipe on a row [rowWidthPx] wide. Strictly positive by contract: the
 *  comparison is `>=`, so a threshold of 0 fires an action on every release, a tap included, and an
 *  unmeasured row (before its first layout pass) is 0. */
internal fun swipeCommitThresholdPx(rowWidthPx: Float, distancePx: Float): Float =
    if (rowWidthPx <= 0f) Float.POSITIVE_INFINITY
    else minOf(distancePx, rowWidthPx * SWIPE_COMMIT_MAX_FRACTION)

/**
 * Which way a released drag of [offsetPx] commits: `1` rightwards, `-1` leftwards, `0` neither.
 */
internal fun swipeCommitDirection(
    offsetPx: Float,
    thresholdPx: Float,
    velocityPxPerSec: Float,
    crossVelocityPxPerSec: Float,
    escapeVelocityPxPerSec: Float,
    slopPx: Float,
    dragCompleted: Boolean,
): Int {
    // A cancelled drag is not a release: it still carries the speed of the instant it was snatched.
    if (!dragCompleted) return 0
    // An unmeasured row is given an unreachable threshold; the velocity path would walk around it.
    if (!thresholdPx.isFinite()) return 0

    // 1. Distance, first because it needs nothing else to be true.
    if (offsetPx >= thresholdPx) return 1
    if (-offsetPx >= thresholdPx) return -1

    // 2. Velocity — all four of ItemTouchHelper.checkHorizontalSwipe's conditions, plus a fifth we
    // owe to our own construction. |vx| >= escape alone would be looser than K-9.

    // The side the row was pulled towards. A row still at 0 has none, and that is reachable: the
    // offset bound is 0 on a side with no action, while the tracker reports the finger's speed.
    val pulled = when {
        offsetPx > 0f -> 1
        offsetPx < 0f -> -1
        else -> return 0
    }
    // The floor: our offset discards the travel made while the direction lock is deciding, so a
    // fast twitch that visibly moved nothing would otherwise fire.
    if (abs(offsetPx) < slopPx) return 0
    // The escape velocity itself.
    if (abs(velocityPxPerSec) < escapeVelocityPxPerSec) return 0
    // |vx| > |vy|. The direction lock judged on distances since touch-down, not on the release: a
    // gesture that starts flat and curves down the list lifts off vertically.
    if (abs(velocityPxPerSec) <= abs(crossVelocityPxPerSec)) return 0
    // The sign. Archive right, delete left: a finger pulling right, then whipping back left and
    // lifting, leaves a positive offset with a strongly negative vx, and the delete would fire at
    // no visible travel. The one case here that destroys mail.
    val flung = when {
        velocityPxPerSec > 0f -> 1
        velocityPxPerSec < 0f -> -1
        else -> return 0
    }
    if (flung != pulled) return 0

    return pulled
}
