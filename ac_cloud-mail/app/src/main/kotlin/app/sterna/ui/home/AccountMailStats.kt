package app.sterna.ui.home

import androidx.annotation.StringRes
import app.sterna.R
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.db.AccountHomeCounts
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
    /** Cached messages of this account that are starred (flagged). */
    val starred: Int,
    /** Cached messages of this account that carry at least one file. */
    val withAttachments: Int,
    /** Cached messages dated within [HOME_RECENT_DAYS] of when the page was opened. */
    val recent: Int,
    /** Epoch millis of the oldest dated cached message, or null when none carries a date. */
    val oldestMillis: Long?,
) {
    /**
     * This account has nothing to count: no message cached and no unread the server reported. The
     * page then says so in a sentence instead of drawing a column of zeros that would look like
     * measurements — a fresh account, or one cleared from Storage, is not "0 starred, 0 recent".
     */
    val hasNoMail: Boolean get() = false
}

/** The window "recent" is counted over, and what the page's label names. One place, so they agree. */
internal const val HOME_RECENT_DAYS = 7

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
    /** `StorageRepository.homeCounts` for this account; null = it has no cached mail at all. */
    home: AccountHomeCounts? = null,
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
    starred = home?.starred ?: 0,
    withAttachments = home?.withAttachments ?: 0,
    recent = home?.recent ?: 0,
    oldestMillis = home?.oldest,
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
    /** `StorageRepository.homeCounts`, per account id. */
    homeCounts: Map<String, AccountHomeCounts> = emptyMap(),
    /** `MailRepository.folderRowsBadgeUnread`, per account id. Last, so a trailing lambda binds it. */
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
        home = homeCounts[account.id],
    )
}

/**
 * The unread across every account that can count it, or null when none can (all IMAP): "no answer"
 * stays distinct from "zero", which the hero's mood line would otherwise celebrate as inbox zero.
 */
internal fun totalUnread(accounts: List<AccountMailStats>): Int? =
    accounts.mapNotNull { it.unread }.takeIf { it.isNotEmpty() }?.sum()

/**
 * The number the hero draws large: [totalUnread], except when EVERY account is empty
 * ([AccountMailStats.hasNoMail]) — a fresh install reports zero unread because nothing has synced,
 * not because the reader is caught up, and a big "0" there would be a measurement of nothing.
 */
internal fun heroUnread(accounts: List<AccountMailStats>): Int? =
    if (accounts.all { it.hasNoMail }) null else totalUnread(accounts)

/**
 * How the page greets the reader, chosen by the real unread total and nothing else. DECLARED as a
 * table (#351/#474): the lowest unread count each line applies from, highest first. A new mood is a
 * new row — the choice in [of] is the same for all of them.
 */
internal enum class HomeMood(val atLeast: Int, @StringRes val line: Int) {
    AVALANCHE(1000, R.string.home_mood_avalanche),
    HEAVY(100, R.string.home_mood_heavy),
    GROWING(10, R.string.home_mood_growing),
    HANDFUL(1, R.string.home_mood_handful),
    ZERO(0, R.string.home_mood_zero),
    ;

    companion object {
        /**
         * The line for these accounts. Two lines claim nothing on purpose: every account empty
         * (nothing has synced, so "inbox zero" would be a lie), and no account able to count unread.
         */
        @StringRes
        fun of(accounts: List<AccountMailStats>): Int {
            if (accounts.all { it.hasNoMail }) return R.string.home_mood_nothing
            val unread = totalUnread(accounts) ?: return R.string.home_mood_uncounted
            return HomeMood.entries.first { unread >= it.atLeast }.line
        }
    }
}
