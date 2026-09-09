package app.sterna.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import app.sterna.ui.rememberMotionEnabled

/**
 * Sterna's signature pull-to-refresh: the launcher-icon tern (a filled teal
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TernRefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    val motionOn = rememberMotionEnabled()
    val color = MaterialTheme.colorScheme.primary

    val pull = state.distanceFraction.coerceIn(0f, 1f)
    val appear = if (isRefreshing) 1f else pull
    val spread = if (isRefreshing) 1f else pull

    // Wing-beat: oscillate while refreshing, hold mid-glide otherwise (and always
    // when the user has asked for reduced motion).
    val flap = if (isRefreshing && motionOn) {
        val t = rememberInfiniteTransition(label = "tern")
        t.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(560, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "flap",
        ).value
    } else {
        0.5f
    }

    val disc = MaterialTheme.colorScheme.surface
    Canvas(
        modifier
            .padding(top = 8.dp)
            .size(54.dp)
            .graphicsLayer {
                alpha = appear
                val sc = 0.6f + 0.4f * appear
                scaleX = sc
                scaleY = sc
            },
    ) {
        // Soft surface-coloured disc (radial fade to transparent) so the tern is cut
        // cleanly from the list text behind it — theme-aware, not a baked white.
        val r = size.minDimension / 2f
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to disc.copy(alpha = 0.94f),
                0.72f to disc.copy(alpha = 0.94f),
                1.0f to disc.copy(alpha = 0f),
                center = center,
                radius = r,
            ),
            radius = r,
        )
        scale(1.0f, pivot = center) {
            drawTern(spread = spread, flap = flap, color = color)
        }
    }
}

// The launcher-icon tern as fillable paths in a 108×108 design space (the exact
// pathData from res/drawable/splash_tern.xml). Parsed once; body is static, the two
// wings pivot at the shoulders so they can fold (pull) and beat (refresh).
private val ternBody = PathParser().parsePathString("M49,58 L50,73 L54,63 L58,73 L59,58 L54,49 Z").toPath()
private val ternLeftWing = PathParser().parsePathString("M54,49 C42,45 32,43 20,54 C32,52 43,54 50,57 Z").toPath()
private val ternRightWing = PathParser().parsePathString("M54,49 C66,45 76,43 88,54 C76,52 65,54 58,57 Z").toPath()

/** Draws the filled icon tern; [spread] 0..1 folds→opens the wings, [flap] 0..1 beats
 *  them (0.5 = level glide). Shared by the refresh + send-flight motions. */
internal fun DrawScope.drawTern(spread: Float, flap: Float, color: Color) {
    val k = size.minDimension / 108f
    val ox = (size.width - 108f * k) / 2f
    val oy = (size.height - 108f * k) / 2f
    // Wings fold up at rest and open out as you pull; during refresh they beat.
    val fold = lerp(24f, 0f, spread)
    val beat = (flap - 0.5f) * 26f
    val swing = fold + beat
    translate(ox, oy) {
        scale(k, k, pivot = Offset.Zero) {
            drawPath(ternBody, color)
            rotate(-swing, pivot = Offset(51f, 54f)) { drawPath(ternLeftWing, color) }
            rotate(swing, pivot = Offset(57f, 54f)) { drawPath(ternRightWing, color) }
        }
    }
}
