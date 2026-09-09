package app.sterna.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sterna.ui.rememberMotionEnabled

/**
 * The centred "this screen is still loading" ring.
 */
@Composable
fun LoadingRing(
    modifier: Modifier = Modifier,
    strokeWidth: Dp = ProgressIndicatorDefaults.CircularStrokeWidth,
) {
    val rest = loadingRingProgress(rememberMotionEnabled())
    if (rest == null) {
        CircularProgressIndicator(modifier = modifier, strokeWidth = strokeWidth)
    } else {
        // A closed ring, and nothing else: the determinate indicator's own defaults draw a grey
        // track behind it and hold a notch open between the ends, so at a full turn it would rest
        // as a circle with a gap bitten out of it.
        //
        // The wrapping Box is not decoration — it is what the ring ANNOUNCES. The determinate
        // overload publishes ProgressBarRangeInfo(1f, …) of its own accord, so a screen reader
        //
        // SIZE: the caller's modifier goes on the Box, and Material 3's own `.size(40.dp)` on the
        // indicator is enforced against the incoming constraints, so it lands wherever the caller
        // asked. No explicit size is forced here: doing so would either freeze the default or
        // stretch the ring for a caller passing fill constraints.
        Box(
            modifier.clearAndSetSemantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                progress = { rest },
                strokeWidth = strokeWidth,
                trackColor = Color.Transparent,
                strokeCap = StrokeCap.Butt,
                gapSize = 0.dp,
            )
        }
    }
}

/** The turn a resting ring is drawn at: the WHOLE circle, never a fragment of one. */
internal const val LOADING_RING_REST_TURN = 1f

/**
 * How much of the ring to draw, or `null` to let Material 3 run its own indeterminate animation.
 */
internal fun loadingRingProgress(motionOn: Boolean): Float? =
    if (motionOn) null else LOADING_RING_REST_TURN
