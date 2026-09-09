package app.sterna.widget

import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.mail.RecentInbox
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * WHEN the latest-messages widget is asked to draw itself again — [UnreadWidgetPush]'s twin, kept
 */
internal object RecentMailWidgetPush {

    /**
     * The moments the cell must be redrawn, as the READ that changed under it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun redraws(
        placed: Flow<Boolean>,
        accounts: Flow<List<StoredAccount>>,
        inbox: () -> Flow<RecentInbox>,
    ): Flow<RecentInbox> = placed
        .distinctUntilChanged()
        .flatMapLatest { anyPlaced ->
            if (anyPlaced) inbox().combine(accounts.map(AllInboxesView::existsFor), ::Pair) else emptyFlow()
        }
        .distinctUntilChanged()
        .map { (read, _) -> read }
}
