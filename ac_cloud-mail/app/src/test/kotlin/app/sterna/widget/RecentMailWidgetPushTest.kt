package app.sterna.widget

import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.db.RecentEmailRow
import app.sterna.core.data.mail.RecentInbox
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The latest-messages widget's push, RUN — not read as source. What is pinned here is not that the
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RecentMailWidgetPushTest {

    private fun row(id: String, seen: Boolean = false) = RecentEmailRow(
        id = id,
        accountId = "acc",
        mailboxId = "inbox",
        subject = "subj-$id",
        fromName = "Nell",
        fromEmail = "nell@example.test",
        seen = seen,
        sortKey = 1L,
        receivedAt = "2026-08-18T10:00:00Z",
    )

    /** A read that answered: accounts were known, and these are their latest messages. */
    private fun read(vararg rows: RecentEmailRow) = RecentInbox(configured = true, rows = rows.toList())

    /** The other empty: no inbox could be read at all, so the cell may claim nothing. */
    private val nothingToSay = RecentInbox(configured = false, rows = emptyList())

    /** Nothing but the id matters here: the bound this pane follows is the list's SIZE. */
    private fun account(id: String) = StoredAccount(id = id, server = "s", username = "u")

    /** Two accounts: above [AllInboxesView.existsFor], so the rows carry their colour dots. */
    private val twoAccounts = listOf(account("a"), account("b"))

    /**
     * Stands in for `MailRepository.observeRecentUnifiedInbox(limit)`, and records two facts a
     */
    private class Reader(private val initial: (Int) -> RecentInbox) {
        val asked = mutableListOf<String>()
        val subscribed = mutableListOf<String>()
        private val handed = mutableListOf<MutableStateFlow<RecentInbox>>()

        fun inbox(): Flow<RecentInbox> {
            val index = handed.size
            asked += "ask#$index"
            val flow = MutableStateFlow(initial(index))
            handed += flow
            return flow.onStart { subscribed += "collect#$index" }
        }

        /** The Room invalidation that would reach a subscription nothing cancelled. */
        fun push(index: Int, inbox: RecentInbox) {
            val flow = handed.getOrNull(index)
                ?: error("the reader was never asked a ${index + 1}th time — nothing to push to")
            flow.value = inbox
        }
    }

    private fun collectPush(
        placed: Flow<Boolean>,
        reader: Reader,
        seen: MutableList<RecentInbox>,
        scope: kotlinx.coroutines.CoroutineScope,
        dispatcher: TestDispatcher,
        accounts: Flow<List<StoredAccount>> = MutableStateFlow(twoAccounts),
    ) = scope.launch(dispatcher) {
        RecentMailWidgetPush.redraws(placed, accounts, reader::inbox).toList(seen)
    }

    /**
     * The rule this file exists for: an install with no latest-messages cell on any home screen
     */
    @Test fun `no widget placed means the inbox is never even asked for`() = runTest {
        val reader = Reader { read(row("r$it")) }
        val seen = mutableListOf<RecentInbox>()
        val job = collectPush(MutableStateFlow(false), reader, seen, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        runCurrent()

        assertEquals(
            "with no cell placed the read factory must not be called: collecting " +
                "observeRecentUnifiedInbox() subscribes a Room query woken by every write to " +
                "emails and to snoozed, for the whole life of the process, and the majority of " +
                "installs will never place this widget.",
            emptyList<String>(),
            reader.asked,
        )
        assertEquals("and nothing is subscribed either", emptyList<String>(), reader.subscribed)
        assertEquals("so nothing asks for a redraw", emptyList<RecentInbox>(), seen)
        job.cancel()
    }

    /**
     * The first cell placed opens the gate, and what happens afterwards arrives — this is the
     * measured defect, in one test: a message marked read moves the rows, and the redraw follows.
     */
    @Test fun `the first widget placed starts the collection, and later reads arrive`() = runTest {
        val reader = Reader { read(row("r$it")) }
        val placed = MutableStateFlow(false)
        val seen = mutableListOf<RecentInbox>()
        val job = collectPush(placed, reader, seen, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        runCurrent()

        placed.value = true
        runCurrent()
        reader.push(0, read(row("r0", seen = true)))
        runCurrent()

        assertEquals("the inbox is asked for exactly once, when the gate opens", listOf("ask#0"), reader.asked)
        assertEquals("and that flow is really collected", listOf("collect#0"), reader.subscribed)
        assertEquals(
            "the read at subscription time asks for a redraw, and so does every change after it " +
                "— a cell that only followed the mail when a message ARRIVED is the defect this " +
                "pane closes: the message read in the app keeps its unread dot on the home screen.",
            listOf(read(row("r0")), read(row("r0", seen = true))),
            seen,
        )
        job.cancel()
    }

    /**
     * The claim [kotlinx.coroutines.flow.flatMapLatest] makes, executed rather than believed: the
     */
    @Test fun `the last widget removed really stops the collection`() = runTest {
        val reader = Reader { read(row("r$it")) }
        val placed = MutableStateFlow(true)
        val seen = mutableListOf<RecentInbox>()
        val job = collectPush(placed, reader, seen, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        runCurrent()

        placed.value = false
        runCurrent()
        reader.push(0, read(row("gone")))
        runCurrent()

        assertEquals(
            "an emission on the flow handed out while the cell was placed must not reach anything " +
                "once the last one is gone. The Room flow never completes, so a subscription that " +
                "was not cancelled goes on arriving here forever.",
            listOf(read(row("r0"))),
            seen,
        )
        assertEquals("and it is not asked again on the way out", listOf("ask#0"), reader.asked)
        job.cancel()
    }

    /** A cell placed again after the last one was removed gets a FRESH subscription. */
    @Test fun `a widget placed again is followed again, on a new subscription`() = runTest {
        val reader = Reader { read(row("r$it")) }
        val placed = MutableStateFlow(true)
        val seen = mutableListOf<RecentInbox>()
        val job = collectPush(placed, reader, seen, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        runCurrent()

        placed.value = false
        runCurrent()
        placed.value = true
        runCurrent()

        assertEquals("the second placement asks again", listOf("ask#0", "ask#1"), reader.asked)
        assertEquals("and collects the new flow", listOf("collect#0", "collect#1"), reader.subscribed)
        assertEquals(
            "the cell is redrawn from the second subscription",
            listOf(read(row("r0")), read(row("r1"))),
            seen,
        )
        job.cancel()
    }

    /**
     * The gate's ceiling: an answer that has not CHANGED must not re-subscribe. Presence is fed by
     */
    @Test fun `the same answer twice does not re-subscribe the inbox`() = runTest {
        val reader = Reader { read(row("r$it")) }
        val seen = mutableListOf<RecentInbox>()
        val job = collectPush(flowOf(true, true), reader, seen, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        runCurrent()

        assertEquals(
            "'a cell is placed' said twice is one gate opening, not two: re-subscribing cancels " +
                "and rebuilds the observed inbox query for nothing.",
            listOf("ask#0"),
            reader.asked,
        )
        assertEquals("and the cell is redrawn once", listOf(read(row("r0"))), seen)
        job.cancel()
    }

    /**
     * THE SAME READ TWICE IS ONE REDRAW. Nothing upstream says so: Room invalidates per TABLE,
     */
    @Test fun `the same read arriving twice is drawn once`() = runTest {
        val first = read(row("a"), row("b"))
        val second = read(row("a", seen = true), row("b"))
        val seen = mutableListOf<RecentInbox>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            RecentMailWidgetPush.redraws(MutableStateFlow(true), MutableStateFlow(twoAccounts)) {
                flowOf(first, first, second, second, first)
            }.toList(seen)
        }
        runCurrent()

        assertEquals(
            "a read that has not moved must not reach the draw. Room invalidates the whole table, " +
                "so one sync page re-runs this query and answers identically, and each one that " +
                "got through cost a binder call, a DataStore read, a decode of the account blob " +
                "and a re-inflation in the launcher — for a cell already showing those rows.",
            listOf(first, second, first),
            seen,
        )
        job.cancel()
    }

    /**
     * THE TWO EMPTIES ARE NOT THE SAME EMPTY, and this is the test the first version of this
     */
    @Test fun `an empty inbox and no account at all are two different redraws`() = runTest {
        val seen = mutableListOf<RecentInbox>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            RecentMailWidgetPush.redraws(MutableStateFlow(true), MutableStateFlow(twoAccounts)) {
                flowOf(read(), nothingToSay, read())
            }.toList(seen)
        }
        runCurrent()

        assertEquals(
            "an empty inbox followed by no account at all must reach the draw TWICE: the two " +
                "carry the same (empty) rows and are drawn as different sentences — 'No messages' " +
                "and the app's own name. Swallowing the second leaves the home screen affirming " +
                "an empty inbox to a reader who no longer has one to be empty.",
            listOf(read(), nothingToSay, read()),
            seen,
        )
        job.cancel()
    }

    /**
     * THE ROWS DO NOT MOVE AND THE CELL MUST STILL BE REDRAWN — the defect this pane closes.
     */
    @Test fun `crossing the two-account bound redraws, on rows that did not move`() = runTest {
        val reader = Reader { read(row("r$it")) }
        val accounts = MutableStateFlow(twoAccounts)
        val seen = mutableListOf<RecentInbox>()
        val job = collectPush(
            MutableStateFlow(true), reader, seen, backgroundScope,
            UnconfinedTestDispatcher(testScheduler), accounts,
        )
        runCurrent()

        accounts.value = listOf(account("a"))
        runCurrent()
        accounts.value = twoAccounts
        runCurrent()

        assertEquals(
            "two accounts becoming one must ask for a redraw (every dot has to go), and one " +
                "becoming two must ask again (they all come back) — on rows that are strictly " +
                "identical throughout. Swallowed, the home screen keeps the dots of an account " +
                "that was removed, and there is no timer to catch it: updatePeriodMillis is 0.",
            listOf(read(row("r0")), read(row("r0")), read(row("r0"))),
            seen,
        )
        assertEquals("and the inbox query is NOT re-subscribed for it", listOf("ask#0"), reader.asked)
        assertEquals("still one subscription", listOf("collect#0"), reader.subscribed)
        job.cancel()
    }

    /**
     * THE PRICE OF THE ARM ABOVE, and the reason it carries a BOUND and not the account list.
     */
    @Test fun `an account list that does not cross the bound costs nothing`() = runTest {
        val reader = Reader { read(row("r$it")) }
        val accounts = MutableStateFlow(listOf(account("a"), account("b"), account("c")))
        val seen = mutableListOf<RecentInbox>()
        val job = collectPush(
            MutableStateFlow(true), reader, seen, backgroundScope,
            UnconfinedTestDispatcher(testScheduler), accounts,
        )
        runCurrent()

        accounts.value = twoAccounts
        runCurrent()
        accounts.value = listOf(account("d"), account("e"))
        runCurrent()

        assertEquals(
            "three accounts becoming two, then two others, never crosses the bound: the lines are " +
                "drawn exactly the same way, so the only redraw is the one the subscription itself " +
                "asked for. Every extra one costs a binder call, a DataStore read and a launcher " +
                "re-inflation, on each of the ~27 writes that go through saveAccounts.",
            listOf(read(row("r0"))),
            seen,
        )
        assertEquals(
            "and above all the inbox is not asked again: the observed Room query is keyed on the " +
                "folder scopes, and rebuilding it on every saveAccounts is the expense this arm " +
                "was designed around.",
            listOf("ask#0"),
            reader.asked,
        )
        assertEquals("nor re-subscribed", listOf("collect#0"), reader.subscribed)
        job.cancel()
    }
    /**
     * ONE ACCOUNT IS NOT AN ABSENCE OF ACCOUNTS, and this test exists because the arm added for
     */
    @Test fun `a single account still follows the mail`() = runTest {
        val reader = Reader { read(row("r$it")) }
        val seen = mutableListOf<RecentInbox>()
        val job = collectPush(
            MutableStateFlow(true), reader, seen, backgroundScope,
            UnconfinedTestDispatcher(testScheduler), MutableStateFlow(listOf(account("a"))),
        )
        runCurrent()

        reader.push(0, read(row("r0", seen = true)))
        runCurrent()

        assertEquals(
            "below the two-account bound the dots are never drawn, and the rows must be followed " +
                "exactly the same way: the read at subscription time, then the message being read " +
                "in the app. An arm that stayed silent under the bound would strand the combine " +
                "and freeze the cell for every single-account install.",
            listOf(read(row("r0")), read(row("r0", seen = true))),
            seen,
        )
        job.cancel()
    }

    /**
     * THE GATE GOVERNS BOTH ARMS, and this is the half a combine placed outside [flatMapLatest]
     */
    @Test fun `an account list that moves while no cell is placed redraws nothing`() = runTest {
        val reader = Reader { read(row("r$it")) }
        val placed = MutableStateFlow(true)
        val accounts = MutableStateFlow(twoAccounts)
        val seen = mutableListOf<RecentInbox>()
        val job = collectPush(
            placed, reader, seen, backgroundScope,
            UnconfinedTestDispatcher(testScheduler), accounts,
        )
        runCurrent()

        placed.value = false
        runCurrent()
        accounts.value = listOf(account("a"))
        runCurrent()

        assertEquals(
            "the last cell is gone, so crossing the two-account bound must reach nothing: there " +
                "are no dots on any home screen to correct, and the gate is what this whole file " +
                "is for.",
            listOf(read(row("r0"))),
            seen,
        )
        job.cancel()
    }
}
