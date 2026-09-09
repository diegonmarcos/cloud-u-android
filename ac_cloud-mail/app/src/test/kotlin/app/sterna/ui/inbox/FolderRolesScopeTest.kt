package app.sterna.ui.inbox

import app.sterna.core.data.account.StoredAccount
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WHEN the (account, folder) → role map is re-read (#115).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FolderRolesScopeTest {

    private fun account(id: String, inboxId: String? = null) = StoredAccount(
        id = id,
        server = "https://mail.example.org",
        username = "$id@example.org",
        inboxId = inboxId,
    )

    /** The cache, as the DAO would answer it: every account's folders, whatever its sync state. */
    private val stored = mapOf(
        "a" to mapOf(("a" to "fa") to "inbox"),
        "b" to mapOf(("b" to "fb") to "sent"),
        "c" to mapOf(("c" to "fc") to "archive"),
    )

    /** Which id sets the map was opened for, in order — the observable behaviour under test. */
    private val asked = mutableListOf<List<String>>()

    private fun roles(ids: List<String>): Flow<Map<Pair<String, String>, String>> {
        asked += ids
        return flowOf(ids.flatMap { stored[it].orEmpty().entries }.associate { it.key to it.value })
    }

    @Test
    fun `an account added before it has ever synced is still covered`() = runTest {
        // The reported shape: "b" is in the store the moment it is added, and has no inbox id until
        // its first folder sync. Off the old signal that was an identical scope list, so nothing
        // moved and b's rows were judged by a's map.
        val accounts = MutableStateFlow(listOf(account("a", inboxId = "fa")))
        val seen = mutableListOf<Map<Pair<String, String>, String>>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            folderRolesFlow(accounts) { roles(it) }.toList(seen)
        }
        runCurrent()

        accounts.value = accounts.value + account("b")
        runCurrent()
        job.cancel()

        assertEquals(
            "the map must cover the account that was just added, inbox id or not",
            mapOf(("a" to "fa") to "inbox", ("b" to "fb") to "sent"),
            seen.last(),
        )
        assertEquals(listOf(listOf("a"), listOf("a", "b")), asked)
    }

    @Test
    fun `signing an account out takes its folders out of the map`() = runTest {
        val accounts = MutableStateFlow(listOf(account("a", inboxId = "fa"), account("b", inboxId = "fb")))
        val seen = mutableListOf<Map<Pair<String, String>, String>>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            folderRolesFlow(accounts) { roles(it) }.toList(seen)
        }
        runCurrent()

        accounts.value = listOf(account("a", inboxId = "fa"))
        runCurrent()
        job.cancel()

        assertEquals(mapOf(("a" to "fa") to "inbox"), seen.last())
        assertEquals(listOf(listOf("a", "b"), listOf("a")), asked)
    }

    @Test
    fun `a write that leaves the accounts the same does not re-open the map`() {
        // The other half of "do not replace one snapshot with another": the store republishes its
        // whole list on every write — an inbox id learned, an unread counter moved, identities
        // refreshed — and re-subscribing to Room on each of those would trade a stale map for a
        // storm. The set of accounts is what the map is keyed by, and the only thing it follows.
        runTest {
            val accounts = MutableStateFlow(listOf(account("a")))
            val seen = mutableListOf<Map<Pair<String, String>, String>>()
            val job = launch(UnconfinedTestDispatcher(testScheduler)) {
                folderRolesFlow(accounts) { roles(it) }.toList(seen)
            }
            runCurrent()

            // Same account, now synced: an inbox id appears, and its unread count moves twice.
            accounts.value = listOf(account("a", inboxId = "fa"))
            runCurrent()
            accounts.value = listOf(account("a", inboxId = "fa").copy(unread = 3))
            runCurrent()
            job.cancel()

            assertEquals(listOf(listOf("a")), asked)
            assertEquals(mapOf(("a" to "fa") to "inbox"), seen.last())
        }
    }

    @Test
    fun `the map is opened for every account, not only the synced ones`() = runTest {
        // Three accounts, none of which has synced yet. The map must be asked for all three: an
        // unfolded conversation in the unified list spans accounts that are not the selected one.
        val accounts = MutableStateFlow(listOf(account("a"), account("b"), account("c")))
        val seen = mutableListOf<Map<Pair<String, String>, String>>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            folderRolesFlow(accounts) { roles(it) }.toList(seen)
        }
        runCurrent()
        job.cancel()

        assertEquals(listOf(listOf("a", "b", "c")), asked)
        assertEquals(
            mapOf(("a" to "fa") to "inbox", ("b" to "fb") to "sent", ("c" to "fc") to "archive"),
            seen.last(),
        )
    }
}
