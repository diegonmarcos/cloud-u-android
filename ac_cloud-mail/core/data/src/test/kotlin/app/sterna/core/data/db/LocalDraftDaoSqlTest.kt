package app.sterna.core.data.db

import app.sterna.core.data.mail.DaoQuerySource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * [LocalDraftDao]'s statements, read out of the shipped DAO and RUN against the table
 */
class LocalDraftDaoSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { it.executeUpdate(LOCAL_DRAFTS_CREATE_SQL) }
    }

    @After fun tearDown() = db.close()

    private fun insert(
        accountId: String,
        id: String,
        state: LocalDraftState = LocalDraftState.PENDING,
        updatedAtMillis: Long = 1,
        createdAtMillis: Long = 1,
        notBeforeMillis: Long = 0,
        attemptCount: Int = 0,
        textBody: String = "text of $id",
        replacesEmailId: String? = null,
    ) {
        db.prepareStatement(
            "INSERT INTO local_drafts (accountId, id, messageId, toAddresses, subject, textBody, " +
                "attachmentsJson, bodyIsLossy, requestReceipt, createdAtMillis, updatedAtMillis, " +
                "notBeforeMillis, attemptCount, state, replacesEmailId) " +
                "VALUES (?, ?, 'mid@example.org', 'bob@example.org', 'Half written', ?, '[]', 0, 0, " +
                "?, ?, ?, ?, ?, ?)",
        ).use {
            it.setString(1, accountId); it.setString(2, id); it.setString(3, textBody)
            it.setLong(4, createdAtMillis); it.setLong(5, updatedAtMillis)
            it.setLong(6, notBeforeMillis); it.setInt(7, attemptCount)
            it.setString(8, state.name)
            it.setString(9, replacesEmailId)
            it.executeUpdate()
        }
    }

    /** `attemptCount` and `notBeforeMillis` as SQLite holds them — the row's history of failures. */
    private fun historyOf(accountId: String, id: String): Pair<Int, Long> =
        db.prepareStatement(
            "SELECT attemptCount, notBeforeMillis FROM local_drafts WHERE accountId = ? AND id = ?",
        ).use { ps ->
            ps.setString(1, accountId); ps.setString(2, id)
            ps.executeQuery().use { it.next(); it.getInt(1) to it.getLong(2) }
        }

    /** One shipped `@Query` of [LocalDraftDao], bound from [args] and executed. */
    private fun run(function: String, args: Map<String, Any> = emptyMap()): Int {
        val (statement, order) = DaoQuerySource.bindOrder(DaoQuerySource.daoQuery("LocalDraftDao", function))
        return db.prepareStatement(statement).use { ps ->
            order.forEachIndexed { i, name ->
                ps.setObject(i + 1, args[name] ?: error("nothing to bind for :$name in $statement"))
            }
            ps.executeUpdate()
        }
    }

    /** The same, for a statement that returns rows: the first column of each. */
    private fun read(function: String, args: Map<String, Any> = emptyMap()): List<String> {
        val (statement, order) = DaoQuerySource.bindOrder(DaoQuerySource.daoQuery("LocalDraftDao", function))
        return db.prepareStatement(statement).use { ps ->
            order.forEachIndexed { i, name ->
                ps.setObject(i + 1, args[name] ?: error("nothing to bind for :$name in $statement"))
            }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
        }
    }

    /**
     * The name the startup sweep's first statement ships under — read in one place so the two
     * helpers below cannot drift apart.
     */
    private val sweep = "revertUploadingToPending"

    /** That statement, executed for one account, exactly as `revertUnfinishedLocalDrafts` runs it. */
    private fun sweepUploading(accountId: String): Int = run(sweep, mapOf("accountId" to accountId))

    /** What that statement BINDS — the executed form of "no composer's id reaches this UPDATE". */
    private fun sweepBindOrder(): List<String> =
        DaoQuerySource.bindOrder(DaoQuerySource.daoQuery("LocalDraftDao", sweep)).second

    private fun rows(): List<Pair<String, String>> =
        db.createStatement().use { st ->
            st.executeQuery("SELECT accountId, id FROM local_drafts ORDER BY accountId, id").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
            }
        }

    private fun stateOf(accountId: String, id: String): String =
        db.prepareStatement("SELECT state FROM local_drafts WHERE accountId = ? AND id = ?").use { ps ->
            ps.setString(1, accountId); ps.setString(2, id)
            ps.executeQuery().use { it.next(); it.getString(1) }
        }

    /** `bodyIsLossy` as SQLite holds it — what `draftReplacementIsFaithful` reads off the row. */
    private fun lossyOf(accountId: String, id: String): Int =
        db.prepareStatement("SELECT bodyIsLossy FROM local_drafts WHERE accountId = ? AND id = ?").use { ps ->
            ps.setString(1, accountId); ps.setString(2, id)
            ps.executeQuery().use { it.next(); it.getInt(1) }
        }

    @Test fun `reading one draft never crosses into the other account's row`() {
        insert("accA", "local-draft:same", textBody = "the first account's sentence")
        insert("accB", "local-draft:same", textBody = "the second account's sentence")

        val (statement, order) = DaoQuerySource.bindOrder(DaoQuerySource.daoQuery("LocalDraftDao", "byId"))
        val text = db.prepareStatement(statement).use { ps ->
            order.forEachIndexed { i, name ->
                ps.setString(i + 1, if (name == "accountId") "accA" else "local-draft:same")
            }
            ps.executeQuery().use { it.next(); it.getString("textBody") }
        }

        assertEquals("the first account's sentence", text)
    }

    @Test fun `deleting one draft leaves the sibling account's draft of the same id`() {
        insert("accA", "local-draft:same")
        insert("accB", "local-draft:same")

        assertEquals(1, run("deleteById", mapOf("accountId" to "accA", "id" to "local-draft:same")))

        assertEquals(listOf("accB" to "local-draft:same"), rows())
    }

    @Test fun `emptying one account leaves every other account's drafts`() {
        insert("accA", "local-draft:1")
        insert("accA", "local-draft:2")
        insert("accB", "local-draft:3")

        assertEquals(2, run("deleteForAccount", mapOf("accountId" to "accA")))

        assertEquals(listOf("accB" to "local-draft:3"), rows())
    }

    @Test fun `the upload picks up what is waiting and what died mid-upload, never what is open`() {
        insert("accA", "local-draft:waiting", state = LocalDraftState.PENDING)
        insert("accA", "local-draft:interrupted", state = LocalDraftState.UPLOADING)
        insert("accA", "local-draft:being-typed", state = LocalDraftState.EDITING)
        insert("accA", "local-draft:mid-copy", state = LocalDraftState.STAGING)
        insert("accB", "local-draft:elsewhere", state = LocalDraftState.PENDING)

        // UPLOADING is in: it is what a process killed mid-upload leaves behind, and leaving it out
        // would strand exactly the draft whose fate is least certain. EDITING is out: the composer
        assertEquals(
            listOf("local-draft:interrupted", "local-draft:waiting"),
            read("pending", mapOf("accountId" to "accA", "nowMillis" to 1_000L)).sorted(),
        )
    }

    @Test fun `a draft whose backoff has not elapsed is not handed to the upload yet`() {
        // The backoff is WRITTEN by recordAttempt and has to be HONOURED here, or every failure is
        // retried immediately: `notBeforeMillis` would be a column nobody reads, and a draft whose
        // server keeps refusing it would be re-uploaded as fast as the worker can loop.
        insert("accA", "local-draft:ready", createdAtMillis = 50, notBeforeMillis = 100)
        insert("accA", "local-draft:backed-off", createdAtMillis = 10, notBeforeMillis = 5_000)

        assertEquals(
            listOf("local-draft:ready"),
            read("pending", mapOf("accountId" to "accA", "nowMillis" to 1_000L)),
        )
    }

    @Test fun `a startup can ask for every waiting row, backed off or not`() {
        // How `MailRepository.localDraftsAwaitingUpload` reads the queue: at an instant no backoff
        // can be past. A startup re-arm is the safety net for the work items a reboot lost, so it
        insert("accA", "local-draft:ready", notBeforeMillis = 100)
        insert("accA", "local-draft:backed-off", notBeforeMillis = 4_000_000_000_000)
        insert("accA", "local-draft:being-typed", state = LocalDraftState.EDITING, notBeforeMillis = 0)

        assertEquals(
            "and STILL not the one the composer is holding: the instant is all that is relaxed",
            listOf("local-draft:ready", "local-draft:backed-off"),
            read("pending", mapOf("accountId" to "accA", "nowMillis" to Long.MAX_VALUE)),
        )
    }

    @Test fun `the queue comes out in the order the drafts may next be tried`() {
        // And the instant comes from the CALLER: a query that reads the clock itself cannot be
        // tested, and this one is bound with a fixed 1000 above and below.
        insert("accA", "local-draft:soonest", createdAtMillis = 900, notBeforeMillis = 10)
        insert("accA", "local-draft:later", createdAtMillis = 100, notBeforeMillis = 20)

        assertEquals(
            listOf("local-draft:soonest", "local-draft:later"),
            read("pending", mapOf("accountId" to "accA", "nowMillis" to 1_000L)),
        )
    }

    @Test fun `the sweep rescues what died mid-upload, and only in the account it was asked about`() {
        // A sweep of account A must not touch account B's rows at all. B's EDITING row is a
        // composer the user has open; B's UPLOADING row belongs to B's own worker, which may be
        insert(
            "accA", "local-draft:interrupted", state = LocalDraftState.UPLOADING,
            attemptCount = 4, notBeforeMillis = 9_000,
        )
        insert("accB", "local-draft:elsewhere-interrupted", state = LocalDraftState.UPLOADING)
        insert("accB", "local-draft:being-typed", state = LocalDraftState.EDITING)

        assertEquals(1, sweepUploading("accA"))
        // The other half of the re-arm runs right after, on the same account, and must find nothing
        // to flag here: this row is whole, and the flag would cost the user a duplicate.
        assertEquals(0, run("revertStagedToPendingLossy", mapOf("accountId" to "accA")))

        assertEquals("PENDING", stateOf("accA", "local-draft:interrupted"))
        assertEquals("UPLOADING", stateOf("accB", "local-draft:elsewhere-interrupted"))
        assertEquals("EDITING", stateOf("accB", "local-draft:being-typed"))
        // And it comes back WHOLE: an UPLOADING row already carries the descriptors of its staged
        // files, so it may replace the server draft it names. Marking it lossy would leave the user
        // a duplicate to delete on every process death, for nothing.
        assertEquals("a row re-armed mid-upload is faithful, and must stay so", 0, lossyOf("accA", "local-draft:interrupted"))
        // **And it comes back with its history, which is the other half of "state and nothing
        // else".** `attemptCount` reset to 0 makes `localDraftMayAlreadyBeOnServer` answer no, so
        assertEquals("the rescue restores the state and nothing else", 4 to 9_000L, historyOf("accA", "local-draft:interrupted"))
        // Nothing was deleted on the way: an interrupted draft is requeued, never dropped.
        assertEquals(3, rows().size)
    }

    @Test fun `an EDITING row of the swept account is left EDITING, whatever its id`() {
        // THE defect. This UPDATE runs from `appScope` at every start, and Android restores the
        // back stack: the composer killed with a draft open comes back and reads its row a moment
        //
        // There is no id to spare and no parameter that could carry one: a cold start cannot know
        // whether a composer is about to be restored, so it lets EDITING alone. EDITING is written
        // by the composer and given back by the composer (`giveLocalDraftEditBack`), by nobody else.
        insert("accA", "local-draft:being-typed", state = LocalDraftState.EDITING)
        insert("accA", "local-draft:another-open", state = LocalDraftState.EDITING)
        insert("accA", "local-draft:interrupted", state = LocalDraftState.UPLOADING)

        val swept = sweepUploading("accA")

        assertEquals("EDITING", stateOf("accA", "local-draft:being-typed"))
        assertEquals("EDITING", stateOf("accA", "local-draft:another-open"))
        // UPLOADING still comes back: nobody is looking at such a row, and no composer may hold
        // it (`localDraftMayBeTakenForEdit` refuses it), so left alone it is frozen for ever.
        assertEquals("PENDING", stateOf("accA", "local-draft:interrupted"))
        assertEquals("one row swept, the two open ones untouched", 1, swept)
        // And the statement binds the account and NOTHING else: no lease, no id, no exception.
        assertEquals(listOf("accountId"), sweepBindOrder())
    }

    @Test fun `startup also picks up the row a process death left mid-staging`() {
        // The defect: a process killed between the two upserts of `saveDraftLocalFirst` leaves the
        // row in STAGING. `LocalDraftDao.pending` skips it (above, and it must keep skipping it), so
        //
        // It comes back as PENDING WITHOUT its attachments: such a row is always a FIRST save (a
        // re-save copies `existing.state`), so it carries `attachmentsJson` "[]", and the
        // descriptors of what was attached only ever lived in the composer's memory — no column
        // holds them.
        //
        // AND IT COMES BACK LOSSY, which is the half that keeps this from destroying mail. When
        // the row names a `replacesEmailId`, the attachments are NOT gone: they are on the SERVER,
        insert("accA", "local-draft:mid-copy", state = LocalDraftState.STAGING, replacesEmailId = "imap:Drafts:7")
        insert("accA", "local-draft:open", state = LocalDraftState.EDITING)
        insert("accA", "local-draft:interrupted", state = LocalDraftState.UPLOADING)
        insert("accB", "local-draft:elsewhere-mid-copy", state = LocalDraftState.STAGING)

        // Both halves of the re-arm, in the order `MailRepository.revertUnfinishedLocalDrafts` runs
        // them — and they are two statements precisely so that only one of them sets the flag.
        assertEquals(1, sweepUploading("accA"))
        assertEquals(1, run("revertStagedToPendingLossy", mapOf("accountId" to "accA")))

        assertEquals("PENDING", stateOf("accA", "local-draft:mid-copy"))
        // The composer's row is NOT part of the rescue: it is the one row of the four a restored
        // screen can still be holding.
        assertEquals("EDITING", stateOf("accA", "local-draft:open"))
        assertEquals("PENDING", stateOf("accA", "local-draft:interrupted"))
        assertEquals(
            "⛔ a re-armed staging row must refuse to be called a faithful replacement",
            1, lossyOf("accA", "local-draft:mid-copy"),
        )
        // Scope: the other account's staging row is a save that may be running RIGHT NOW in its
        // own process. The re-arm of one account never reaches it — neither its state nor its flag.
        assertEquals("STAGING", stateOf("accB", "local-draft:elsewhere-mid-copy"))
        assertEquals(0, lossyOf("accB", "local-draft:elsewhere-mid-copy"))
        // Nothing was deleted on the way: a stranded draft is requeued, never dropped.
        assertEquals(4, rows().size)
    }

    @Test fun `a startup finds the account whose only draft is stranded mid-staging`() {
        // The enumeration is the FIRST link of the re-arm: `SternaApplication` walks
        // `accountsWithDrafts` and calls the revert for each. An account whose only row is STAGING
        insert("accA", "local-draft:only-mid-copy", state = LocalDraftState.STAGING)
        insert("accB", "local-draft:waiting", state = LocalDraftState.PENDING)

        val (statement, order) = DaoQuerySource.bindOrder(
            DaoQuerySource.daoQuery("LocalDraftDao", "accountsWithDrafts"),
        )
        assertEquals("accountsWithDrafts binds nothing", emptyList<String>(), order)
        val accounts = db.prepareStatement(statement).use { ps ->
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("accountId")) } }
        }

        assertEquals(listOf("accA", "accB"), accounts.sorted())
    }

    @Test fun `a failed attempt records its backoff and keeps the draft exactly where it was`() {
        insert("accA", "local-draft:1")
        insert("accB", "local-draft:1")

        val (statement, order) = DaoQuerySource.bindOrder(
            DaoQuerySource.daoQuery("LocalDraftDao", "recordAttempt"),
        )
        db.prepareStatement(statement).use { ps ->
            order.forEachIndexed { i, name ->
                when (name) {
                    "accountId" -> ps.setString(i + 1, "accA")
                    "id" -> ps.setString(i + 1, "local-draft:1")
                    "state" -> ps.setString(i + 1, LocalDraftState.PENDING.name)
                    "attemptCount" -> ps.setInt(i + 1, 9)
                    "lastError" -> ps.setString(i + 1, "no route to host")
                    "lastAttemptMillis" -> ps.setLong(i + 1, 4242)
                    "notBeforeMillis" -> ps.setLong(i + 1, 9999)
                    else -> error("recordAttempt now binds :$name, which this test does not know")
                }
            }
            assertEquals(1, ps.executeUpdate())
        }

        // Nine failures and the draft is still PENDING, still there, still whole. There is no
        // attempt cap in this store and no state to be parked in — see [LocalDraftState].
        db.createStatement().use { st ->
            st.executeQuery(
                "SELECT state, attemptCount, lastError, notBeforeMillis, textBody FROM local_drafts " +
                    "WHERE accountId = 'accA' AND id = 'local-draft:1'",
            ).use {
                it.next()
                assertEquals("PENDING", it.getString(1))
                assertEquals(9, it.getInt(2))
                assertEquals("no route to host", it.getString(3))
                assertEquals(9999, it.getLong(4))
                assertEquals("text of local-draft:1", it.getString(5))
            }
        }
        assertEquals(0, run("deleteById", mapOf("accountId" to "accC", "id" to "local-draft:1")))
        assertEquals(2, rows().size)
    }
}
