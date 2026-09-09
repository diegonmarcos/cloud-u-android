package app.sterna.widget

import app.sterna.core.data.account.StoredAccount
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * WHEN the home-screen widget is pushed new numbers, kept apart from Android so it can be EXECUTED
 */
internal object UnreadWidgetPush {

    /**
     * The numbers to draw over time: nothing while no widget is placed, the shared unified-unread
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun redraws(
        placed: Flow<Boolean>,
        accounts: Flow<List<StoredAccount>>,
        unread: () -> Flow<Map<String, Int>>,
    ): Flow<Map<String, Int>> = placed
        .distinctUntilChanged()
        .flatMapLatest { anyPlaced ->
            if (anyPlaced) unread().combine(accounts.map(::cellBound), ::Pair) else emptyFlow()
        }
        .distinctUntilChanged()
        .map { (unreadByAccount, _) -> unreadByAccount }

    /** Which of [UnreadWidgetContent.of]'s three arms the cell is drawn by — the account list
     *  projected down to the only thing this trigger may react to. */
    private enum class CellBound { ALL_INBOXES, ONE_ACCOUNT, NO_ACCOUNT }

    private fun cellBound(accounts: List<StoredAccount>): CellBound = when {
        AllInboxesView.existsFor(accounts) -> CellBound.ALL_INBOXES
        accounts.isEmpty() -> CellBound.NO_ACCOUNT
        else -> CellBound.ONE_ACCOUNT
    }
}
