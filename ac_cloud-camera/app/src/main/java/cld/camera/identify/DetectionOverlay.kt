package cld.camera.identify

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.diegonmarcos.superapp.image.mlkit.Recognition

/**
 * #798 the boxes of the latest detection drawn over the viewfinder: a frame per object with its
 * label, confidence and tracking id. A tap on a box hands it to [onBox] (the details sheet).
 */
@SuppressLint("ViewConstructor")
class DetectionOverlay(context: Context, private val onBox: (Recognition.Box) -> Unit) : View(context) {
    private val density = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * density; color = Color.YELLOW }
    private val fill = Paint().apply { color = 0xAA000000.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14 * density }

    private var boxes: List<Recognition.Box> = emptyList()
    private var frameW = 0
    private var frameH = 0
    private var rects: List<FloatArray> = emptyList()

    fun show(r: Recognition) {
        boxes = r.boxes
        frameW = r.width
        frameH = r.height
        invalidate()
    }

    fun clear() = show(Recognition.failed("", ""))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        rects = boxes.map { BoxMapping.toView(it.x, it.y, it.w, it.h, frameW, frameH, width, height) }
        boxes.forEachIndexed { i, b ->
            val r = rects[i]
            canvas.drawRect(r[0], r[1], r[2], r[3], stroke)
            val caption = caption(b)
            val tw = text.measureText(caption)
            val top = maxOf(0f, r[1] - text.textSize - 6 * density)
            canvas.drawRect(r[0], top, r[0] + tw + 8 * density, top + text.textSize + 6 * density, fill)
            canvas.drawText(caption, r[0] + 4 * density, top + text.textSize, text)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return boxes.isNotEmpty()
        val i = BoxMapping.hit(rects, event.x, event.y)
        if (i >= 0 && i < boxes.size) onBox(boxes[i])
        return i >= 0
    }

    companion object {
        /** "suit 70% #7" — the label, its confidence, and the tracking id when the stream has one. */
        fun caption(b: Recognition.Box): String =
            "${b.label} ${Math.round(b.p * 100)}%" + (b.id?.let { " #$it" } ?: "")
    }
}
