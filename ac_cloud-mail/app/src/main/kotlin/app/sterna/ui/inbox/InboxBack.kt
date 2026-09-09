package app.sterna.ui.inbox

/** What the system Back gesture does on the list screen, in priority order. [LEAVE_APP] means no
 *  handler is enabled and the system does its usual thing. */
internal enum class InboxBackAction {
    /** Multi-select is on: back drops the selection. */
    CLEAR_SELECTION,

    /** A message is open in the reading pane beside the list: back empties the pane (#103). */
    CLOSE_DETAIL,

    /** Inline search is open: back closes it and gives the list back (Codeberg #86). */
    CLOSE_SEARCH,

    /** A folder other than the Inbox is open: back returns to the Inbox. */
    SHOW_INBOX,

    /** Nothing left to undo on this screen — the system handles it. */
    LEAVE_APP,
}

/** The single Back rule of the list screen: modes are peeled off innermost first (#103). The
 *  drawer is not part of this — `ModalNavigationDrawer` registers its own handling and wins, under
 *  1 200 dp only; above, it is permanent and never "open". */
internal fun inboxBackAction(
    selectionActive: Boolean,
    detailOpen: Boolean,
    searching: Boolean,
    atInbox: Boolean,
): InboxBackAction = when {
    selectionActive -> InboxBackAction.CLEAR_SELECTION
    detailOpen -> InboxBackAction.CLOSE_DETAIL
    searching -> InboxBackAction.CLOSE_SEARCH
    !atInbox -> InboxBackAction.SHOW_INBOX
    else -> InboxBackAction.LEAVE_APP
}

/** Whether the list is showing the account's Inbox. Structural, never `(sel as? Sel.Folder)?.id
 *  == inboxMailboxId`: on a never-synced inbox that answers `null == null` for any non-folder
 *  selection, and Back would close the app from the unread view. */
internal fun isAtInbox(sel: Sel, inboxMailboxId: () -> String?): Boolean =
    sel is Sel.Unified || (sel is Sel.Folder && sel.id == inboxMailboxId())
