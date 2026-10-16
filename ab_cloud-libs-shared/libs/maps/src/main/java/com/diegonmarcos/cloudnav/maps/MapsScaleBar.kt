package com.diegonmarcos.cloudnav.maps

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * A Google-Maps-style scale bar: a short bar + "500 m" / "2 km" label that
 * appears while the zoom is changing and fades out shortly after the camera
 * settles. MapLibre Native ships no scale bar in its UiSettings and its scalebar
 * plugin is always-on, so this is a tiny Compose canvas driven from
 * `Projection.getMetersPerPixelAtLatitude` through [MapsScaleBarState].
 *
 * [niceScale]/[label] are pure and unit-tested (the only non-trivial logic —
 * choosing a round 1/2/5×10ⁿ distance that fits the max width). Colours/sizes
 * are UI chrome constants, not data.
 */
object MapsScaleBar {

    /** Largest round 1/2/5×10ⁿ metre distance whose on-screen width fits
     *  [maxWidthPx]; returns (metres, pixelWidth). (0,0) for bad input. */
    fun niceScale(metersPerPixel: Double, maxWidthPx: Double): Pair<Double, Double> {
        if (metersPerPixel <= 0.0 || maxWidthPx <= 0.0) return 0.0 to 0.0
        val maxMeters = metersPerPixel * maxWidthPx
        val base = 10.0.pow(floor(log10(maxMeters)))
        val meters = listOf(5.0, 2.0, 1.0).map { it * base }.firstOrNull { it <= maxMeters } ?: base
        return meters to (meters / metersPerPixel)
    }

    /** "500 m" / "2 km" — inputs are always round 1/2/5×10ⁿ. */
    fun label(meters: Double): String = when {
        meters >= 1000.0 -> {
            val km = meters / 1000.0
            "${if (km == floor(km)) km.toInt().toString() else km.toString()} km"
        }
        meters >= 1.0 -> "${meters.toInt()} m"
        else -> "${"%.1f".format(meters)} m"
    }

    /** How long the bar stays after the camera settles. */
    const val HIDE_DELAY_MS = 900L
    private const val FADE_MS = 400
    private const val MAX_WIDTH_DP = 96f
    private const val PAD_DP = 7f

    /** The bar itself; invisible (alpha 0) until [MapsScaleBarState.showNow]. */
    @Composable
    fun Bar(state: MapsScaleBarState, modifier: Modifier = Modifier) {
        val alpha by animateFloatAsState(
            targetValue = if (state.shown) 1f else 0f,
            animationSpec = tween(if (state.shown) 0 else FADE_MS),
            label = "scale-bar-alpha",
        )
        // Fade out after HIDE_DELAY_MS of no scale change; any showNow() restarts the clock.
        LaunchedEffect(state.epoch) {
            if (state.hideArmed) {
                delay(HIDE_DELAY_MS)
                state.shown = false
            }
        }
        if (state.text.isEmpty()) return
        val density = LocalDensity.current
        val width = with(density) { (state.barPx + 2 * PAD_DP.dp.toPx()).toDp() }
        Box(modifier.alpha(alpha).size(width, 30.dp), contentAlignment = Alignment.TopCenter) {
            Canvas(Modifier.fillMaxSize()) {
                val padH = PAD_DP.dp.toPx()
                drawRoundRect(Color(0x88101418), cornerRadius = CornerRadius(4.dp.toPx()))
                val barY = size.height - 9.dp.toPx()
                val left = padH
                val right = padH + state.barPx
                val w = 2.dp.toPx()
                drawLine(Color.White, Offset(left, barY), Offset(right, barY), w)                          // bar
                drawLine(Color.White, Offset(left, barY), Offset(left, barY - 6.dp.toPx()), w)             // left tick
                drawLine(Color.White, Offset(right, barY), Offset(right, barY - 6.dp.toPx()), w)           // right tick
            }
            Text(state.text, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }

    /** The widest the bar may grow, in pixels, at [density]. */
    fun maxWidthPx(density: Float): Double = (MAX_WIDTH_DP * density).toDouble()
}

/** What the scale bar shows right now; written by the map's camera listeners, read by [MapsScaleBar.Bar]. */
class MapsScaleBarState {
    var barPx by mutableFloatStateOf(0f)
    var text by mutableStateOf("")
    var shown by mutableStateOf(false)
    internal var hideArmed by mutableStateOf(false)
    internal var epoch by mutableIntStateOf(0)

    /** Recompute the bar for the current scale (does not change visibility). */
    fun update(metersPerPixel: Double, maxWidthPx: Double) {
        val (meters, px) = MapsScaleBar.niceScale(metersPerPixel, maxWidthPx)
        if (meters <= 0.0) return
        barPx = px.toFloat()
        text = MapsScaleBar.label(meters)
    }

    /** Show immediately (zoom changed), cancelling any pending fade-out. */
    fun showNow() { hideArmed = false; shown = true; epoch++ }

    /** Fade out after [MapsScaleBar.HIDE_DELAY_MS] of no scale change (camera idle). */
    fun scheduleHide() { hideArmed = true; epoch++ }
}
