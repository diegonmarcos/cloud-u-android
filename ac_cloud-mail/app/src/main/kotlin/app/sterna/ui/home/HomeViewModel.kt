package app.sterna.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.core.data.account.StoredAccount
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** What the Home screen draws. [loaded] tells "no account configured" apart from "the account list
 *  has not been read yet": the first must show the empty state, the second must show nothing. */
data class HomeUi(
    val accounts: List<AccountMailStats> = emptyList(),
    val loaded: Boolean = false,
)

/**
 * Backs the Home screen: one card of statistics per configured account.
 *
 * NOTHING here is a new metrics pipeline. Every number is either already in the account record or
 * comes back from a read the drawer performs anyway:
 *
 *  - folders / subscribed folders and the unread badge → `MailRepository.observeMailboxes`, which
 *    is `SELECT * FROM mailboxes WHERE accountId = ?` (a few dozen rows) plus, on JMAP, the same
 *    two per-folder unread aggregates the drawer's badges are built from. Room runs both on its own
 *    query executor, so nothing here touches the main thread.
 *  - cached messages → `StorageRepository.usage`, whose per-account figure is ONE
 *    `SELECT accountId, COUNT(*) FROM emails GROUP BY accountId` for every account at once, already
 *    behind `withContext(Dispatchers.IO)`. Read once when the page opens rather than observed: a
 *    cache count that ticks while you look at it is worth less than the query it would cost.
 *
 * No message row is ever walked in Kotlin — the database aggregates and hands back one number per
 * (account, folder). An account with fifty thousand cached messages costs the same as an empty one
 * plus whatever SQLite spends counting index entries.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val accountStore = application.container.accountStore
    private val repository = application.container.mailRepository
    private val storage = application.container.storageRepository

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<HomeUi> = accountStore.accountsFlow
        // An imported account still awaiting sign-in has never synced and has no folders; the
        // accounts screen hides it for the same reason ([StoredAccount.accountsScreenRows]).
        .map { accounts -> accounts.filterNot { it.importPending } }
        .distinctUntilChanged()
        .flatMapLatest(::statsFor)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeUi(),
        )

    /**
     * One card per account. One folder flow PER ACCOUNT, combined — never one flattened list of
     * folders: two accounts can share a bare mailbox id, and a merged list is precisely how one
     * account's mail ends up counted on the other's card. The pairing itself is
     * [accountMailStatsList], where a test can reach it.
     */
    private fun statsFor(accounts: List<StoredAccount>): Flow<HomeUi> {
        if (accounts.isEmpty()) return flowOf(HomeUi(loaded = true))
        val folderFlows = accounts.map { repository.observeMailboxes(it.id) }
        return combine(combine(folderFlows) { it.toList() }, cachedMessagesByAccount()) { folders, cached ->
            HomeUi(
                accounts = accountMailStatsList(
                    accounts = accounts,
                    foldersPerAccount = folders,
                    cachedMessages = cached,
                    unreadIsCounted = repository::folderRowsBadgeUnread,
                ),
                loaded = true,
            )
        }
    }

    /** The cache count for every account in one aggregate, emitted once. An account with no cached
     *  mail is absent from the map, so the caller reads it as 0 rather than as "unknown". */
    private fun cachedMessagesByAccount(): Flow<Map<String, Int>> =
        flow { emit(storage.usage().perAccount.associate { it.accountId to it.messageCount }) }
}
