package app.sterna.core.data.account

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the account selector observes (issue #31): the list republished on every write to
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountsStateTest {

    private val login = StoredAccount(
        id = "login-uuid",
        server = "https://mail.example.org",
        username = "alex.rivera@example.org",
        jmapAccountId = "s",
    )

    private fun shared(id: String) = StoredAccount(
        id = id,
        server = "https://mail.example.org",
        username = "alex.rivera@example.org",
        accountName = "jordan.lee@example.org",
        loginId = "login-uuid",
        jmapAccountId = "u",
    )

    /** Ids only: what the selector keys its rows on. */
    private fun idsSeen(lists: List<List<StoredAccount>>) = lists.map { list -> list.map { it.id } }

    @Test fun anObserverStartsOnTheStoredList() = runTest {
        val state = AccountsState(listOf(login, shared("sub-1")))
        val seen = mutableListOf<List<StoredAccount>>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            state.flow.toList(seen)
        }
        runCurrent()

        assertEquals(listOf(listOf("login-uuid", "sub-1")), idsSeen(seen))
        job.cancel()
    }

    @Test fun aRevokedShareLeavesTheListAndAReGrantedOneComesBack() = runTest {
        val state = AccountsState(listOf(login, shared("sub-1")))
        val seen = mutableListOf<List<StoredAccount>>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            state.flow.toList(seen)
        }
        runCurrent()

        // The reconcile that follows a revocation prunes the sub-account…
        state.publish(listOf(login))
        // …and the one that follows a fresh grant mints it again, under a new record id.
        state.publish(listOf(login, shared("sub-2")))
        runCurrent()

        assertEquals(
            listOf(
                listOf("login-uuid", "sub-1"),
                listOf("login-uuid"),
                listOf("login-uuid", "sub-2"),
            ),
            idsSeen(seen),
        )
        job.cancel()
    }

    @Test fun anUnchangedListIsNotRepublished() = runTest {
        val state = AccountsState(listOf(login, shared("sub-1")))
        val seen = mutableListOf<List<StoredAccount>>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            state.flow.toList(seen)
        }
        runCurrent()

        // Every connect reconciles, and the usual outcome is "nothing changed" — rebuilt list
        // objects, equal contents. Re-emitting those would recompose the drawer for nothing.
        state.publish(listOf(login, shared("sub-1")))
        state.publish(listOf(login, shared("sub-1")))
        runCurrent()

        assertEquals(1, seen.size)
        job.cancel()
    }

    @Test fun signingOutOfEverythingEmptiesTheList() = runTest {
        val state = AccountsState(listOf(login, shared("sub-1")))
        val seen = mutableListOf<List<StoredAccount>>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            state.flow.toList(seen)
        }
        runCurrent()

        state.publish(emptyList())
        runCurrent()

        assertEquals(listOf(listOf("login-uuid", "sub-1"), emptyList()), idsSeen(seen))
        job.cancel()
    }
}
