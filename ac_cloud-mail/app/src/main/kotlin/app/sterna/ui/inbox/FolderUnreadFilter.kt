package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Mailbox

/** The two tabs above the drawer's folder list (#247). [ALL] is every folder the account has. */
internal enum class FolderTab { ALL, UNREAD }

/**
 * The folders the `Unread` tab lists: those carrying unread mail, PLUS every ancestor of one.
 *
 * The ancestors are not decoration. [mailboxTree] builds the tree by looking each folder's parent up
 * in the list it was handed, so a parent dropped here does not merely disappear — it takes its whole
 * subtree with it, and a folder with 200 unread would vanish from the tab that exists to show it.
 * Keeping the ancestors is therefore the only rule under which the tab can show what it promises;
 * that a parent may appear with no unread of its own is the honest half of that trade, and it reads
 * correctly, because the drawer already badges a folded parent with what its descendants hold (see
 * [drawerUnreadCount]).
 *
 * Counts come from [Mailbox.unreadForList], which is what the rows badge — so the tab can never
 * disagree with the number printed beside a name.
 */
internal fun foldersWithUnread(mailboxes: List<Mailbox>): List<Mailbox> {
    val byId = mailboxes.associateBy { it.id }
    val keep = mutableSetOf<String>()
    mailboxes.filter { it.unreadForList > 0 }.forEach { unread ->
        keep += unread.id
        // Walk up until the chain leaves the list or reaches something already kept — which is both
        // the "its ancestors are in already" shortcut and the guard against a pathological cycle.
        var parentId = folderParentId(unread, byId)
        while (parentId != null && keep.add(parentId)) {
            parentId = byId[parentId]?.let { folderParentId(it, byId) }
        }
    }
    return mailboxes.filter { it.id in keep }
}

/** The list a tab shows. [FolderTab.ALL] is the account's folders untouched, as the drawer always
 *  drew them; only [FolderTab.UNREAD] filters, so the two tabs cannot drift apart in any other way. */
internal fun foldersForTab(mailboxes: List<Mailbox>, tab: FolderTab): List<Mailbox> = when (tab) {
    FolderTab.ALL -> mailboxes
    FolderTab.UNREAD -> foldersWithUnread(mailboxes)
}
