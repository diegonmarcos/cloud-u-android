package app.sterna.core.data.account

import app.sterna.core.data.filter.SourceText
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
 * "All inboxes (N)" is shown twice — in the drawer and on a home-screen widget, which breaks it
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UnifiedInboxUnreadTest {

    private fun account(id: String, inboxId: String?, unread: Int = 0) = StoredAccount(
        id = id,
        server = "https://mail.example.test",
        username = "$id@example.test",
        inboxId = inboxId,
        unread = unread,
    )

    /**
     * Stands in for `MailRepository.observeUnifiedInboxUnreadByAccount(scopes)` and records what
     */
    private class Counter(private val perScope: Map<Pair<String, String>, Int> = emptyMap()) {
        val calls = mutableListOf<List<Pair<String, String>>>()
        private val handed = mutableMapOf<List<Pair<String, String>>, MutableStateFlow<Map<String, Int>>>()

        fun unreadFor(scopes: List<Pair<String, String>>): Flow<Map<String, Int>> {
            calls += scopes
            return handed.getOrPut(scopes) {
                MutableStateFlow(scopes.associate { it.first to (perScope[it] ?: 0) })
            }
        }

        /** Emit [value] on the flow handed out for [scopes] — the Room invalidation that would
         *  reach a subscription nothing cancelled. */
        fun push(scopes: List<Pair<String, String>>, value: Map<String, Int>) {
            val flow = handed[scopes]
                ?: error("the counter was never asked for $scopes, so there is nothing to push to")
            flow.value = value
        }
    }

    @Test fun `only accounts with a known inbox are counted, and always as a pair`() = runTest {
        val counter = Counter()
        val accounts = MutableStateFlow(
            listOf(account("a", "inbox-1"), account("never-synced", inboxId = null)),
        )
        val seen = mutableListOf<Map<String, Int>>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            UnifiedInbox.unreadByAccount(accounts, counter::unreadFor).toList(seen)
        }
        runCurrent()

        assertEquals(
            "the counter must be asked for (accountId, inboxId) pairs of synced accounts only",
            listOf(listOf("a" to "inbox-1")),
            counter.calls,
        )
        assertEquals(
            "⛔ and the account with no inbox must be ABSENT from the breakdown, never a 0 — a 0 " +
                "on a widget line affirms 'no unread mail' about an inbox never seen.",
            listOf(mapOf("a" to 0)),
            seen,
        )
        job.cancel()
    }

    /**
     * Codeberg #121, executed. Two accounts on one server are routinely handed the same mailbox
     */
    @Test fun `two accounts sharing an inbox id stay two distinct scopes`() = runTest {
        val counter = Counter(mapOf(("a" to "42") to 3, ("b" to "42") to 5))
        val accounts = MutableStateFlow(listOf(account("a", "42"), account("b", "42")))
        val seen = mutableListOf<Map<String, Int>>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            UnifiedInbox.unreadByAccount(accounts, counter::unreadFor).toList(seen)
        }
        runCurrent()

        assertEquals(
            "a collision on the mailbox id must not fold the two accounts into one scope",
            listOf(listOf("a" to "42", "b" to "42")),
            counter.calls,
        )
        assertEquals("both inboxes counted, neither twice", listOf(mapOf("a" to 3, "b" to 5)), seen)
        job.cancel()
    }

    /** An account added, then one removed: the count follows, and stops reading what is gone. */
    @Test fun `an account list that changes is re-counted with the new scopes`() = runTest {
        val counter = Counter(mapOf(("a" to "ia") to 2, ("b" to "ib") to 7))
        val accounts = MutableStateFlow(listOf(account("a", "ia")))
        val seen = mutableListOf<Map<String, Int>>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            UnifiedInbox.unreadByAccount(accounts, counter::unreadFor).toList(seen)
        }
        runCurrent()

        accounts.value = listOf(account("a", "ia"), account("b", "ib"))
        runCurrent()
        accounts.value = listOf(account("b", "ib"))
        runCurrent()

        assertEquals(
            "the counter must be re-subscribed with the scopes as they are now",
            listOf(
                listOf("a" to "ia"),
                listOf("a" to "ia", "b" to "ib"),
                listOf("b" to "ib"),
            ),
            counter.calls,
        )
        assertEquals(
            "one account, then two, then the remaining one — and the removed account leaves the " +
                "breakdown rather than lingering at its last number",
            listOf(mapOf("a" to 2), mapOf("a" to 2, "b" to 7), mapOf("b" to 7)),
            seen,
        )
        job.cancel()
    }

    /**
     * The claim `flatMapLatest` makes, executed rather than believed: a removed account's
     */
    @Test fun `a removed account's inbox is unsubscribed, not just superseded`() = runTest {
        val counter = Counter(mapOf(("a" to "ia") to 2, ("b" to "ib") to 7))
        val accounts = MutableStateFlow(listOf(account("a", "ia"), account("b", "ib")))
        val seen = mutableListOf<Map<String, Int>>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            UnifiedInbox.unreadByAccount(accounts, counter::unreadFor).toList(seen)
        }
        runCurrent()

        accounts.value = listOf(account("b", "ib"))
        runCurrent()

        counter.push(listOf("a" to "ia", "b" to "ib"), mapOf("a" to 4242, "b" to 4242))
        runCurrent()

        assertEquals(
            "an emission on the flow of the PREVIOUS scopes must not reach the caller: with " +
                "flatMapMerge it arrives as a third value and the widget oscillates; with " +
                "flatMapConcat the new scopes are never subscribed at all, because the counter's " +
                "flow never completes.",
            listOf(mapOf("a" to 2, "b" to 7), mapOf("b" to 7)),
            seen,
        )
        job.cancel()
    }

    /**
     * The dedupe's ceiling: a write that moves NOTHING the total is built from must not re-count.
     */
    @Test fun `a write that touches nothing the count is built from does not re-count`() = runTest {
        val counter = Counter(mapOf(("a" to "ia") to 5))
        val stored = account("a", "ia", unread = 5)
        val accounts = MutableStateFlow(listOf(stored))
        val seen = mutableListOf<Map<String, Int>>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            UnifiedInbox.unreadByAccount(accounts, counter::unreadFor).toList(seen)
        }
        runCurrent()

        accounts.value = listOf(stored.copy(accountName = "Work", color = 0x336699))
        runCurrent()

        assertEquals(
            "renaming an account, or recolouring it, republishes the whole account list; the " +
                "count must be asked for ONCE all the same. Asking again cancels and rebuilds the " +
                "Room aggregates on the collector's context for a number that cannot have moved.",
            listOf(listOf("a" to "ia")),
            counter.calls,
        )
        assertEquals("and nothing new is emitted either", listOf(mapOf("a" to 5)), seen)
        job.cancel()
    }

    /**
     * The IMAP half, and the reason the dedupe key is NOT the derived scopes alone. An IMAP inbox
     */
    @Test fun `a stored counter moving re-counts even though the scopes are identical`() = runTest {
        val calls = mutableListOf<List<Pair<String, String>>>()
        val accounts = MutableStateFlow(listOf(account("imap", "INBOX", unread = 4)))
        val seen = mutableListOf<Map<String, Int>>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            UnifiedInbox.unreadByAccount(accounts) { scopes ->
                calls += scopes
                // The flat read MailRepository does for IMAP: whatever is stored at this instant.
                flowOf(scopes.associate { (id, _) -> id to accounts.value.first { it.id == id }.unread })
            }.toList(seen)
        }
        runCurrent()

        accounts.value = listOf(account("imap", "INBOX", unread = 9))
        runCurrent()

        assertEquals(
            "the same scopes must be asked again when the stored record changes",
            listOf(listOf("imap" to "INBOX"), listOf("imap" to "INBOX")),
            calls,
        )
        assertEquals(
            "the drawer follows the stored IMAP counter",
            listOf(mapOf("imap" to 4), mapOf("imap" to 9)),
            seen,
        )
        job.cancel()
    }

    /**
     * The single number is DERIVED from the breakdown, and this is what "derived" has to mean:
     */
    @Test fun `the single unified number is the breakdown added up`() {
        assertEquals(
            "'All inboxes (N)' must be the SUM of the per-account shares — not the largest, not " +
                "the first, and not how many accounts there are.",
            19,
            UnifiedInbox.total(mapOf("a" to 3, "b" to 5, "c" to 11)),
        )
    }

    /** No account, nothing counted: 0, and not an empty header or a crash on `max`. */
    @Test fun `no counted inbox totals zero`() {
        assertEquals(0, UnifiedInbox.total(emptyMap()))
    }

    /**
     * Whole line, not a `contains`: a fragment check passes on anything appended to it. This is
     */
    @Test fun `AccountStore derives the scopes through the shared rule, not a copy of it`() {
        val source = SourceText.read(
            "core/data/src/main/kotlin/app/sterna/core/data/account/AccountStore.kt",
        )
        assertEquals(
            "AccountStore.allInboxScopes no longer delegates to UnifiedInbox.scopes. The drawer " +
                "and the widget then derive 'all inboxes' from two pieces of code, which is the " +
                "one thing this whole file exists to prevent.",
            listOf("fun allInboxScopes(): List<Pair<String, String>> = UnifiedInbox.scopes(accounts())"),
            SourceText.codeLines(SourceText.functionSource(source, "fun allInboxScopes()")),
        )
    }

    /**
     * SOURCE LINT, and the one hole the tests above cannot reach: they inject their own account
     */
    @Test fun `MailRepository builds the breakdown on the live account flow`() {
        val source = SourceText.read(
            "core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt",
        )
        assertEquals(
            "MailRepository.observeUnifiedInboxUnreadByAccount() must hand " +
                "UnifiedInbox.unreadByAccount the LIVE accountStore.accountsFlow and delegate the " +
                "counting to the scoped overload. A snapshot (flowOf(accountStore.accounts())) " +
                "compiles, keeps every other test green, and freezes the drawer and the widget at " +
                "the number they started with.",
            listOf(
                "fun observeUnifiedInboxUnreadByAccount(): Flow<Map<String, Int>> = UnifiedInbox.unreadByAccount(",
                "accountStore.accountsFlow,",
                ") { scopes -> observeUnifiedInboxUnreadByAccount(scopes) }",
            ),
            SourceText.codeLines(
                SourceText.functionSource(
                    source,
                    "fun observeUnifiedInboxUnreadByAccount(): Flow<Map<String, Int>>",
                ),
            ),
        )
    }

    /**
     * And the single number is DERIVED from that one subscription, not observed a second time.
     */
    @Test fun `the single number is derived from the breakdown, not observed a second time`() {
        val source = SourceText.read(
            "core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt",
        )
        assertEquals(
            "MailRepository.observeUnifiedInboxUnread() must be the breakdown, totalled — one " +
                "DERIVATION for the drawer and the widget alike. ⛔ Not one subscription: nothing " +
                "shares this flow, so each caller still builds its own combine. What is held here " +
                "is that there is only one rule, so the two numbers cannot disagree.",
            listOf(
                "fun observeUnifiedInboxUnread(): Flow<Int> =",
                "observeUnifiedInboxUnreadByAccount().map(UnifiedInbox::total)",
            ),
            SourceText.codeLines(
                SourceText.functionSource(source, "fun observeUnifiedInboxUnread(): Flow<Int>"),
            ),
        )
    }
}
