package app.sterna.core.data.account

import app.sterna.core.data.db.RecentEmailRow
import app.sterna.core.data.mail.RecentInbox
import app.sterna.core.data.mail.recentUnifiedInboxFrom
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** What "all inboxes" MEANS, computed once so nothing derives it by hand. READ-ONLY, over
 *  [AccountStore.accountsFlow]'s last PUBLISHED list, so an undecodable blob leaves the count where it was. */
internal object UnifiedInbox {

    /** One inbox that counts: its scope and the stored counter it contributes on its own. */
    data class CountedInbox(val scope: Pair<String, String>, val storedUnread: Int)

    /** Inboxes of every account synced at least once (`inboxId == null` skipped). The scope
     *  is a PAIR, never a bare folder id (#121): two accounts can share a mailbox id. */
    fun countedInboxes(accounts: List<StoredAccount>): List<CountedInbox> =
        accounts.mapNotNull { a -> a.inboxId?.let { CountedInbox(a.id to it, a.unread) } }

    /** The (account id, inbox id) pairs the counter is asked for — [countedInboxes] projected. */
    fun scopes(accounts: List<StoredAccount>): List<Pair<String, String>> =
        countedInboxes(accounts).map { it.scope }

    /** Unified unread total, BROKEN DOWN per account. Keyed on [countedInboxes], not bare
     *  scopes: the STORED COUNTER must be in the key (a flat read no Room invalidation wakes). */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun unreadByAccount(
        accounts: Flow<List<StoredAccount>>,
        unreadFor: (List<Pair<String, String>>) -> Flow<Map<String, Int>>,
    ): Flow<Map<String, Int>> = accounts
        .map(::countedInboxes)
        .distinctUntilChanged()
        .map { counted -> counted.map { it.scope } }
        .flatMapLatest(unreadFor)

    /** "All inboxes (N)": every counted inbox's share of [unreadByAccount], ADDED. No entry is 0. */
    fun total(byAccount: Map<String, Int>): Int = byAccount.values.sum()

    /** [unreadByAccount]'s sibling for the home screen. Carries [RecentInbox], not bare rows,
     * or "no mail" and "no account" look identical. Keyed on [scopes], not [countedInboxes]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun recentRows(
        accounts: Flow<List<StoredAccount>>,
        rowsFor: (List<Pair<String, String>>) -> Flow<List<RecentEmailRow>>,
    ): Flow<RecentInbox> = accounts
        .map(::scopes)
        .distinctUntilChanged()
        .flatMapLatest { scopes ->
            if (scopes.isEmpty()) flowOf(recentUnifiedInboxFrom(scopes) { emptyList() })
            else rowsFor(scopes).map { rows -> recentUnifiedInboxFrom(scopes) { rows } }
        }
}
