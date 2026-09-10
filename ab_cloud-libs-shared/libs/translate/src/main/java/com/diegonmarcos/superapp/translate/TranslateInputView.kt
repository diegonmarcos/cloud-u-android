package com.diegonmarcos.superapp.translate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView

/**
 * The translate bar's own text field.
 *
 * It cannot be an EditText: the on-screen keyboard only ever commits to the host
 * app's InputConnection, never to a child view of the IME's own window, so no view
 * in here can receive key input by holding focus (same constraint documented in
 * EmojiSearchBarView). [TranslateBarView] therefore owns the text buffer and routes
 * keys into it by hand, and this view's only extra job is to draw the caret that an
 * unfocused TextView will not draw for us — Editor only blinks a cursor for a
 * focused view, so a plain `isCursorVisible = true` would render nothing.
 *
 * Selection is drawn by the caller as a BackgroundColorSpan; [caret] is set to -1
 * whenever a selection is showing, since a range and an insertion point are
 * mutually exclusive.
 */
class TranslateInputView(context: Context) : TextView(context) {

    private val caretPaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 1.5f, resources.displayMetrics)
    }

    /** Buffer offset to draw the caret at, or -1 to draw none (empty buffer / active selection). */
    var caret: Int = -1
        set(value) { if (field != value) { field = value; invalidate() } }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val l = layout ?: return
        val at = caret
        if (at < 0 || at > text.length) return
        val line = l.getLineForOffset(at)
        val x = l.getPrimaryHorizontal(at) + totalPaddingLeft
        canvas.drawLine(
            x, (l.getLineTop(line) + totalPaddingTop).toFloat(),
            x, (l.getLineBottom(line) + totalPaddingTop).toFloat(),
            caretPaint)
    }

    /** Buffer offset under a touch point, clamped into the text (-1 before the first layout pass). */
    fun offsetAt(x: Float, y: Float): Int =
        if (layout == null) text.length else getOffsetForPosition(x, y).coerceIn(0, text.length)

    /**
     * Scroll the caret's line into view when this box is inside a [CappedScrollView].
     *
     * Nothing else will do it: an unfocused TextView gets no scrolling from the
     * platform, so once the text passes the box's cap the caret keeps moving
     * outside the window and the user is typing blind at text they cannot see.
     *
     * Call it from a `post` — the layout still describes the PREVIOUS text at the
     * moment the caller sets a new one, and a stale layout would scroll to the
     * wrong line. A null layout here means the measure pass has not run yet, so
     * there is nothing off-screen to reveal and the next caret move will do it.
     */
    fun revealCaret() {
        val scroller = parent as? ScrollView ?: return
        val l = layout ?: return
        val at = caret
        if (at < 0 || at > text.length) return
        val line = l.getLineForOffset(at)
        val top = l.getLineTop(line) + totalPaddingTop
        val bottom = l.getLineBottom(line) + totalPaddingTop
        if (top < scroller.scrollY) scroller.smoothScrollTo(0, top)
        else if (bottom > scroller.scrollY + scroller.height)
            scroller.smoothScrollTo(0, bottom - scroller.height)
    }
}

/**
 * A ScrollView that grows with its content up to [maxHeight] and scrolls past it.
 *
 * The partner of [TranslateInputView]: a bar's text box has to stay small enough to
 * leave the keys visible, and capping it with `maxLines` alone makes every line past
 * the cap unreachable rather than merely off-screen. Shared rather than copied —
 * libs:keyboard already depends on this module, so EnhanceBarView uses this one too.
 */
class CappedScrollView(context: Context, private val maxHeight: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val capped = if (mode == MeasureSpec.UNSPECIFIED || size > maxHeight)
            MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST) else heightMeasureSpec
        super.onMeasure(widthMeasureSpec, capped)
    }
}
