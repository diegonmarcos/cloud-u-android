package com.diegonmarcos.superapp.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** The short trail drawn under the finger while a shape is being drawn. Never touchable; fades out when the stroke ends. */
class GestureTrailView(context: Context) : View(context) {
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        strokeWidth = 6f * resources.displayMetrics.density; color = 0xCC7FDBFF.toInt()
    }
    private val tail = ArrayDeque<Pair<Float, Float>>()

    init { isClickable = false; isFocusable = false; alpha = 0f }

    /** Only the last [MAX_POINTS] points are kept: a trail, not the whole drawing. */
    fun add(x: Float, y: Float) {
        tail.addLast(x to y)
        while (tail.size > MAX_POINTS) tail.removeFirst()
        path.reset()
        tail.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.first, p.second) else path.lineTo(p.first, p.second) }
        animate().cancel(); alpha = 1f; invalidate()
    }

    fun end() { animate().alpha(0f).setDuration(180).withEndAction { tail.clear(); path.reset() }.start() }

    override fun onDraw(c: Canvas) { c.drawPath(path, paint) }

    private companion object { const val MAX_POINTS = 40 }
}
