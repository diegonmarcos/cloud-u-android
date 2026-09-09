package app.sterna.core.data.db

import app.sterna.core.data.mail.DaoQuerySource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The count on the overflow menu's Outbox entry (#70): what is in the Outbox **right now**.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OutboxQueuedCountTest {
    private val grace = OutboxLogic.BADGE_GRACE_MILLIS

    /** The undo window of a normal send: the row is only allowed to leave once it elapses. */
    private val window = 5_000L

    /**
     * "Now", read once from the real clock — every deadline below is expressed against it, so that a
     * clock consulted inside the count cannot be hidden by fixtures from 1970. See the class KDoc.
     */
    private val anchor = System.currentTimeMillis()

    /** A message queued this instant, still inside its undo window. */
    private fun justQueued(state: OutboxState = OutboxState.HELD) =
        OutboxBadgeItem(state, notBeforeMillis = anchor + window)

    /** A message queued this instant with no undo window (holdMs = 0): free to leave at once. */
    private fun queuedWithNoWindow(state: OutboxState = OutboxState.QUEUED) =
        OutboxBadgeItem(state, notBeforeMillis = anchor)

    // ---------------------------------------------------------------- the count itself

    /**
     * Every state the outbox can be in, decided one by one. Driven off [OutboxState.entries] so a
     * sixth state cannot be added without someone deciding here whether it is in the Outbox or not.
     */
    @Test fun everyStateCountsExceptTheOneOpenInTheComposer() {
        OutboxState.entries.forEach { state ->
            val expected = if (state == OutboxState.EDITING) 0 else 1
            assertEquals(
                "a $state row queued this instant is ${if (expected == 1) "in" else "not in"} the Outbox",
                expected,
                OutboxLogic.queuedCount(listOf(justQueued(state))),
            )
        }
    }

    /**
     * THE REPORTER'S CASE, on real timestamps. He is offline, the send is queued, its undo window
     */
    @Test fun theReportersCaseWithRealTimestamps() {
        val queuedRightNow = listOf(justQueued())

        assertEquals(
            "the menu must already say 1 (#70)",
            1, OutboxLogic.queuedCount(queuedRightNow),
        )
        assertEquals(
            "and the dot must still be dark at that same instant (#82)",
            0, OutboxLogic.activeCount(queuedRightNow, now = anchor),
        )
    }

    /**
     * The count reads no clock at all, stated as a property rather than as a scenario: the same row
     */
    @Test fun theCountIgnoresTheClockEntirely() {
        val hour = 3_600_000L
        listOf(anchor - hour, anchor - grace, anchor - 1, anchor, anchor + window, anchor + hour)
            .forEach { deadline ->
                assertEquals(
                    "a message waiting in the Outbox is counted whatever its deadline " +
                        "(${deadline - anchor}ms from now): this count has no clock",
                    1,
                    OutboxLogic.queuedCount(listOf(OutboxBadgeItem(OutboxState.HELD, deadline))),
                )
            }
    }

    /** Nothing queued, nothing announced — the entry carries no badge at all. */
    @Test fun anEmptyOutboxIsCountedAsZero() {
        assertEquals(0, OutboxLogic.queuedCount(emptyList()))
    }

    @Test fun severalWaitingMessagesAreAllCounted() {
        val rows = listOf(
            justQueued(OutboxState.HELD),
            queuedWithNoWindow(OutboxState.QUEUED),
            queuedWithNoWindow(OutboxState.SENDING),
            queuedWithNoWindow(OutboxState.FAILED),
            queuedWithNoWindow(OutboxState.EDITING), // held by the composer
        )
        assertEquals(4, OutboxLogic.queuedCount(rows))
    }

    /**
     * The count is a photograph, not a countdown: the same rows give the same number an hour later.
     * (The dot's count does move on its own — that is [OutboxLogic.activeCount]'s job, not this one.)
     */
    @Test fun theCountNeverChangesOnItsOwnHoweverLongTheMessageWaits() {
        val rows = listOf(justQueued())
        val first = OutboxLogic.queuedCount(rows)

        assertEquals(first, OutboxLogic.queuedCount(rows))
        // Same rows, and the number the menu shows is the same at every instant of the timeline.
        assertEquals(1, first)
    }

    // ---------------------------------------------------------------- the asymmetry, on the shipped paths

    /**
     * The whole point of the change, checked on the two shipped paths at once: the dot goes through
     */
    @Test fun theDotStaysDarkThroughTheGraceWhileTheMenuAlreadyShowsTheMessage() = runTest {
        val rows = MutableStateFlow(listOf(justQueued()))
        val dot = mutableListOf<Int>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            OutboxLogic.badgeCount(rows) { anchor + testScheduler.currentTime }.toList(dot)
        }
        runCurrent()

        assertEquals("the dot must not appear the moment a send is queued (#82)", listOf(0), dot)
        assertEquals("but the menu entry must (#70)", 1, OutboxLogic.queuedCount(rows.value))

        advanceTimeBy(window + 1) // the undo window has closed: the worker may now take the row
        assertEquals("the dot must not light up the moment the send is released (#82)", listOf(0), dot)
        assertEquals("the message is in the Outbox all the same", 1, OutboxLogic.queuedCount(rows.value))

        advanceTimeBy(grace - 2) // one millisecond short of the dot's threshold
        assertEquals("still nothing on the toolbar", listOf(0), dot)
        assertEquals("and still one message in the Outbox", 1, OutboxLogic.queuedCount(rows.value))

        advanceTimeBy(2) // the grace runs out; the message never left
        assertEquals("now the dot too", listOf(0, 1), dot)
        assertEquals("the menu says what it always said", 1, OutboxLogic.queuedCount(rows.value))

        rows.value = emptyList() // the network came back and the message went out
        runCurrent()
        assertEquals("both fall silent together when the row leaves", listOf(0, 1, 0), dot)
        assertEquals(0, OutboxLogic.queuedCount(rows.value))
        job.cancel()
    }

    /**
     * Guard on the grace itself rather than on one scenario: over the first half-minute of a send,
     */
    @Test fun theTwoCountsDisagreeForTheWholeLengthOfTheGrace() {
        val rows = listOf(queuedWithNoWindow())
        assertTrue("the dot's grace is what buys the silence; it must not be zero", grace > 0)

        // Sampled across the grace, not just at its ends — on the real timeline, counted from the
        // instant the row was queued.
        listOf(0L, 1L, grace / 2, grace - 1).forEach { elapsed ->
            val now = anchor + elapsed
            assertEquals("the menu at ${elapsed}ms", 1, OutboxLogic.queuedCount(rows))
            assertEquals("the dot at ${elapsed}ms", 0, OutboxLogic.activeCount(rows, now))
            assertNotEquals(OutboxLogic.queuedCount(rows), OutboxLogic.activeCount(rows, now))
        }
        // Past the grace they finally say the same thing.
        assertEquals(1, OutboxLogic.activeCount(rows, now = anchor + grace))
    }

    /**
     * A failure is the one case where the two already agreed, and must keep agreeing: it counts on
     * both at once, with no delay on either side.
     */
    @Test fun aFailedMessageIsShownAtOnceOnBothIndicators() {
        val rows = listOf(queuedWithNoWindow(OutboxState.FAILED))
        assertEquals(1, OutboxLogic.queuedCount(rows))
        assertEquals(1, OutboxLogic.activeCount(rows, now = anchor))
    }

    // ---------------------------------------------------------------- the WYSIWYG invariant

    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(OUTBOX_CREATE_SQL)
            // The columns later migrations added, so the shipped queries run against the table as
            // it is today rather than as it was at v10.
            st.executeUpdate("ALTER TABLE `outbox` ADD COLUMN `pgpMode` TEXT")
            st.executeUpdate("ALTER TABLE `outbox` ADD COLUMN `pgpEntityPath` TEXT")
            st.executeUpdate("ALTER TABLE `outbox` ADD COLUMN `draftEmailId` TEXT")
        }
    }

    @After fun tearDown() = db.close()

    private fun insert(state: OutboxState, notBefore: Long, createdAt: Long) {
        db.prepareStatement(
            "INSERT INTO `outbox`(accountId, recipients, subject, textBody, attachmentsJson, " +
                "createdAtMillis, notBeforeMillis, state, attemptCount) " +
                "VALUES ('a', 'to@example.org', 's', 'b', '[]', ?, ?, ?, 0)",
        ).use { st ->
            st.setLong(1, createdAt)
            st.setLong(2, notBefore)
            st.setString(3, state.name)
            st.executeUpdate()
        }
    }

    /**
     * The contract of this fix: the number on the menu entry is, at every instant, exactly the
     */
    @Test fun theMenuCountIsExactlyTheNumberOfRowsTheOutboxScreenLists() {
        insert(OutboxState.HELD, notBefore = anchor + window, createdAt = anchor + 1)
        insert(OutboxState.QUEUED, notBefore = anchor, createdAt = anchor + 2)
        insert(OutboxState.SENDING, notBefore = anchor, createdAt = anchor + 3)
        insert(OutboxState.FAILED, notBefore = anchor, createdAt = anchor + 4)
        insert(OutboxState.EDITING, notBefore = anchor, createdAt = anchor + 5) // open in the composer
        insert(OutboxState.HELD, notBefore = anchor + window, createdAt = anchor + 6) // queued a second ago

        // What the Outbox screen lists: observeAll, filtered the way outboxFlow filters it.
        val listed = listedStates()
        val onScreen = listed.filter { OutboxLogic.isWaitingInOutbox(it) }

        // What the menu entry announces: observeBadgeItems, counted the way outboxQueuedCount counts.
        val badgeItems = badgeItems()

        assertEquals("both queries must see the same rows of the same table", listed.size, badgeItems.size)
        assertEquals(
            "the count must equal the number of lines the screen shows",
            onScreen.size,
            OutboxLogic.queuedCount(badgeItems),
        )
        // Not a pair of zeroes agreeing by accident: six rows in, one of them being edited.
        assertEquals(6, listed.size)
        assertEquals(5, onScreen.size)
    }

    /**
     * The invariant again, on the state that breaks it if anything does: the row taken out for
     */
    @Test fun theRowOpenInTheComposerLeavesBothTheListAndTheCountTogether() {
        insert(OutboxState.QUEUED, notBefore = anchor, createdAt = anchor + 1)
        insert(OutboxState.HELD, notBefore = anchor + window, createdAt = anchor + 2)
        assertEquals(2, countThroughBothPaths())

        db.createStatement().use {
            it.executeUpdate("UPDATE `outbox` SET state = 'EDITING' WHERE createdAtMillis = ${anchor + 2}")
        }
        assertEquals("taken out to be edited: off the list AND off the count", 1, countThroughBothPaths())

        db.createStatement().use {
            it.executeUpdate("UPDATE `outbox` SET state = 'HELD' WHERE createdAtMillis = ${anchor + 2}")
        }
        assertEquals("given back to the queue: on both again", 2, countThroughBothPaths())
    }

    /** The states `observeAll` — the Outbox screen's own query — hands back, in its own order. */
    private fun listedStates(): List<OutboxState> {
        val listed = mutableListOf<OutboxState>()
        db.createStatement().use { st ->
            st.executeQuery(DaoQuerySource.daoQuery("OutboxDao", "observeAll")).use { rs ->
                while (rs.next()) listed += OutboxState.valueOf(rs.getString("state"))
            }
        }
        return listed
    }

    /** The rows `observeBadgeItems` — the count's own query — hands back. */
    private fun badgeItems(): List<OutboxBadgeItem> {
        val items = mutableListOf<OutboxBadgeItem>()
        db.createStatement().use { st ->
            st.executeQuery(DaoQuerySource.daoQuery("OutboxDao", "observeBadgeItems")).use { rs ->
                while (rs.next()) {
                    items += OutboxBadgeItem(
                        OutboxState.valueOf(rs.getString("state")),
                        rs.getLong("notBeforeMillis"),
                    )
                }
            }
        }
        return items
    }

    /** Runs both shipped paths over the current table and asserts they agree, returning the count. */
    private fun countThroughBothPaths(): Int {
        val count = OutboxLogic.queuedCount(badgeItems())
        assertEquals(listedStates().count { OutboxLogic.isWaitingInOutbox(it) }, count)
        return count
    }
}
