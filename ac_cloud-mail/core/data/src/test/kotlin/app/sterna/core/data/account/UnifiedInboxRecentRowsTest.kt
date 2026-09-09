package app.sterna.core.data.account

import app.sterna.core.data.db.RecentEmailRow
import app.sterna.core.data.filter.SourceText
import app.sterna.core.data.mail.RecentInbox
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WHICH inboxes the "latest messages" home-screen widget follows, and WHEN it asks again — pinned
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UnifiedInboxRecentRowsTest {

    private fun account(id: String, inboxId: String?, unread: Int = 0) = StoredAccount(
        id = id,
        server = "https://mail.example.test",
        username = "$id@example.test",
        inboxId = inboxId,
        unread = unread,
    )

    private fun row(id: String, accountId: String) = RecentEmailRow(
        id = id,
        accountId = accountId,
        mailboxId = "inbox",
        subject = "subj-$id",
        fromName = null,
        fromEmail = "someone@example.test",
        seen = false,
        sortKey = 1L,
        receivedAt = "2026-08-18T10:00:00Z",
    )

    /** A read that answered: the accounts were known, and these are their latest messages. */
    private fun read(vararg rows: RecentEmailRow) = RecentInbox(configured = true, rows = rows.toList())

    /**
     * Stands in for `MailRepository.observeRecentUnifiedInbox(limit)` and records what it was
     */
    private class Reader {
        val calls = mutableListOf<List<Pair<String, String>>>()
        private val handed = mutableMapOf<List<Pair<String, String>>, MutableStateFlow<List<RecentEmailRow>>>()

        fun rowsFor(scopes: List<Pair<String, String>>): Flow<List<RecentEmailRow>> {
            calls += scopes
            return handed.getOrPut(scopes) { MutableStateFlow(scopes.map { (id, _) -> seed(id) }) }
        }

        /** The Room invalidation that would reach a subscription nothing cancelled. */
        fun push(scopes: List<Pair<String, String>>, rows: List<RecentEmailRow>) {
            val flow = handed[scopes]
                ?: error("the reader was never asked for $scopes, so there is nothing to push to")
            flow.value = rows
        }

        companion object {
            /** One row per account in the scope list, so the collected value names its accounts. */
            fun seed(accountId: String) = RecentEmailRow(
                id = "seed-$accountId",
                accountId = accountId,
                mailboxId = "inbox",
                subject = null,
                fromName = null,
                fromEmail = null,
                seen = false,
                sortKey = 0L,
                receivedAt = null,
            )
        }
    }

    @Test fun `only accounts with a known inbox are read, and always as a pair`() = runTest {
        val reader = Reader()
        val accounts = MutableStateFlow(
            listOf(account("a", "inbox-1"), account("never-synced", inboxId = null)),
        )
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            UnifiedInbox.recentRows(accounts, reader::rowsFor).toList(mutableListOf())
        }
        runCurrent()

        assertEquals(
            "the widget read must be asked for (accountId, inboxId) pairs of synced accounts only " +
                "— an account whose inbox id is still unknown has no folder to read, and a made-up " +
                "scope would read someone else's.",
            listOf(listOf("a" to "inbox-1")),
            reader.calls,
        )
        job.cancel()
    }

    /**
     * Codeberg #121, on this read too. Two accounts on one server are routinely handed the same
     */
    @Test fun `two accounts sharing an inbox id stay two distinct scopes, in order`() = runTest {
        val reader = Reader()
        val accounts = MutableStateFlow(listOf(account("a", "42"), account("b", "42")))
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            UnifiedInbox.recentRows(accounts, reader::rowsFor).toList(mutableListOf())
        }
        runCurrent()

        assertEquals(
            "a collision on the mailbox id must not fold the two accounts into one scope",
            listOf(listOf("a" to "42", "b" to "42")),
            reader.calls,
        )
        job.cancel()
    }

    /**
     * THE ARBITRAGE THIS FILE EXISTS TO PIN, and the one place this rule DIVERGES from
     */
    @Test fun `a stored counter moving does not re-subscribe the rows`() = runTest {
        val reader = Reader()
        val stored = account("imap", "INBOX", unread = 4)
        val accounts = MutableStateFlow(listOf(stored))
        val seen = mutableListOf<RecentInbox>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            UnifiedInbox.recentRows(accounts, reader::rowsFor).toList(seen)
        }
        runCurrent()

        accounts.value = listOf(stored.copy(unread = 9, accountName = "Work", color = 0x336699))
        runCurrent()

        assertEquals(
            "the stored unread counter changing, an account renamed, an account recoloured: none " +
                "of them can move a row that comes from Room, and each would otherwise cancel and " +
                "rebuild the observed query and redraw the cell.",
            listOf(listOf("imap" to "INBOX")),
            reader.calls,
        )
        assertEquals("and nothing new is emitted either", listOf(read(Reader.seed("imap"))), seen)
        job.cancel()
    }

    /** An account added, then one removed: the read follows, with the scopes as they are now. */
    @Test fun `an account list that changes is re-read with the new scopes`() = runTest {
        val reader = Reader()
        val accounts = MutableStateFlow(listOf(account("a", "ia")))
        val seen = mutableListOf<RecentInbox>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            UnifiedInbox.recentRows(accounts, reader::rowsFor).toList(seen)
        }
        runCurrent()

        accounts.value = listOf(account("a", "ia"), account("b", "ib"))
        runCurrent()
        accounts.value = listOf(account("b", "ib"))
        runCurrent()

        assertEquals(
            "the reader must be re-subscribed with the scopes as they are now",
            listOf(
                listOf("a" to "ia"),
                listOf("a" to "ia", "b" to "ib"),
                listOf("b" to "ib"),
            ),
            reader.calls,
        )
        assertEquals(
            "and the cell is redrawn from each of them in turn",
            listOf(
                read(Reader.seed("a")),
                read(Reader.seed("a"), Reader.seed("b")),
                read(Reader.seed("b")),
            ),
            seen,
        )
        job.cancel()
    }

    /**
     * The claim `flatMapLatest` makes, executed rather than believed: a removed account's
     */
    @Test fun `a removed account's rows are unsubscribed, not just superseded`() = runTest {
        val reader = Reader()
        val accounts = MutableStateFlow(listOf(account("a", "ia"), account("b", "ib")))
        val seen = mutableListOf<RecentInbox>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            UnifiedInbox.recentRows(accounts, reader::rowsFor).toList(seen)
        }
        runCurrent()

        accounts.value = listOf(account("b", "ib"))
        runCurrent()
        reader.push(listOf("a" to "ia", "b" to "ib"), listOf(row("ghost", "a")))
        runCurrent()

        assertEquals(
            "an emission on the flow of the PREVIOUS scopes must not reach the redraw: with " +
                "flatMapMerge it arrives as a third value and the cell oscillates; with " +
                "flatMapConcat the new scopes are never subscribed at all, because a Room flow " +
                "never completes.",
            listOf(
                read(Reader.seed("a"), Reader.seed("b")),
                read(Reader.seed("b")),
            ),
            seen,
        )
        job.cancel()
    }

    /**
     * NO ACCOUNT LEFT IS ONE EMISSION, IT SAYS `configured = false`, AND IT READS NOTHING.
     */
    @Test fun `no account left emits one unconfigured read and reads nothing`() = runTest {
        val reader = Reader()
        val accounts = MutableStateFlow(listOf(account("a", "ia")))
        val seen = mutableListOf<RecentInbox>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            UnifiedInbox.recentRows(accounts, reader::rowsFor).toList(seen)
        }
        runCurrent()

        accounts.value = emptyList()
        runCurrent()

        assertEquals(
            "the last account removed must ask for a redraw exactly once, and that redraw must " +
                "say configured = false — 'nothing to affirm', which the cell draws as the app's " +
                "own name. configured = true with no rows is the OTHER empty, drawn 'No messages', " +
                "and telling someone who has just removed their last account that their mail is " +
                "empty is the claim RecentInbox exists to refuse.",
            listOf(read(Reader.seed("a")), RecentInbox(configured = false, rows = emptyList())),
            seen,
        )
        assertEquals(
            "and it must not build a scope-less query: that statement matches nothing and still " +
                "subscribes Room to every write to the mail table.",
            listOf(listOf("a" to "ia")),
            reader.calls,
        )
        job.cancel()
    }

    /**
     * SOURCE LINT, and the hole the tests above cannot reach: they inject their own account flow
     */
    @Test fun `MailRepository observes the SAME widget query, over the live account flow`() {
        val source = SourceText.read(
            "core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt",
        )
        assertEquals(
            "MailRepository.observeRecentUnifiedInbox must hand UnifiedInbox.recentRows the LIVE " +
                "accountStore.accountsFlow and read through recentUnifiedQuery — the statement " +
                "recentUnifiedInbox already runs. A snapshot freezes the widget at the accounts it " +
                "started with; a second SQL is a twin that drifts from the list's own predicate.",
            listOf(
                "fun observeRecentUnifiedInbox(limit: Int): Flow<RecentInbox> = UnifiedInbox.recentRows(",
                "accountStore.accountsFlow,",
                ") { scopes -> emailDao.observeRecentUnified(recentUnifiedQuery(scopes, limit)) }",
            ),
            SourceText.codeLines(
                SourceText.functionSource(source, "fun observeRecentUnifiedInbox(limit: Int)"),
            ),
        )
    }

    /**
     * SOURCE LINT, and the only kind available for this one: what makes the widget's read REACTIVE
     */
    @Test fun `the widget's observed read is woken by BOTH the mail and the snoozes`() {
        val lines = SourceText.codeLines(
            SourceText.read("core/data/src/main/kotlin/app/sterna/core/data/db/EmailDao.kt"),
        )
        val at = lines.indexOfFirst { it.startsWith("fun observeRecentUnified(") }
        check(at > 0) { "EmailDao no longer declares `fun observeRecentUnified(` — was it renamed?" }
        assertEquals(
            "EmailDao.observeRecentUnified must be a @RawQuery observing EmailEntity AND " +
                "SnoozedEntity. Without the first the widget stops following the mail at all; " +
                "without the second a snooze expiring never redraws the cell. Neither can be " +
                "caught by running anything: this suite does not run Room.",
            listOf(
                "@RawQuery(observedEntities = [EmailEntity::class, SnoozedEntity::class])",
                "fun observeRecentUnified(query: SupportSQLiteQuery): Flow<List<RecentEmailRow>>",
            ),
            lines.subList(at - 1, at + 1),
        )
    }
}
