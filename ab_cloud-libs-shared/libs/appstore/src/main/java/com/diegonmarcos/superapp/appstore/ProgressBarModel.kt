package com.diegonmarcos.superapp.appstore

/**
 * #894 One bar state per item, and it only moves forward.
 *
 * The Store's progress bar used to be redrawn from each [StoreStages.Progress]
 * on its own, so every writer got a vote: a download opens with a 0-byte
 * `Downloading` (bar indeterminate), bytes make it determinate, the next
 * source in the ladder restarts at 0 (bar back to indeterminate or to 0%),
 * `Installing`/`CheckingManifest` carry no percent at all (indeterminate
 * again), and the auto chain's between-packages phase line has no item (percent
 * -1 again). The same bar therefore flipped between its two drawables - the
 * "flashing" the owner saw - several times per app.
 *
 * The rule here: an item's bar is indeterminate ONLY until its first byte
 * count, and determinate from then on, its percent never going down. A
 * different item resets it; a state that names no item (the chain's phase
 * line) does not.
 *
 * Pure Kotlin: no view, no Android, so a JVM test can drive it.
 */
class ProgressBarModel {

    /** What to draw: [indeterminate] ignores [percent]. */
    data class Draw(val indeterminate: Boolean, val percent: Int)

    private var item = ""
    private var determinate = false
    private var percent = 0

    /**
     * Fold one state in and return what the bar should show.
     * [item] is the app the state is about ("" = none named), [bytes] the byte
     * count so far, [percent] the declared 0..100 (< 0 = unknown), [failed] a
     * failure line, which is drawn empty and determinate and starts the next
     * item over.
     */
    @Synchronized
    fun step(item: String, bytes: Long, percent: Int, failed: Boolean): Draw {
        if (failed) { reset(); return Draw(false, 0) }
        if (item.isNotEmpty() && item != this.item) { reset(); this.item = item }
        // A byte count with no known total is still unknown, not a percentage: drawing 0% while
        // bytes flow reads as a stalled transfer (the ambiguity StoreProgressBarTest holds).
        if (percent > 0 || (bytes > 0 && percent >= 0)) {
            determinate = true
            this.percent = maxOf(this.percent, percent.coerceIn(0, 100))
        }
        return Draw(!determinate, this.percent)
    }

    /** A finished batch or a hidden row: the next item starts indeterminate. */
    @Synchronized
    fun reset() { item = ""; determinate = false; percent = 0 }

    /** A message drawn in the bar itself (a batch's outcome): complete, determinate. */
    @Synchronized
    fun complete(): Draw { determinate = true; percent = 100; return Draw(false, 100) }
}
