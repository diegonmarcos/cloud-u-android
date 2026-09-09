package app.sterna.core.data.db

import app.sterna.core.data.mail.DaoQuerySource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The row an INTERRUPTED EDIT leaves behind, run against a REAL SQLite engine through the
 */
class EditInterruptedRowTest {

    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(OUTBOX_CREATE_SQL)
            st.executeUpdate("ALTER TABLE `outbox` ADD COLUMN `pgpMode` TEXT")
            st.executeUpdate("ALTER TABLE `outbox` ADD COLUMN `pgpEntityPath` TEXT")
            st.executeUpdate("ALTER TABLE `outbox` ADD COLUMN `draftEmailId` TEXT")
        }
    }

    @After fun tearDown() = db.close()

    @Test fun `the row the composer held is parked FAILED at the cap with the sentinel reason`() {
        val held = insert(OutboxState.EDITING, attemptCount = 0)

        park(held)

        assertEquals(
            "⛔ the parked row must read FAILED, at OutboxLogic.MAX_ATTEMPTS, with the sentinel " +
                "in lastError. FAILED is what the Outbox screen lists and Retry re-arms; the cap is " +
                "the proxy OutboxLogic.stateAfterEdit already trusts, so the next reopen-and-close " +
                "parks it again instead of queueing it (#70); the sentinel is what the screen " +
                "translates into 'editing was interrupted, not sent'",
            Row(OutboxState.FAILED, OutboxLogic.MAX_ATTEMPTS, OutboxLogic.EDIT_INTERRUPTED),
            read(held),
        )
    }

    /**
     * The sentinel is STORED, in every user's database, from the first version that ships it. It
     */
    @Test fun `the sentinel is a fixed token, never a translated sentence`() {
        assertEquals("edit-interrupted", OutboxLogic.EDIT_INTERRUPTED)
    }

    @Test fun `a row the composer no longer owns is left exactly as it is`() {
        // The same guard as releaseOutboxEdit / parkOutboxEdit, in the statement itself: only an
        // EDITING row belongs to the composer. A QUEUED row under the same id is one a startup
        // sweep or a Retry has already claimed, and writing FAILED over it would park a message
        // somebody else has just put back on its way.
        val queued = insert(OutboxState.QUEUED, attemptCount = 1, lastError = "550 refused")

        park(queued)

        assertEquals(
            "⛔ a QUEUED row must not be touched: neither its state, nor its count, nor its reason",
            Row(OutboxState.QUEUED, 1, "550 refused"),
            read(queued),
        )
    }

    @Test fun `only the row named is parked, not every row mid-edit`() {
        // A second composer's row — or one a process death stranded — is not this close's to park:
        // the startup sweep (parkInterruptedOutboxEdits) decides those, and this statement must name its id.
        val held = insert(OutboxState.EDITING, attemptCount = 0)
        val other = insert(OutboxState.EDITING, attemptCount = 2)

        park(held)

        assertEquals(Row(OutboxState.FAILED, OutboxLogic.MAX_ATTEMPTS, OutboxLogic.EDIT_INTERRUPTED), read(held))
        assertEquals(
            "⛔ the other EDITING row must be left alone: it is somebody else's edit",
            Row(OutboxState.EDITING, 2, null),
            read(other),
        )
    }

    // -- the startup sweep, both statements in the shipped order ----------------------------------

    @Test fun `a row left mid-edit by a process death is parked at startup, not requeued`() {
        val stranded = insert(OutboxState.EDITING, attemptCount = 0)

        startupSweep()

        assertEquals(
            "⛔ a row still EDITING at startup is one whose composer died mid-edit. It used to go " +
                "back to QUEUED and be re-armed at 0, so the message left on its own — and the " +
                "composer restored afterwards could send the same message a second time. It must " +
                "be PARKED: FAILED at OutboxLogic.MAX_ATTEMPTS with the sentinel reason, visible " +
                "in red in the Outbox, reopenable, sent by Retry and by nothing else",
            Row(OutboxState.FAILED, OutboxLogic.MAX_ATTEMPTS, OutboxLogic.EDIT_INTERRUPTED),
            read(stranded),
        )
    }

    @Test fun `an exhausted row keeps its own reason through the sweep`() {
        val exhausted = insert(OutboxState.EDITING, attemptCount = OutboxLogic.MAX_ATTEMPTS, lastError = "server said no")

        startupSweep()

        assertEquals(
            "⛔ a row that was ALREADY at the cap before it was reopened is a genuine send failure, " +
                "and its own reason must survive the sweep: revertEditingExhaustedToFailed runs " +
                "FIRST and claims it out of EDITING, so parkAllEditingAsInterrupted no longer sees " +
                "it. Run the two the other way round and the parking statement overwrites 'server " +
                "said no' with the interrupted-edit sentinel, and the Outbox tells her the edit " +
                "was interrupted on a message the server refused",
            Row(OutboxState.FAILED, OutboxLogic.MAX_ATTEMPTS, "server said no"),
            read(exhausted),
        )
    }

    @Test fun `the sweep touches no row in any other state`() {
        // One row of EVERY state but EDITING, each with a count and a reason of its own, so a
        // statement that forgot its WHERE — or one that named a second state — is seen on the
        // column it clobbered, not only on the state.
        val untouched = OutboxState.entries.filter { it != OutboxState.EDITING }.mapIndexed { i, state ->
            insert(state, attemptCount = i + 1, lastError = "reason ${state.name}") to Row(state, i + 1, "reason ${state.name}")
        }
        assertEquals(
            "the fixture must cover every state but EDITING — a new OutboxState is judged here without a line of its own",
            OutboxState.entries.size - 1,
            untouched.size,
        )

        startupSweep()

        untouched.forEach { (id, before) ->
            assertEquals(
                "⛔ the startup sweep must touch nothing but the rows a process death stranded in " +
                    "EDITING: a ${before.state} row swept into FAILED at the cap is a permanent " +
                    "failure banner over a message that is on its way, already parked, or kept as " +
                    "a draft — and its own count and reason gone with it",
                before,
                read(id),
            )
        }
    }

    @Test fun `the re-arm that follows the sweep never picks up a parked row`() {
        val stranded = insert(OutboxState.EDITING, attemptCount = 0)
        val exhausted = insert(OutboxState.EDITING, attemptCount = OutboxLogic.MAX_ATTEMPTS, lastError = "server said no")
        val held = insert(OutboxState.HELD, attemptCount = 0)
        val queued = insert(OutboxState.QUEUED, attemptCount = 1)
        val sending = insert(OutboxState.SENDING, attemptCount = 1)

        startupSweep()
        val rearmed = idsOf(DaoQuerySource.daoQuery("OutboxDao", "unfinished"))

        assertEquals(
            "⛔ SternaApplication re-arms every row unfinished() returns, right after the sweep. " +
                "A parked row in that list is the sweep undone: the worker is booked on it at 0 " +
                "and the message leaves with nobody asking. The two parked ids ($stranded, " +
                "$exhausted) must be absent, and the three states a worker still has something " +
                "to do with must be present",
            setOf(held, queued, sending),
            rearmed,
        )
    }

    /**
     * THE ORDER OF THE TWO STATEMENTS is what keeps an exhausted row's own reason (the SQLite
     */
    @Test fun `the repository issues the exhausted statement first, then parks the rest`() {
        assertEquals(
            "⛔ parkInterruptedOutboxEdits must be exactly these lines, in this order. Swapped, " +
                "parkAllEditingAsInterrupted claims every EDITING row first — the exhausted ones " +
                "included — and a genuine send failure reopened and lost is relabelled 'editing " +
                "was interrupted'. Anything else in the body is a decision this test does not know",
            listOf(
                "{",
                "outboxDao.revertEditingExhaustedToFailed(OutboxLogic.MAX_ATTEMPTS)",
                "outboxDao.parkAllEditingAsInterrupted(OutboxLogic.MAX_ATTEMPTS, OutboxLogic.EDIT_INTERRUPTED)",
                "}",
            ),
            codeLines(DaoQuerySource.mailFunctionBody("MailRepository", "parkInterruptedOutboxEdits")),
        )
    }

    /**
     * The close-time door, line by line. `MailRepository` cannot be instantiated here, so the ONE
     */
    @Test fun `the close-time park writes the cap on a row still under it, and the state alone on one already there`() {
        assertEquals(
            "⛔ parkInterruptedOutboxEdit must be exactly these lines. Inverted, a QUEUED row at " +
                "attemptCount 0 closed over a lost body is parked FAILED *at 0*: the next Edit and " +
                "close run stateAfterEdit(0) = QUEUED + schedule(0), and the message the user was " +
                "told to reopen leaves on its own — the very symptom, one round trip later. Without " +
                "the EDITING guard, a row a Retry has just requeued is parked over the user's gesture",
            listOf(
                "{",
                "val row = outboxDao.byId(id) ?: return",
                "if (row.state != OutboxState.EDITING) return",
                "if (row.attemptCount >= OutboxLogic.MAX_ATTEMPTS) outboxDao.setState(id, OutboxState.FAILED)",
                "else outboxDao.parkEditingAsInterrupted(id, OutboxLogic.MAX_ATTEMPTS, OutboxLogic.EDIT_INTERRUPTED)",
                "}",
            ),
            codeLines(DaoQuerySource.mailFunctionBody("MailRepository", "parkInterruptedOutboxEdit")),
        )
    }

    /** Every CODE line of [text] — comments and blanks dropped, trailing comments cut, whitespace
     *  normalised — compared WHOLE, never by `contains`. */
    private fun codeLines(text: String): List<String> =
        text.lines()
            .filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }
            .map { it.substringBefore("//").replace(Regex("""\s+"""), " ").trim() }
            .filter { it.isNotEmpty() }

    // -- the statement, as shipped -----------------------------------------------------------------

    /** Runs the shipped `OutboxDao.parkEditingAsInterrupted` on [id], bound as the repository binds it. */
    private fun park(id: Long) = execute(
        "parkEditingAsInterrupted",
        mapOf("id" to id, "maxAttempts" to OutboxLogic.MAX_ATTEMPTS, "lastError" to OutboxLogic.EDIT_INTERRUPTED),
    )

    /**
     * The startup sweep as `MailRepository.parkInterruptedOutboxEdits` issues it: the exhausted
     * rows first, then every row still EDITING — each statement bound as the repository binds it.
     */
    private fun startupSweep() {
        execute("revertEditingExhaustedToFailed", mapOf("maxAttempts" to OutboxLogic.MAX_ATTEMPTS))
        execute(
            "parkAllEditingAsInterrupted",
            mapOf("maxAttempts" to OutboxLogic.MAX_ATTEMPTS, "lastError" to OutboxLogic.EDIT_INTERRUPTED),
        )
    }

    /** Executes the shipped `OutboxDao.[function]` with exactly [values] bound, by name. */
    private fun execute(function: String, values: Map<String, Any>) {
        val (sql, order) = DaoQuerySource.bindOrder(DaoQuerySource.daoQuery("OutboxDao", function))
        assertEquals(
            "$function must bind exactly the values the repository hands it",
            values.keys,
            order.toSet(),
        )
        db.prepareStatement(sql).use { st ->
            order.forEachIndexed { i, name ->
                when (val v = values.getValue(name)) {
                    is Long -> st.setLong(i + 1, v)
                    is Int -> st.setInt(i + 1, v)
                    is String -> st.setString(i + 1, v)
                    else -> error("unexpected bind $name")
                }
            }
            st.executeUpdate()
        }
    }

    // -- the fixture -------------------------------------------------------------------------------

    private data class Row(val state: OutboxState, val attemptCount: Int, val lastError: String?)

    private var rows = 0L

    /** Inserts one row and returns its id. */
    private fun insert(state: OutboxState, attemptCount: Int, lastError: String? = null): Long {
        rows++
        db.prepareStatement(
            "INSERT INTO `outbox`(accountId, recipients, subject, textBody, attachmentsJson, " +
                "createdAtMillis, notBeforeMillis, state, attemptCount, lastError) " +
                "VALUES ('a', 'to@example.org', 's', 'b', '[]', ?, ?, ?, ?, ?)",
        ).use { st ->
            st.setLong(1, rows)
            st.setLong(2, rows)
            st.setString(3, state.name)
            st.setInt(4, attemptCount)
            st.setString(5, lastError)
            st.executeUpdate()
        }
        db.createStatement().use { st ->
            st.executeQuery("SELECT last_insert_rowid()").use { rs -> rs.next(); return rs.getLong(1) }
        }
    }

    /** The ids [sql] hands back. */
    private fun idsOf(sql: String): Set<Long> {
        val out = mutableSetOf<Long>()
        db.createStatement().use { st ->
            st.executeQuery(sql).use { rs -> while (rs.next()) out += rs.getLong("id") }
        }
        return out
    }

    private fun read(id: Long): Row = db.prepareStatement(
        "SELECT state, attemptCount, lastError FROM `outbox` WHERE id = ?",
    ).use { st ->
        st.setLong(1, id)
        st.executeQuery().use { rs ->
            check(rs.next()) { "no row $id" }
            Row(OutboxState.valueOf(rs.getString("state")), rs.getInt("attemptCount"), rs.getString("lastError"))
        }
    }
}
