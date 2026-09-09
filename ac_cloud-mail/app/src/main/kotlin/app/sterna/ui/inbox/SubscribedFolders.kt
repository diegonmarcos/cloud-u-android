package app.sterna.ui.inbox

import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.showOnlySubscribedFoldersOf
import app.sterna.core.data.mail.isRoutingTargetByName
import app.sterna.core.jmap.model.Mailbox

/**
 * The folders the screens show when an account asks for its subscribed folders only (#174), in the
 */
internal fun visibleFolders(mailboxes: List<Mailbox>, showOnlySubscribed: Boolean): List<Mailbox> {
    if (!showOnlySubscribed) return mailboxes
    val byId = mailboxes.associateBy { it.id }
    val keep = mutableSetOf<String>()
    mailboxes.forEach { mailbox ->
        val survives = mailbox.isSubscribed || mailbox.role != null ||
            isRoutingTargetByName(mailbox) || mailbox.lostRoleClaim
        if (!survives) return@forEach
        // Rule 4, walked from the visible folder up. `seen` guards a `parentId` cycle, which a
        // server can answer and which would spin here forever.
        val seen = mutableSetOf<String>()
        var current: Mailbox? = mailbox
        while (current != null && seen.add(current.id)) {
            keep += current.id
            current = folderParentId(current, byId)?.let { byId[it] }
        }
    }
    return mailboxes.filter { it.id in keep }
}

/** The id of [mailbox]'s parent among [byId], or null at the root of what is listed: the JMAP
 * `parentId` when it names a folder the list holds, else the IMAP path prefix. A `parentId`
 *  naming an absent folder falls through: an unplaceable child is drawn at top level, not dropped. */
internal fun folderParentId(mailbox: Mailbox, byId: Map<String, Mailbox>): String? {
    if (mailbox.parentId != null && byId.containsKey(mailbox.parentId)) return mailbox.parentId
    val delimiter = when {
        mailbox.id.contains('/') -> "/"
        mailbox.id.contains('.') -> "."
        else -> return null
    }
    val parent = mailbox.id.substringBeforeLast(delimiter, "")
    return if (parent.isNotEmpty() && byId.containsKey(parent)) parent else null
}

/** What [accountId]'s own record asks for: the setting is per account, and the pickers do not read
 * the same account as the drawer — the reader's is the open message's owner (#92). An id
 *  resolving to no record answers false: true would hide folders on an unidentified account. */
internal fun showOnlySubscribedFor(accountId: String?, accounts: List<StoredAccount>): Boolean =
    showOnlySubscribedFoldersOf(accounts.firstOrNull { it.id == accountId })
