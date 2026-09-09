package app.sterna.core.data.db

import app.sterna.core.data.mail.DraftSaveOutcome
import app.sterna.core.data.mail.DaoQuerySource
import app.sterna.core.data.mail.draftSaveParksTheQueuedRowAs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The row a "save as a draft" left behind, run against a REAL SQLite engine through the
 */
class KeptAsDraftRowTest {

    private lateinit var db: Connection

    /** The state the shipped rule parks a kept-on-device save in — asked, never assumed. */
    private val parked = draftSaveParksTheQueuedRowAs(DraftSaveOutcome.KEPT_ON_DEVICE)
        ?: error("a save kept on the device parks nothing at all — see LocalDraftSaveTest")

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(OUTBOX_CREATE_SQL)
            // The columns later migrations added, so the shipped statements run against the table
            // as it is today rather than as it was at v10.
            st.executeUpdate("ALTER TABLE `outbox` ADD COLUMN `pgpMode` TEXT")
            st.executeUpdate("ALTER TABLE `outbox` ADD COLUMN `pgpEntityPath` TEXT")
            st.executeUpdate("ALTER TABLE `outbox` ADD COLUMN `draftEmailId` TEXT")
        }
    }

    @After fun tearDown() = db.close()

    /**
     * The startup re-arm ([OutboxDao.unfinished], read by `SternaApplication` at every launch and
     */
    @Test fun `the startup re-arm picks up the three states a worker still has something to do with`() {
        OutboxState.entries.forEach { insert(it) }

        assertEquals(
            "⛔ exactly these three, and each for its own reason. HELD and QUEUED are messages " +
                "still on their way. ${OutboxState.SENDING} is NOT on its way — it is a delivery " +
                "whose run never came back — and it is re-armed precisely so the worker returns and " +
                "PARKS it as ${OutboxState.INTERRUPTED} (OutboxLogic.parkOnPickup); take it out of " +
                "this set and the row reads 'Sending…' on the Outbox screen for good, with nobody " +
                "ever coming to look at it. ${OutboxState.INTERRUPTED} must stay OUT: it is already " +
                "parked, and re-arming it calls the worker back onto a row whose only move left is " +
                "the user's Retry. $parked in this set is the reported defect itself: the user " +
                "tapped 'save as a draft' offline, and the next launch delivers the message they " +
                "had before they edited it",
            setOf(OutboxState.HELD, OutboxState.QUEUED, OutboxState.SENDING),
            statesOf(DaoQuerySource.daoQuery("OutboxDao", "unfinished")),
        )
    }

    /**
     * The other startup path: the mid-edit recovery. Both statements only ever name `EDITING`, and
     */
    @Test fun `the mid-edit recovery leaves a row saved as a draft exactly where it is`() {
        insert(parked, attemptCount = 0)
        insert(parked, attemptCount = OutboxLogic.MAX_ATTEMPTS)
        insert(OutboxState.EDITING, attemptCount = 0)

        val (exhausted, order) = DaoQuerySource.bindOrder(
            DaoQuerySource.daoQuery("OutboxDao", "revertEditingExhaustedToFailed"),
        )
        assertEquals(listOf("maxAttempts"), order)
        db.prepareStatement(exhausted).use {
            it.setInt(1, OutboxLogic.MAX_ATTEMPTS)
            it.executeUpdate()
        }
        val (park, parkOrder) = DaoQuerySource.bindOrder(
            DaoQuerySource.daoQuery("OutboxDao", "parkAllEditingAsInterrupted"),
        )
        assertEquals(listOf("maxAttempts", "lastError"), parkOrder)
        db.prepareStatement(park).use {
            it.setInt(1, OutboxLogic.MAX_ATTEMPTS)
            it.setString(2, OutboxLogic.EDIT_INTERRUPTED)
            it.executeUpdate()
        }

        assertEquals(
            "⛔ startup recovery must touch nothing but the rows a process death stranded in " +
                "EDITING — and that one it PARKS as FAILED, it no longer requeues it. A $parked " +
                "row swept into FAILED, whatever its attempt count, is a permanent failure banner " +
                "over a save that worked; swept into QUEUED it is the pre-edit message sent " +
                "behind the user's back",
            listOf(parked, parked, OutboxState.FAILED),
            statesInOrder(),
        )
    }

    // -- the fixture -----------------------------------------------------------------------------

    private var rows = 0L

    private fun insert(state: OutboxState, attemptCount: Int = 0) {
        rows++
        db.prepareStatement(
            "INSERT INTO `outbox`(accountId, recipients, subject, textBody, attachmentsJson, " +
                "createdAtMillis, notBeforeMillis, state, attemptCount) " +
                "VALUES ('a', 'to@example.org', 's', 'b', '[]', ?, ?, ?, ?)",
        ).use { st ->
            st.setLong(1, rows)
            st.setLong(2, rows)
            st.setString(3, state.name)
            st.setInt(4, attemptCount)
            st.executeUpdate()
        }
    }

    /** The distinct states [sql] hands back. */
    private fun statesOf(sql: String): Set<OutboxState> {
        val out = mutableSetOf<OutboxState>()
        db.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                while (rs.next()) out += OutboxState.valueOf(rs.getString("state"))
            }
        }
        return out
    }

    /** Every row's state, in insertion order — what the table holds after the recovery has run. */
    private fun statesInOrder(): List<OutboxState> {
        val out = mutableListOf<OutboxState>()
        db.createStatement().use { st ->
            st.executeQuery("SELECT state FROM `outbox` ORDER BY createdAtMillis ASC").use { rs ->
                while (rs.next()) out += OutboxState.valueOf(rs.getString("state"))
            }
        }
        return out
    }
}
