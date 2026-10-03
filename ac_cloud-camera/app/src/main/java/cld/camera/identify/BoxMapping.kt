package cld.camera.identify

/**
 * #798 where a detected box lands on screen. The engine answers in the pixels of the frame it was
 * handed (sized to recognition.json's live_side); the viewfinder shows the same camera stream
 * through PreviewView's FILL_CENTER: scaled to COVER the view, centred, the overflow cropped.
 * Preview and analysis are bound with the same 4:3 strategy, so one mapping serves both.
 */
object BoxMapping {
    /** [x, y, w, h] in a [frameW]×[frameH] frame → [left, top, right, bottom] in a [viewW]×[viewH] view. */
    fun toView(x: Int, y: Int, w: Int, h: Int, frameW: Int, frameH: Int, viewW: Int, viewH: Int): FloatArray {
        if (frameW <= 0 || frameH <= 0 || viewW <= 0 || viewH <= 0) return floatArrayOf(0f, 0f, 0f, 0f)
        val scale = maxOf(viewW.toFloat() / frameW, viewH.toFloat() / frameH)
        val dx = (viewW - frameW * scale) / 2f
        val dy = (viewH - frameH * scale) / 2f
        return floatArrayOf(dx + x * scale, dy + y * scale, dx + (x + w) * scale, dy + (y + h) * scale)
    }

    /** The index of the smallest box under ([px], [py]) — the innermost wins a tap — or -1. */
    fun hit(rects: List<FloatArray>, px: Float, py: Float): Int =
        rects.indices.filter { val r = rects[it]; px >= r[0] && px <= r[2] && py >= r[1] && py <= r[3] }
            .minByOrNull { val r = rects[it]; (r[2] - r[0]) * (r[3] - r[1]) } ?: -1
}
