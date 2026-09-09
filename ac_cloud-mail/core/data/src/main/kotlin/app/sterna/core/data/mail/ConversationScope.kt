package app.sterna.core.data.mail

/** Which folders one conversation is counted in, and listed from: the viewed folder(s) plus the
 *  replies the account filed in Sent. The chip's SQL and the row's unfold must agree, so both take
 *  the same resolution as an argument — a second lookup on either side is how they diverge. */
object ConversationScope {

    /** The Sent folders of [accountId] among [sentMailboxes]; a null [accountId] keeps them all.
     *  Account-pinned pairs, never bare ids: same-server accounts carry colliding ids (#92). */
    fun sentFolders(
        sentMailboxes: List<Pair<String, String>>,
        accountId: String?,
    ): List<Pair<String, String>> =
        sentMailboxes.distinct().filter { accountId == null || it.first == accountId }

    /** The folder ids a conversation of [accountId] covers; a resolution that yields nothing leaves
     *  the viewed folders alone, which is honest as long as both sides are given it. */
    fun folders(
        viewedMailboxIds: List<String>,
        sentMailboxes: List<Pair<String, String>>,
        accountId: String?,
    ): Set<String> =
        (viewedMailboxIds + sentFolders(sentMailboxes, accountId).map { it.second }).toSet()
}
