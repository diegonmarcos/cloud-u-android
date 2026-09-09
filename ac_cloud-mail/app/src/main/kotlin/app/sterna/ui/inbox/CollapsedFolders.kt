package app.sterna.ui.inbox

import app.sterna.core.data.mail.isRoutingTargetByName
import app.sterna.core.jmap.model.Mailbox

/**
 * What a folder nobody has touched does — the default half of the fold decision (#185).
 * calls it its parent. [mailboxes] must be the list the drawer draws. */
internal fun defaultCollapsedFolderIds(mailboxes: List<Mailbox>, rowsBadgeUnread: Boolean): Set<String> {
    if (!rowsBadgeUnread) return emptySet()
    val byId = mailboxes.associateBy { it.id }
    val parents = mailboxes.mapNotNullTo(mutableSetOf()) { folderParentId(it, byId) }
    return mailboxes
        .filter { it.role == null && !isRoutingTargetByName(it) && !it.lostRoleClaim && it.id in parents }
        .mapTo(mutableSetOf()) { it.id }
}

/** Where the registry and the default become the ids [mailboxTree] hides the children of. An
 *  absent key in [choices] means nobody decided, a present one wins both ways: `false` is a folder
 * opened by hand. [rowsBadgeUnread] empties the default, never the registry. */
internal fun collapsedFolderIds(
    mailboxes: List<Mailbox>,
    choices: Map<String, Boolean>,
    rowsBadgeUnread: Boolean,
): Set<String> {
    val decided = choices.filterValues { it }.keys
    val untouched = defaultCollapsedFolderIds(mailboxes, rowsBadgeUnread).filterNot { choices.containsKey(it) }
    return decided + untouched
}

/** The number the drawer badges one folder row with: its own unread when open, plus every
 * descendant's when folded — what is hidden, not the whole subtree. [mailboxes] must be the list
 * the drawer draws. Over-counts in conversation view; on IMAP `unreadForList` is a hard 0. */
internal fun drawerUnreadCount(mailbox: Mailbox, mailboxes: List<Mailbox>, collapsed: Set<String>): Int {
    if (mailbox.id !in collapsed) return mailbox.unreadForList
    val byId = mailboxes.associateBy { it.id }
    val childrenOf = mailboxes.groupBy { folderParentId(it, byId) }
    var total = mailbox.unreadForList
    val visited = mutableSetOf(mailbox.id)
    fun visit(parent: String) {
        childrenOf[parent].orEmpty().forEach { child ->
            if (!visited.add(child.id)) return@forEach // guard against pathological cycles
            total += child.unreadForList
            visit(child.id)
        }
    }
    visit(mailbox.id)
    return total
}
