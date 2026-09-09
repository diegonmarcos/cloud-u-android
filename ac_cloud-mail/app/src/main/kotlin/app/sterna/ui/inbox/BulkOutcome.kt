package app.sterna.ui.inbox

/** What a finished bulk action has to tell the user. */
internal enum class BulkOutcome {
    /** Everything the batches were given went through — say nothing. */
    NONE,

    /** Some ids went through and some did not. */
    PARTIAL,

    /** Nothing went through. */
    TOTAL,
}

/** Which of the three a bulk action landed on. [attempted] is the selection size, not the rows
 *  the cache returned, and unresolved keys count on both sides: as failures alone they would say
 *  "some of it failed" over an action that reached everything. */
internal fun bulkOutcome(attempted: Int, failed: Int): BulkOutcome = when {
    attempted <= 0 || failed <= 0 -> BulkOutcome.NONE
    failed >= attempted -> BulkOutcome.TOTAL
    else -> BulkOutcome.PARTIAL
}
