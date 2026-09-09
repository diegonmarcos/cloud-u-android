package app.sterna.ui

/**
 * Which folder the list behind should show when a new-mail notification is opened — the folder half
 */
object NotificationFolderSwitch {

    /**
     * The folder to show, or null to leave the list where it is. Null in four cases: the
     */
    /*
     * A FIFTH case, judged by the caller and not here: a folder the account's "show subscribed
     * folders only" setting hides (#174). The answer below is a folder id, and whether one may be
     */
    fun resolve(
        notificationMailboxId: String?,
        selectedMailboxId: String?,
        unifiedView: Boolean,
        knownMailboxIds: Collection<String>,
    ): String? {
        val target = notificationMailboxId?.takeIf { it.isNotBlank() } ?: return null
        if (unifiedView) return null
        if (target == selectedMailboxId) return null
        if (target !in knownMailboxIds) return null
        return target
    }
}
