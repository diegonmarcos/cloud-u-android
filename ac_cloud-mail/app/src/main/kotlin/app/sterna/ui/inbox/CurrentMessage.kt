package app.sterna.ui.inbox

/** The message the reading pane shows, as the same quintuplet the `message/…` route carries (#103):
 *  under 600 dp the inbox turns it into that route ([toRoute]), from 600 dp into the pane. */
data class MessageAnchor(
    val emailId: String,
    val accountId: String?,
    val src: String?,
    val index: Int,
    val thread: String?,
) {
    /** [encode] is `Uri::encode` on Android and the identity in a JVM test, which pins the text. */
    fun toRoute(encode: (String) -> String): String {
        val base = "message/${encode(emailId)}?accountId=${encode(accountId.orEmpty())}"
        return when (src) {
            "list", "search" -> "$base&index=$index&src=$src"
            "thread" -> "$base&index=$index&src=thread&thread=${encode(thread.orEmpty())}"
            else -> base
        }
    }

    /** Account-qualified, like every per-row lookup: two accounts of one server list a message
     *  under the same id (#92), and that id under another account is not this message. */
    fun matches(accountId: String?, emailId: String): Boolean =
        this.emailId == emailId && this.accountId == accountId

    companion object {
        /** From the route's arguments, where a blank `accountId` / `thread` stands for none. */
        fun fromRoute(emailId: String, accountId: String?, index: Int, src: String?, thread: String?): MessageAnchor =
            MessageAnchor(emailId, accountId?.ifBlank { null }, src, index, thread?.ifBlank { null })
    }
}

/** What the reading pane shows, and the [session] it is shown in — the pane's recomposition key: a
 *  tap on another row rebuilds the pager, a swipe moves the anchor and keeps the session, since
 *  rebuilding reloads every body on every page. */
data class ReadingPaneState(val anchor: MessageAnchor? = null, val session: Int = 0)

/** The transitions of [ReadingPaneState], pure so a JVM test runs them (#103). */
object ReadingPaneRule {
    /** A tap: the same message already open is a no-op; another message starts a new session. */
    fun open(prev: ReadingPaneState, anchor: MessageAnchor): ReadingPaneState =
        if (prev.anchor?.matches(anchor.accountId, anchor.emailId) == true) prev
        else ReadingPaneState(anchor = anchor, session = prev.session + 1)

    /** The pager settled on a page: the anchor follows the message under the finger, keeping its
     *  `src` / `thread` and — deliberately — its session. */
    fun follow(prev: ReadingPaneState, emailId: String, accountId: String?, index: Int): ReadingPaneState {
        val anchor = prev.anchor ?: return prev
        if (anchor.matches(accountId, emailId)) return prev
        return prev.copy(anchor = anchor.copy(emailId = emailId, accountId = accountId, index = index))
    }

    /** Back, or an action that takes the message out of the folder: the pane empties. */
    fun close(prev: ReadingPaneState): ReadingPaneState =
        if (prev.anchor == null) prev else prev.copy(anchor = null)

    /** The list changed scope — folder, unified, unread, or account: the pane empties (#112).
     * No per-account exception: sparing the anchor because it belongs to the arriving account
     *  keeps, in the unified inbox, a message in no folder the new list shows. */
    fun onViewChanged(prev: ReadingPaneState): ReadingPaneState = close(prev)

    /** The folder a tapped notification asks the list to show (#91). It lands a beat before or
     *  after the message it posted and nothing here may depend on which, so the question is "is the
     * pane's message my message?", account-qualified (#92). Never a flat "keep". */
    fun onFolderFromNotification(prev: ReadingPaneState, emailId: String, accountId: String?): ReadingPaneState =
        if (prev.anchor?.matches(accountId, emailId) == true) prev else close(prev)

    /** The message a tapped notification parked for the account switch it asked for: put back on
     *  its own account, dropped on any other — a park that outlived its switch would otherwise land
     *  on a stranger's list (#92). */
    fun replayOnAccountChange(parked: MessageAnchor?, arrivingAccountId: String?): MessageAnchor? =
        if (parked?.accountId == arrivingAccountId) parked else null

    /** The anchor read back after process death loses its paging context: the list restarts on its
     *  first page, so a kept index would open another message and mark it read (`SECURITY.md`). */
    fun restored(anchor: MessageAnchor): MessageAnchor = anchor.copy(src = null, index = 0, thread = null)
}
