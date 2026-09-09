package app.sterna.core.data.mail

/** Whether deleting a message destroys it rather than moving it to the bin: is it already in the
 *  Trash? An absent Trash answers false, not true — `delete()` creates the folder when nothing
 *  below. The two guards are redundant on purpose: no single test tells them apart. */
fun deleteDestroysMessage(trashMailboxId: String?, messageMailboxId: String?): Boolean {
    if (trashMailboxId.isNullOrBlank()) return false
    if (messageMailboxId.isNullOrBlank()) return false
    return messageMailboxId == trashMailboxId
}
