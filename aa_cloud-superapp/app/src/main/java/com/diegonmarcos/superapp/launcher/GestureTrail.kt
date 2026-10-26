package com.diegonmarcos.superapp.launcher

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

/** The short trail drawn under the finger while a shape is being drawn: only the last [MAX_POINTS] points, fading out when the stroke ends. */
class GestureTrailState {
    val points = mutableStateListOf<Offset>()
    var visible by mutableStateOf(false)
        private set

    fun add(x: Float, y: Float) {
        points.add(Offset(x, y))
        while (points.size > MAX_POINTS) points.removeAt(0)
        visible = true
    }

    fun end() { visible = false }

    /** A new stroke starts from nothing. */
    fun begin() { points.clear(); visible = false }

    companion object { const val MAX_POINTS = 40 }
}

/** Compose overlay for [GestureTrailState]; it takes no pointer input, so every touch falls through to the page below. */
@Composable
fun GestureTrail(state: GestureTrailState, strokeWidthPx: Float) {
    val alpha by animateFloatAsState(if (state.visible) 1f else 0f, label = "gesture-trail")
    Canvas(Modifier.fillMaxSize()) {
        if (state.points.size < 2 || alpha <= 0f) return@Canvas
        val path = Path()
        state.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
        drawPath(path, Color(0xFF7FDBFF).copy(alpha = 0.8f * alpha),
            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
