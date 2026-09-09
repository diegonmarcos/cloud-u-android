package app.sterna.ui

/** Folders that hold the user's OWN outgoing mail. Triage actions that only make sense on incoming
 *  mail are hidden there (#82); a null or unknown role keeps them, the rule hiding them only where
 *  they are provably meaningless. */
internal fun isOutgoingFolder(role: String?): Boolean =
    role?.trim()?.lowercase() in OUTGOING_ROLES

private val OUTGOING_ROLES = setOf("drafts", "sent")

/** Whether "Snooze" is worth offering in a folder (#82): a promise about mail you still intend to
 *  deal with, so on top of Drafts and Sent it is dropped in Spam and Trash. Deliberately narrower
 *  than hiding the whole incoming-only group there — "Not spam" is what the Spam folder is for. */
internal fun canSnoozeIn(role: String?): Boolean =
    role?.trim()?.lowercase() !in NO_SNOOZE_ROLES

// "spam" as well as "junk": JMAP names the role `junk` and the IMAP side maps \Junk onto it, but a
// role reaching here unmapped must not slip through on a spelling.
private val NO_SNOOZE_ROLES = OUTGOING_ROLES + setOf("junk", "spam", "trash")

/**
 * Whether a message should be shown by its RECIPIENTS ("To: …") instead of its sender — the one
 */
internal fun showsRecipients(role: String?, unified: Boolean, selfAuthored: Boolean): Boolean {
    val normalised = role?.trim()?.lowercase()
    if (normalised in OUTGOING_ROLES) return true
    if (unified || normalised == "inbox") return false
    return selfAuthored
}

/**
 * The same decision for a row INSIDE an unfolded conversation — the third surface, which used to
 */
internal fun showsRecipientsInThread(
    accountId: String?,
    mailboxId: String?,
    roles: Map<Pair<String, String>, String>,
    selfAuthored: Boolean,
): Boolean = showsRecipients(
    role = messageFolderRole(accountId, mailboxId, roles),
    unified = false,
    selfAuthored = selfAuthored,
)

/**
 * Whether a list row carries the "(Draft)" chip — two terms, one not always there and the other not
 */
internal fun showsDraftBadge(
    isDraft: Boolean,
    accountId: String?,
    mailboxId: String?,
    roles: Map<Pair<String, String>, String>,
): Boolean = isDraft || messageFolderRole(accountId, mailboxId, roles)?.trim()?.lowercase() == "drafts"

/**
 * The role of the folder a message is actually filed in, or null when that cannot be said.
 */
internal fun messageFolderRole(
    accountId: String?,
    mailboxId: String?,
    roles: Map<Pair<String, String>, String>,
): String? {
    if (accountId == null || mailboxId == null) return null
    return roles[accountId to mailboxId]
}
