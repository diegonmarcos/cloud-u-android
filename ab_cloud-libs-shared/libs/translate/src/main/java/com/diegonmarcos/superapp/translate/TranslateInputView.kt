package com.diegonmarcos.superapp.translate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

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
 *
 * BIDIRECTIONAL TEXT, stated because this is a TRANSLATION box and Arabic, Hebrew
 * and Persian land in it. What works: the platform lays out the runs, so RTL text
 * READS correctly; [offsetAt] goes through getOffsetForPosition, which is
 * direction-aware, so a tap resolves to the offset under the finger even in a
 * mixed-direction line; and a selection spanning a direction change is drawn as
 * the two visual runs it really is, because Layout draws the span per run.
 * What does NOT: caret MOVEMENT is in logical order, not visual order. Pressing
 * right at the boundary between an English word and an Arabic one moves the caret
 * to the next character in the string, which on screen can be a jump to the other
 * side of the run. The platform's own answer is Layout.getOffsetToLeftOf /
 * getOffsetToRightOf, which would mean the caret step asking the VIEW instead of
 * the buffer — a real change, and one nobody here can watch on a screen.
 *
 * ponytail: logical-order caret movement, visual-order via Layout.getOffsetToLeftOf
 * when someone with an RTL keyboard and a device can confirm it feels right.
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
     * Every touch gesture a text field owes the user, wired to [editor] — tap places
     * the caret, double-tap selects the word, drag extends the selection, long press
     * selects the word and opens the edit menu.
     *
     * All of it is reimplemented because an unfocused TextView provides none of it,
     * and it lives HERE, once, rather than in each bar: the copy in each bar is how
     * the double tap came to be missing from both of them and how the next gesture
     * would have been added to only one.
     *
     * [onClaim] runs on the first touch down — the enhance bar uses it to take the
     * keys, which its box only owns once tapped. [onChange] re-renders. [onMenu]
     * shows the bar's own edit menu after a long press.
     */
    fun attachEditing(
        editor: TextBoxEditor,
        onClaim: () -> Unit,
        onChange: () -> Unit,
        onMenu: () -> Unit,
    ) {
        isClickable = true
        setOnTouchListener(object : View.OnTouchListener {
            private val ui = Handler(Looper.getMainLooper())
            private var anchor = 0
            private var downX = 0f
            private var downY = 0f
            private var dragging = false
            private var lastUpAt = 0L
            private var lastUpOffset = -1
            private val longPress = Runnable { editor.selectWordAt(anchor); onChange(); onMenu() }

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                if (editor.isEmpty) return false
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        onClaim()
                        anchor = offsetAt(e.x, e.y); downX = e.x; downY = e.y; dragging = false
                        // A second tap in the same place, soon enough, is the platform's
                        // select-the-word gesture; the timeout and the offset both have
                        // to match, or a fast typist tapping twice in two places gets a
                        // selection they did not ask for.
                        val quick = e.eventTime - lastUpAt <= ViewConfiguration.getDoubleTapTimeout()
                        if (quick && anchor == lastUpOffset) {
                            editor.selectWordAt(anchor)
                            lastUpAt = 0L
                        } else {
                            editor.setCaret(anchor)
                            ui.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                        }
                        onChange()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val slop = ViewConfiguration.get(context).scaledTouchSlop
                        if (!dragging && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) {
                            dragging = true; ui.removeCallbacks(longPress)
                        }
                        if (dragging) { editor.select(anchor, offsetAt(e.x, e.y)); onChange() }
                    }
                    MotionEvent.ACTION_UP -> {
                        ui.removeCallbacks(longPress)
                        // A drag ended a selection; only a stationary tap can begin a
                        // double tap, or the end of a drag would start one.
                        lastUpAt = if (dragging) 0L else e.eventTime
                        lastUpOffset = anchor
                        v.performClick()
                    }
                    MotionEvent.ACTION_CANCEL -> ui.removeCallbacks(longPress)
                }
                return true
            }
        })
    }

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
