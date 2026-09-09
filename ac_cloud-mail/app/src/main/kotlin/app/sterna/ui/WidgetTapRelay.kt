package app.sterna.ui

/** What a widget tap order must do in the composition currently on hand. Three arms, and no fourth. */
enum class WidgetTapStep {

    /** Nothing is pending. Do not consume anything, do not touch the list. */
    Ignore,

    /** The order is real but the list is not composed yet: take it from the activity and keep it. */
    Hold,

    /** The list exists: consume, drop what is held, and put the list on the unified inbox. */
    Deliver,
}

/**
 * Whether a home-screen widget tap (#112) can be carried out in THIS composition, or must be kept
 */
object WidgetTapRelay {

    /**
     * [ordered] is the activity's one-shot flag, [held] this host's own copy of an order already
     */
    fun step(ordered: Boolean, held: Boolean, listReady: Boolean): WidgetTapStep = when {
        // Neither a fresh order nor one already taken: the widget was not touched.
        !ordered && !held -> WidgetTapStep.Ignore
        // The list exists — deliver, whichever of the two flags carries the order.
        listReady -> WidgetTapStep.Deliver
        // An order with no list to give it to: keep it rather than drop it. The caller consumes the
        // activity's flag in this same step, which is what brings the next composition.
        else -> WidgetTapStep.Hold
    }
}
