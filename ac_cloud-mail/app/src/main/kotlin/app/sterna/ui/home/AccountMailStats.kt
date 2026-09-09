package app.sterna.ui.home

import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.jmap.model.Mailbox
import app.sterna.ui.inbox.drawerUnreadCount
import app.sterna.ui.inbox.showOnlySubscribedFor
import app.sterna.ui.inbox.visibleFolders

/**
 * What the Home screen says about ONE account. Every field is derived from data the store already
 * holds for the drawer, so opening this page asks the database for nothing it is not already being
 * asked while the inbox is on screen; see [accountMailStats] for where each number comes from.
 *
 * A data class rather than five parallel lists: the whole point of this page is that two accounts
 * must not bleed into each other, and the type is what makes "account A's folder count" impossible
 * to hand to account B's card.
 */
data class AccountMailStats(
    val accountId: String,
    /** [StoredAccount.label] — the account name, or the address when it is blank. */
    val label: String,
    /** Chosen accent colour (ARGB) or null for the derived one; the drawer's own convention. */
    val color: Int?,
    val protocol: MailProtocol,
    /** The host this account talks to: the IMAP server for IMAP, the JMAP endpoint otherwise. */
    val host: String,
    /**
     * Unread over the folders the drawer draws for this account, or null when this account's rows
     * cannot carry an unread count at all (IMAP; see [AccountMailStats.protocol] and
     * `MailRepository.folderRowsBadgeUnread`). Null is NOT zero: printing 0 for an IMAP account
     * would state, in numerals, that there is no unread mail — which nothing here knows.
     */
    val unread: Int?,
    /** Messages of this account in the local cache. */
    val cachedMessages: Int,
    /** Every folder cached for this account, whether or not the drawer currently lists it. */
    val folders: Int,
    /** How many of [folders] the server reports as subscribed (RFC 8621 §2 / IMAP `LSUB`). */
    val subscribedFolders: Int,
)

/**
 * The account's unread as THE DRAWER counts it — [drawerUnreadCount], executed, over the same list
 * the drawer draws. Not a second sum written here: a statistics page that disagrees with the badge
 * beside it by one is worse than a page carrying no unread at all, and the only way to be sure the
 * two agree is for there to be one function.
 *
 * Asked with NOTHING folded, deliberately. A FOLDED row's badge already contains its descendants'
 * unread, so adding up the folded badges counts the mail under a parent once per ancestor above it.
 * Unfolded, every row answers its own count and every folder is added exactly once — including the
 * ones the user has collapsed, which are still this account's unread mail.
 */
internal fun accountUnreadTotal(drawnFolders: List<Mailbox>): Int =
    drawnFolders.sumOf { drawerUnreadCount(it, drawnFolders, collapsed = emptySet()) }

/**
 * One account's card, from the two lists that describe it and the two numbers only the database can
 * answer. Deliberately takes an account and ITS OWN folders rather than reading anything global:
 * handed the wrong list it produces a wrong card, and a test can say so.
 *
 * [allFolders] and [drawnFolders] are different lists on purpose. The folder counts describe what
 * the ACCOUNT has, so they are taken over everything cached for it — an account with 28 folders of
 * which 24 are subscribed must say 28, or the two numbers stop meaning anything together. The
 * unread is taken over what the DRAWER DRAWS, because that is the number it has to agree with: on
 * an account asking for subscribed folders only (#174) the drawer never badges the hidden ones.
 */
internal fun accountMailStats(
    account: StoredAccount,
    allFolders: List<Mailbox>,
    drawnFolders: List<Mailbox>,
    /** `MailRepository.folderRowsBadgeUnread` for this account — asked of the repository, never
     *  re-derived from the protocol here, so there is one answer to "can a row badge unread". */
    unreadIsCounted: Boolean,
    cachedMessages: Int,
): AccountMailStats = AccountMailStats(
    accountId = account.id,
    label = account.label(),
    color = account.color,
    protocol = account.protocol,
    host = accountHost(account),
    unread = if (unreadIsCounted) accountUnreadTotal(drawnFolders) else null,
    cachedMessages = cachedMessages,
    folders = allFolders.size,
    subscribedFolders = allFolders.count { it.isSubscribed },
)

/** The host the account actually connects to. [StoredAccount.server] is the JMAP session URL and is
 *  left empty on an IMAP account, whose host lives in [StoredAccount.imapHost] instead. */
internal fun accountHost(account: StoredAccount): String = when (account.protocol) {
    MailProtocol.IMAP -> account.imapHost.ifBlank { account.server }
    MailProtocol.JMAP -> account.server
}

/**
 * Every configured account's card, each PAIRED with its own folder list — the whole risk this page
 * carries. Two accounts can hold the same bare mailbox id (servers number mailboxes per account,
 * which is why the cache is keyed on the pair), so a folder list handed to the wrong account
 * produces a card that is wrong in a way nothing on screen would betray: plausible numbers, under
 * the other account's name.
 *
 * `zip` rather than an index into a second list: the pairing is then positional ONCE, in one
 * expression, instead of at every field. [foldersPerAccount] comes from a `combine` over one flow
 * per account, so it is the same length and in the same order as [accounts]; if it ever is not, zip
 * drops a card rather than mixing two.
 */
internal fun accountMailStatsList(
    accounts: List<StoredAccount>,
    foldersPerAccount: List<List<Mailbox>>,
    cachedMessages: Map<String, Int>,
    /** `MailRepository.folderRowsBadgeUnread`, per account id. */
    unreadIsCounted: (String) -> Boolean,
): List<AccountMailStats> = accounts.zip(foldersPerAccount) { account, folders ->
    accountMailStats(
        account = account,
        allFolders = folders,
        // The drawer's own list for THIS account: "show only subscribed folders" is a per-account
        // setting (#174), so one account's answer is never reused for another's card.
        drawnFolders = visibleFolders(folders, showOnlySubscribedFor(account.id, accounts)),
        unreadIsCounted = unreadIsCounted(account.id),
        cachedMessages = cachedMessages[account.id] ?: 0,
    )
}
