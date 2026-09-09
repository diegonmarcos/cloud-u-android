package app.sterna.ui.inbox

/** How a bulk snooze splits: what can be [snooze]d, and what is [refused] — written nowhere,
 *  because writing it would arm a reminder that cannot ring. */
internal data class SelectionSnoozePlan(
    val snooze: List<SelectionTarget>,
    val refused: List<SelectionTarget>,
)

/** Who can be snoozed, decided on one thing: whether the message has a local row. `SnoozeWorker`
 *  resolves the message back through `emails` at the due date, so a row that only ever existed on
 *  screen rings nothing. Such a target is [refused], counted as an attempt and a failure. */
internal fun planSelectionSnooze(targets: List<SelectionTarget>): SelectionSnoozePlan {
    val snooze = mutableListOf<SelectionTarget>()
    val refused = mutableListOf<SelectionTarget>()
    for (target in targets) {
        if (target.folderTrusted) snooze += target else refused += target
    }
    return SelectionSnoozePlan(snooze, refused)
}
