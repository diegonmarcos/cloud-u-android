package app.sterna.core.data.mail

import app.sterna.core.jmap.WindowWalk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Signing an account out while it syncs must stop what writes in its name — EXECUTED, decision
 */
class SignedOutAccountStopsWritingTest {

    private companion object {
        const val ACCOUNT = "acc-1"
        const val SIBLING = "acc-2"
    }

    /** Five pages, so there is a middle and a tail: two are written before the sign-out lands. */
    private val fivePages = listOf(
        listOf("p1a", "p1b"),
        listOf("p2a", "p2b"),
        listOf("p3a"),
        listOf("p4a"),
        listOf("p5a"),
    )
    private val allFive = fivePages.flatten()

    /**
     * The walk: hands each page over and counts what it handed over. [offered] includes the page
     */
    private class Walk(
        private val pages: List<List<String>>,
        private val between: suspend (Int) -> Unit = {},
    ) {
        var offered = 0
        val handed = mutableListOf<String>()

        suspend fun run(onPage: suspend (List<String>) -> Unit): WindowWalk {
            pages.forEachIndexed { index, page ->
                offered++
                onPage(page)
                handed += page
                between(index)
            }
            return WindowWalk(
                ids = handed, queryState = "q1", emailState = "e1",
                queryCount = handed.size, resumedByPosition = false,
            )
        }
    }

    /** The folder as this path leaves it, plus what the reconcile was handed (null = it never ran). */
    private class Folder(cached: List<String> = emptyList()) {
        val cache = LinkedHashSet(cached)
        var reconciledKeepIds: Set<String>? = null
        var evicted: List<String> = emptyList()

        suspend fun write(page: List<String>) {
            cache += page
        }

        suspend fun reconcile(keepIds: Set<String>, spareIds: List<String>, evictableIds: Set<String>?) {
            reconciledKeepIds = keepIds
            val gone = reconcileEvictions(
                cachedIds = evictableCachedIds(cache.toList(), evictableIds),
                keepIds = keepIds,
                spareIds = spareIds.toHashSet(),
            ).flatten()
            evicted = gone
            cache -= gone.toSet()
        }
    }

    /**
     * One full-query walk of [ACCOUNT], written through the shipped order, with the account list
     */
    private fun walkWhile(
        configured: MutableList<String>,
        folder: Folder,
        accountId: String = ACCOUNT,
        signOutAfterPage: Int? = null,
        signOut: (MutableList<String>) -> Unit = { it -= ACCOUNT },
    ): Pair<Walk, Throwable?> {
        val walk = Walk(fivePages, between = { index -> if (index == signOutAfterPage) signOut(configured) })
        val thrown = runBlocking {
            runCatching {
                fullQueryWriteThrough(
                    folderIds = { folder.cache.toSet() },
                    walk = { onPage -> walk.run(onPage) },
                    writePage = { page ->
                        checkAccountStillConfigured(accountId, configured)
                        folder.write(page)
                    },
                    keepIds = { it.ids.toHashSet() },
                    spareIds = { emptyList() },
                    reconcile = { _, keep, spare, evictable -> folder.reconcile(keep, spare, evictable) },
                )
            }.exceptionOrNull()
        }
        return walk to thrown
    }

    // -- the walk of a signed-out account ---------------------------------------------------------

    @Test fun `a sign-out mid-walk stops the walk and leaves nothing new under the removed id`() {
        // THE test of this volet. The account goes after two pages. What must hold:
        //  - the walk STOPPED (three pages offered out of five, the third being the one refused);
        val configured = mutableListOf(ACCOUNT, SIBLING)
        val folder = Folder(cached = listOf("older"))

        val (walk, thrown) = walkWhile(configured, folder, signOutAfterPage = 1)

        assertTrue(
            "the walk was not stopped by an AccountGoneException but by ${thrown?.javaClass?.name}: $thrown",
            thrown is AccountGoneException,
        )
        assertEquals("the removed account is not the one named in the failure", ACCOUNT, (thrown as AccountGoneException).accountId)
        assertEquals(
            "the walk kept going after the sign-out: it offered ${walk.offered} pages out of " +
                "${fivePages.size}. Refusing the WRITE is not enough — a walk that runs on keeps " +
                "the connection, the folder and the battery busy for an account that is gone.",
            3, walk.offered,
        )
        assertEquals(
            "a page landed under an account that no longer exists — the ghost of #121, re-created " +
                "after the sign-out had already swept it",
            setOf("older", "p1a", "p1b", "p2a", "p2b"), folder.cache,
        )
        assertNull("the reconcile ran on an abandoned walk", folder.reconciledKeepIds)
        assertEquals("something was deleted by an abandoned walk", emptyList<String>(), folder.evicted)
    }

    @Test fun `the witness — the same fixture with the account left alone runs to the end`() {
        // Without this the test above proves nothing: it would pass on a fixture whose walk stops
        // for some other reason. Same five pages, same guard, nobody signs out.
        val configured = mutableListOf(ACCOUNT, SIBLING)
        val folder = Folder(cached = listOf("older"))

        val (walk, thrown) = walkWhile(configured, folder)

        assertNull("a configured account's walk was refused: $thrown", thrown)
        assertEquals(fivePages.size, walk.offered)
        assertEquals(allFive.toSet(), folder.cache)
        assertEquals("the walk finished, so the reconcile must have run", allFive.toSet(), folder.reconciledKeepIds)
        assertEquals(listOf("older"), folder.evicted)
    }

    @Test fun `signing ANOTHER account out does not touch this walk`() {
        // The mutation this is written against: a guard that asks "has ANY account been removed"
        // (or checks the wrong id) stops a healthy account's sync every time a sibling is deleted.
        val configured = mutableListOf(ACCOUNT, SIBLING)
        val folder = Folder()

        val (walk, thrown) = walkWhile(configured, folder, signOutAfterPage = 1, signOut = { it -= SIBLING })

        assertNull("removing a SIBLING account stopped this account's walk: $thrown", thrown)
        assertEquals(fivePages.size, walk.offered)
        assertEquals(allFive.toSet(), folder.cache)
    }

    @Test fun `a walk whose account goes before the first page writes nothing at all`() {
        // The other end of the race: the sign-out lands while the first request is in flight.
        val configured = mutableListOf(ACCOUNT, SIBLING)
        val folder = Folder(cached = listOf("older"))
        configured -= ACCOUNT

        val (walk, thrown) = walkWhile(configured, folder)

        assertTrue("$thrown", thrown is AccountGoneException)
        assertEquals(1, walk.offered)
        assertEquals(setOf("older"), folder.cache)
        assertNull(folder.reconciledKeepIds)
    }

    // -- the decision itself -------------------------------------------------------------------------

    @Test fun `a configured account may write`() {
        checkAccountStillConfigured(ACCOUNT, listOf(SIBLING, ACCOUNT))
    }

    @Test fun `an account that is no longer configured may not`() {
        val thrown = assertThrows(AccountGoneException::class.java) {
            checkAccountStillConfigured(ACCOUNT, listOf(SIBLING))
        }
        assertEquals(ACCOUNT, thrown.accountId)
    }

    @Test fun `the refusal is a cancellation, which is what keeps it off the screen and off the delete path`() {
        // Three things ride on the TYPE, none of them visible from the assertion above:
        // `fullQueryWriteThrough` runs no reconcile on it, `ImapMailService.runWithRetry` does not
        // replay the walk, and `InboxViewModel.refresh` does not turn it into an error banner for a
        // gesture that succeeded. A plain IllegalStateException would compile and break all three.
        val thrown = assertThrows(AccountGoneException::class.java) {
            checkAccountStillConfigured(ACCOUNT, emptyList())
        }
        assertTrue(
            "AccountGoneException is no longer a CancellationException: it is now an error the " +
                "refresh will report, and one the IMAP retry will replay",
            thrown is CancellationException,
        )
    }

    @Test fun `an empty account list refuses, it does not wave everything through`() {
        // The opposite of OrphanedAccountCache.orphans, deliberately. There an empty inventory
        // must sweep NOTHING, because sweeping deletes; here an empty list must refuse, because
        // refusing only stops a write. Both fail on the side that cannot destroy data.
        assertThrows(AccountGoneException::class.java) {
            checkAccountStillConfigured(ACCOUNT, emptyList())
        }
    }

    @Test fun `a blank id is the OTHER guard's case and still throws loudly`() {
        // #121's comment forbids turning the blank-id refusal into a silent no-op: it exists to
        // make a fourth add path that primes before creating the account fail visibly. Answering
        // "this account was signed out" for a blank id would do exactly that, quietly.
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            checkAccountStillConfigured("", listOf(ACCOUNT))
        }
        assertTrue(
            "a blank id is reported as a signed-out account: $thrown",
            thrown !is AccountGoneException,
        )
        assertThrows(IllegalArgumentException::class.java) {
            checkAccountStillConfigured("   ", listOf(ACCOUNT))
        }
    }

    @Test fun `membership is exact, never a prefix`() {
        // `configuredIds.any { it.startsWith(id) }` would compile and pass every test above. Ids
        // are UUIDs today, so this is not reachable — it is here because the neighbouring cursor
        // rule (`cursorKeysOfAccount`) DOES read ids as prefixes, and the two must not be confused.
        assertThrows(AccountGoneException::class.java) {
            checkAccountStillConfigured("acc", listOf("acc-1", "acc-2"))
        }
        assertThrows(AccountGoneException::class.java) {
            checkAccountStillConfigured("acc-10", listOf("acc-1"))
        }
    }
}
