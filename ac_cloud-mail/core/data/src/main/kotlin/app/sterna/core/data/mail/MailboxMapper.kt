package app.sterna.core.data.mail

import app.sterna.core.data.db.MailboxEntity
import app.sterna.core.jmap.model.Mailbox

internal fun Mailbox.toEntity(accountId: String): MailboxEntity = MailboxEntity(
    accountId = accountId,
    id = id,
    name = name,
    role = role,
    parentId = parentId,
    sortOrder = sortOrder,
    totalEmails = totalEmails,
    unreadEmails = unreadEmails,
    isSubscribed = isSubscribed,
)

/** The cache row as the UI model ([Mailbox]). A marked role is erased here ([unmarkedRole]); what
 *  crosses instead is the fact of the lost claim, as a boolean and never as a role, because
 *  `InboxScreen` gates "New subfolder", "Rename" and "Delete" on `mailbox.role == null`. No search
 *  path goes through here: the searched/excluded split reads the raw stored role. */
internal fun MailboxEntity.toMailbox(): Mailbox = Mailbox(
    id = id,
    name = name,
    role = unmarkedRole(role),
    parentId = parentId,
    sortOrder = sortOrder,
    totalEmails = totalEmails,
    unreadEmails = unreadEmails,
    // The stored server counter, and the BASELINE the repository keeps: it is overridden only
    // where the local cache actually holds that folder's mail to count (#247).
    unreadForList = unreadEmails,
    isSubscribed = isSubscribed,
    // Read by `visibleFolders` alone, to keep a folder that lost its claim listed under #174.
    lostRoleClaim = isLostRoleClaim(role),
)
