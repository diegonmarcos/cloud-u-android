package app.sterna.ui.inbox

import androidx.annotation.PluralsRes

/** One message a bulk action actually wrote, and where it put it ([destMailboxId], null when
 *  nothing moved). It carries the [SelectionTarget], not a bare row: whether the folder can be
 *  believed is what decides if an Undo may be offered at all. */
internal data class UndoCandidate(val target: SelectionTarget, val destMailboxId: String?)

/** The Undo entries a finished batch may offer. Undo overwrites `mailboxIds`, so the source must be
 * the folder the message really was in, which an untrusted row cannot supply. One untrusted row
 *  withholds the whole batch's Undo: putting back four of ten archived messages is a lie. */
internal fun selectionUndoEntries(succeeded: List<UndoCandidate>): List<UndoEntry> {
    if (succeeded.any { !it.target.folderTrusted }) return emptyList()
    return succeeded.mapNotNull { candidate ->
        val email = candidate.target.email
        val source = email.mailboxId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        UndoEntry(email.id, email.accountId, source, candidate.destMailboxId)
    }
}

/** What a batch's success banner announces, or null when there is no banner. */
internal data class SelectionBanner(@PluralsRes val labelRes: Int, val count: Int)

/** The success banner a finished batch shows, or null when it shows none. The count is [wrote],
 *  never [entries]: [selectionUndoEntries] empties as soon as one row is untrusted while those
 *  messages left the folder all the same. */
internal fun selectionBanner(
    @PluralsRes labelRes: Int?,
    wrote: List<UndoCandidate>,
    entries: List<UndoEntry>,
): SelectionBanner? {
    if (labelRes == null || entries.isEmpty()) return null
    return SelectionBanner(labelRes, wrote.size)
}
