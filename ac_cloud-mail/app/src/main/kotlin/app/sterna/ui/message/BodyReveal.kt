package app.sterna.ui.message

import android.view.KeyEvent

/** What one tick of the body height poll decides (see [BodyReveal.step]). */
internal sealed interface HeightPoll {
    /** The body's laid-out height is [px]: report it and stop polling. [settled] is `true` when two
     *  consecutive readings agreed and `false` when the poll fell back to a guess — only a settled
     *  height may decide the bottom bar (see [BodyReveal.barVisible]). */
    data class Report(val px: Int, val settled: Boolean) : HeightPoll

    /** Nothing conclusive yet — poll again. */
    data object Retry : HeightPoll
}

/**
 * One report of the body's scroll geometry: how far it is scrolled and the RAW numbers it was read
 */
internal data class BodyMetrics(
    val scrollY: Int,
    val rangePx: Int,
    val viewportPx: Int,
    val contentHeightPx: Int,
) {
    /** How far this body can scroll: see [BodyReveal.maxScroll]. */
    val maxScrollPx: Int get() = BodyReveal.maxScroll(rangePx, viewportPx)

    /** Whether this report measured anything at all: see [BodyReveal.rangeMeasured]. */
    val measured: Boolean get() = BodyReveal.rangeMeasured(rangePx, viewportPx, contentHeightPx)
}

/**
 * Everything the reader needs to decide the Reply/Forward bar, carried forward one report at a time.
 */
internal data class BarState(
    /** Whether the bar belongs on screen. */
    val shown: Boolean = false,
    /** The tallest scroll range reported for this body so far. A measured range only ever grows. */
    val maxScrollPx: Int = 0,
    /** The scroll offset the last report carried: a report that repeats it moved nothing. */
    val scrollY: Int = 0,
)

/**
 * What one tick of the post-gesture resize probe decides (see [BodyReveal.resizeStep]). The document
 */
internal sealed interface ResizeProbe {
    /** The height changed: report the geometry now and stop. */
    data object Report : ResizeProbe

    /** Unchanged so far — the relayout does not land in the same frame as the tap. Poll again. */
    data object Retry : ResizeProbe

    /** Out of ticks with the height unchanged: stop, and report NOTHING. A tap that opened nothing is
     *  the common case, and reporting "nothing moved" would re-found the bar's remembered range on an
     *  arbitrary instant of a body that may still be laying out. */
    data object Done : ResizeProbe
}

/**
 * The message body is drawn by a WebView the reader keeps INVISIBLE until it has reported a laid-out
 */
internal object BodyReveal {
    /**
     * One tick of the poll. [px] is this tick's reading of the content range, [last] the previous one,
     */
    fun step(px: Int, last: Int, maxSeen: Int, triesLeft: Int, viewHeightPx: Int): HeightPoll {
        if (px > 0 && px == last) return HeightPoll.Report(px, settled = true)
        if (triesLeft <= 0) return HeightPoll.Report(maxOf(maxSeen, viewHeightPx, 1), settled = false)
        return HeightPoll.Retry
    }

    /**
     * How far a body of [contentRangePx] can scroll inside a [viewportPx]-tall window. Zero when it
     * fits: there is nothing to scroll, so the body is already at its end.
     */
    fun maxScroll(contentRangePx: Int, viewportPx: Int): Int =
        (contentRangePx - viewportPx).coerceAtLeast(0)

    /**
     * Whether a reading of the content range MEASURED anything, or merely read the floor.
     */
    fun rangeMeasured(rangePx: Int, viewportPx: Int, contentHeightPx: Int): Boolean =
        rangePx > viewportPx || (rangePx > 0 && contentHeightPx > 0)

    /**
     * Whether the Reply/Forward bar belongs on screen for a body scrolled to [scrollY] out of
     */
    fun barVisible(scrollY: Int, maxScrollPx: Int, thresholdPx: Int): Boolean =
        maxScrollPx <= thresholdPx || scrollY >= maxScrollPx - thresholdPx

    /**
     * Fold one report — a resting measurement or a live scroll — into the bar's [BarState].
     */
    fun barAfterReport(prev: BarState, scrollY: Int, maxScrollPx: Int, thresholdPx: Int): BarState {
        val range = maxOf(prev.maxScrollPx, maxScrollPx)
        val readerMoved = scrollY != prev.scrollY
        val wants = barVisible(scrollY, range, thresholdPx)
        return BarState(
            shown = if (prev.shown && !readerMoved) true else wants,
            maxScrollPx = range,
            scrollY = scrollY,
        )
    }

    /**
     * Fold in a report about a body that changed size UNDER THE READER'S OWN GESTURE — the same
     */
    fun barAfterResize(prev: BarState, scrollY: Int, maxScrollPx: Int, thresholdPx: Int): BarState {
        val shrank = maxScrollPx < prev.maxScrollPx
        val from = if (shrank) BarState() else prev
        return barAfterReport(from, scrollY, maxScrollPx, thresholdPx)
    }

    /**
     * One tick of the resize probe: [now] is this tick's reading, [atGesture] the reading taken when
     */
    fun resizeStep(now: Int, atGesture: Int, last: Int, triesLeft: Int): ResizeProbe {
        if (now != atGesture && now == last) return ResizeProbe.Report
        if (triesLeft <= 0) return ResizeProbe.Done
        return ResizeProbe.Retry
    }

    /**
     * Whether a key event should arm the resize probe: [action], [keyCode] and [repeatCount] are raw.
     */
    fun keyArmsResizeProbe(action: Int, keyCode: Int, repeatCount: Int): Boolean =
        (action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP) &&
            repeatCount == 0 &&
            keyCode in ACTIVATION_KEYS

    /** The keys that activate a focused `<summary>`: what a hardware keyboard or a D-pad uses to
     *  open (or close) the fold. Run key by key by `KeyArmsResizeProbeTest`. */
    private val ACTIVATION_KEYS = setOf(
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_SPACE,
    )
}

/**
 * The reader's running [BarState] for one body, and the events that feed it.
 */
internal class BarReveal {
    /** Whether the bar belongs on screen after every report folded in so far. */
    var shown: Boolean = false
        private set

    /** Everything folded in so far. Exposed for tests; the reader only ever reads [shown]. */
    var state: BarState = BarState()
        private set

    /** The body's height poll finished. [resting] is the geometry it measured, null when it capped out
     *  and fell back to a guess: a fallback is not a measurement, so it decides nothing and the settle
     *  poll has the last word. A SETTLED height can be just as empty — two agreeing readings of the
     *  pre-layout floor — and is inert for the same reason. */
    fun bodyReady(resting: BodyMetrics?, thresholdPx: Int): Boolean {
        if (resting == null || !resting.measured) return shown
        return fold(resting.scrollY, resting.maxScrollPx, thresholdPx)
    }

    /**
     * A live scroll report: the reader moved, or the load's settle poll fired its one terminal report.
     */
    fun scrolled(m: BodyMetrics, thresholdPx: Int, lastWord: Boolean = false): Boolean {
        if (!m.measured && !lastWord) return shown
        return fold(m.scrollY, m.maxScrollPx, thresholdPx)
    }

    /**
     * The document was REPLACED — the reader switched between the message's HTML and its text (#149) —
     */
    fun documentReplaced() {
        state = BarState()
        shown = false
    }

    /**
     * The body RESIZED under the reader's gesture and the resize probe caught the new geometry. The
     */
    fun resized(m: BodyMetrics, thresholdPx: Int): Boolean {
        if (!m.measured) return shown
        state = BodyReveal.barAfterResize(state, m.scrollY, m.maxScrollPx, thresholdPx)
        shown = state.shown
        return shown
    }

    private fun fold(scrollY: Int, maxScrollPx: Int, thresholdPx: Int): Boolean {
        state = BodyReveal.barAfterReport(state, scrollY, maxScrollPx, thresholdPx)
        shown = state.shown
        return shown
    }
}
