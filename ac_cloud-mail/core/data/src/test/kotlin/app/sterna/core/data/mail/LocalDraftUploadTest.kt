package app.sterna.core.data.mail

import app.sterna.core.data.db.LOCAL_DRAFTS_CREATE_SQL
import app.sterna.core.data.db.LOCAL_DRAFT_ID_PREFIX
import app.sterna.core.data.db.LocalDraftEntity
import app.sterna.core.data.db.LocalDraftState
import app.sterna.core.data.db.OutboxAttachment
import app.sterna.core.data.db.OutboxAttachments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/**
 * **The deferred upload of a draft the phone kept** (#95), executed: the shipped
 */
class LocalDraftUploadTest {
    private lateinit var db: Connection
    private lateinit var filesDir: File

    /** Every call the harness made, in order — so "exactly once" and "never" are countable. */
    private val uploaded = mutableListOf<String>()
    private val discarded = mutableListOf<String>()
    private val marked = mutableListOf<String>()

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { it.executeUpdate(LOCAL_DRAFTS_CREATE_SQL) }
        filesDir = Files.createTempDirectory("local-drafts").toFile()
    }

    @After fun tearDown() {
        db.close()
        filesDir.deleteRecursively()
    }

    // ---- 1. the backoff: hard-coded couples, and no cap on anything but the wait -------------------

    @Test fun `the wait doubles from thirty seconds and then stops growing`() {
        assertEquals("the first failure waits half a minute", 30_000L, localDraftRetryDelayMillis(1))
        assertEquals(60_000L, localDraftRetryDelayMillis(2))
        assertEquals(120_000L, localDraftRetryDelayMillis(3))
        assertEquals(240_000L, localDraftRetryDelayMillis(4))
        assertEquals(480_000L, localDraftRetryDelayMillis(5))
        assertEquals(15_360_000L, localDraftRetryDelayMillis(10))
        assertEquals("six hours, and never more", 21_600_000L, localDraftRetryDelayMillis(11))
        assertEquals(21_600_000L, localDraftRetryDelayMillis(40))
        // The number that says there is no cap on ATTEMPTS. A phone weeks from the network its
        // account is on still asks four times a day, for ever — it is never given up on.
        assertEquals("the thousandth failure still books a retry", 21_600_000L, localDraftRetryDelayMillis(1_000))
        assertEquals(
            "⛔ and it must not WRAP: `30_000 shl 64` is 30_000 again, i.e. a half-minute retry " +
                "storm arriving exactly when the backoff mattered most",
            21_600_000L, localDraftRetryDelayMillis(Int.MAX_VALUE),
        )
        assertEquals("a count of zero is still a failure, and still waits", 30_000L, localDraftRetryDelayMillis(0))
    }

    @Test fun `a failed attempt counts up from the row and carries the cause`() {
        val failure = java.net.UnknownHostException("Unable to resolve host \"mail.example.org\"")

        val first = localDraftAttemptAfterFailure(row(), failure, NOW)
        assertEquals(1, first.attemptCount)
        assertEquals("Unable to resolve host \"mail.example.org\"", first.error)
        assertEquals(NOW, first.atMillis)
        assertEquals(NOW + 30_000L, first.notBeforeMillis)

        // Counted off the ROW, not by the caller: the save and the worker both fail into this,
        // and a counter either of them kept would reset every time the other one ran.
        val eighth = localDraftAttemptAfterFailure(row().copy(attemptCount = 7), failure, NOW)
        assertEquals(8, eighth.attemptCount)
        assertEquals(NOW + 3_840_000L, eighth.notBeforeMillis)

        assertEquals(
            "an exception with no message at all still says something on the row",
            "IllegalStateException",
            localDraftAttemptAfterFailure(row(), IllegalStateException(), NOW).error,
        )
    }

    @Test fun `whose turn it is to be uploaded`() {
        // Written out, state by state and instant by instant. `LocalDraftDaoSqlTest` runs the
        // SQL of `LocalDraftDao.pending` over the same four states, so the two readings of the
        // same question cannot drift apart.
        assertTrue(localDraftUploadIsDue(LocalDraftState.PENDING, notBeforeMillis = 100, nowMillis = 100))
        assertTrue(localDraftUploadIsDue(LocalDraftState.PENDING, notBeforeMillis = 100, nowMillis = 101))
        assertFalse(localDraftUploadIsDue(LocalDraftState.PENDING, notBeforeMillis = 100, nowMillis = 99))
        assertTrue(
            "a process killed mid-upload leaves UPLOADING behind, and that is the row whose fate " +
                "is least certain — it is picked up, not stranded",
            localDraftUploadIsDue(LocalDraftState.UPLOADING, notBeforeMillis = 100, nowMillis = 100),
        )
        assertFalse(
            "⛔ a composer is holding it (#70's rule): the worker must not take the text away " +
                "from under the user",
            localDraftUploadIsDue(LocalDraftState.EDITING, notBeforeMillis = 0, nowMillis = NOW),
        )
        assertFalse(
            "⛔ its attachmentsJson still says \"[]\" while the bytes are being copied: uploading " +
                "it sends the draft amputated",
            localDraftUploadIsDue(LocalDraftState.STAGING, notBeforeMillis = 0, nowMillis = NOW),
        )
    }

    // ---- 2. one attempt, over the real table ------------------------------------------------------

    @Test fun `an upload that goes through consumes the row and its staged files`() = runTest {
        val stored = write(row())
        val dir = File(filesDir, localDraftDirName(stored.id)).apply { mkdirs() }
        File(dir, "notes.txt").writeText("hello")

        val result = attempt(stored) { DraftSaveOutcome.SAVED }

        assertEquals(LocalDraftUploadResult(LocalDraftUploadStep.UPLOADED, null), result)
        assertNull("the draft is on the server; the local copy must not linger", read(stored.id))
        assertFalse("the staged bytes go with the row", dir.exists())
        assertEquals(listOf(stored.id), discarded)
        assertEquals("it was marked in flight first, so a second worker cannot take it", 1, marked.size)
    }

    @Test fun `an upload that fails keeps the draft whole and only arms its backoff`() = runTest {
        val stored = write(row(textBody = TEXT, attemptCount = 0))
        val dir = File(filesDir, localDraftDirName(stored.id)).apply { mkdirs() }
        File(dir, "notes.txt").writeText("hello")

        val result = attempt(stored) { offline() }

        assertEquals(
            LocalDraftUploadResult(LocalDraftUploadStep.KEPT_FOR_LATER, NOW + 30_000L), result,
        )
        val row = requireNotNull(read(stored.id)) { "⛔ the text the user typed was deleted" }
        assertEquals("the body is untouched", TEXT, row["textBody"])
        assertEquals("one failure, counted", "1", row["attemptCount"])
        assertEquals("Unable to resolve host \"mail.example.org\"", row["lastError"])
        assertEquals((NOW + 30_000L).toString(), row["notBeforeMillis"])
        assertEquals("⛔ still PENDING — there is no state to park a draft in", "PENDING", row["state"])
        assertEquals("⛔ nothing was consumed", emptyList<String>(), discarded)
        assertTrue("and its attachments are still on the phone", File(dir, "notes.txt").exists())
    }

    @Test fun `the hundredth failure leaves the draft exactly where the first one did`() = runTest {
        // THE test of this volet. The outbox parks an item as FAILED past OutboxLogic.MAX_ATTEMPTS
        // because an undeliverable message has a recipient waiting. A draft has nobody waiting: a
        // phone can be weeks from the network its account is on, and the ninety-ninth failure must
        // book a hundredth attempt exactly as the first booked a second.
        val stored = write(row(textBody = TEXT, attemptCount = 99))

        val result = attempt(stored) { offline() }

        assertEquals(
            "a retry is still booked, six hours out — not null, which would be an attempt cap " +
                "wearing a clock",
            LocalDraftUploadResult(LocalDraftUploadStep.KEPT_FOR_LATER, NOW + 21_600_000L), result,
        )
        val row = requireNotNull(read(stored.id)) { "⛔ a hundred failures deleted the user's text" }
        assertEquals(TEXT, row["textBody"])
        assertEquals("100", row["attemptCount"])
        assertEquals("PENDING", row["state"])
        assertEquals(emptyList<String>(), discarded)
    }

    @Test fun `a draft still open in the composer is not uploaded and books no wake-up`() = runTest {
        val stored = write(row(state = LocalDraftState.EDITING, notBeforeMillis = 0))

        val result = attempt(stored) { error("the worker must not have got here") }

        assertEquals(
            "⛔ and NO retry instant: `notBeforeMillis` is in the past for an EDITING row, so a " +
                "booking would fire at once, and again, for as long as the user keeps typing",
            LocalDraftUploadResult(LocalDraftUploadStep.NOT_DUE, null), result,
        )
        assertEquals("nothing was uploaded", emptyList<String>(), uploaded)
        assertEquals("and nothing was marked in flight either", emptyList<String>(), marked)
        assertEquals("EDITING", requireNotNull(read(stored.id))["state"])
    }

    @Test fun `a draft whose backoff has not run out comes back at the instant the row names`() = runTest {
        val stored = write(row(notBeforeMillis = NOW + 90_000L))

        val result = attempt(stored) { error("the worker must not have got here") }

        assertEquals(
            LocalDraftUploadResult(LocalDraftUploadStep.NOT_DUE, NOW + 90_000L), result,
        )
        assertEquals(emptyList<String>(), uploaded)
    }

    @Test fun `a draft stranded mid-upload by a process death is picked up again`() = runTest {
        val stored = write(row(state = LocalDraftState.UPLOADING))

        val result = attempt(stored) { DraftSaveOutcome.SAVED }

        assertEquals(LocalDraftUploadResult(LocalDraftUploadStep.UPLOADED, null), result)
        assertEquals(listOf(stored.id), uploaded)
    }

    @Test fun `a row that is no longer there is not an error and books nothing`() = runTest {
        val result = uploadLocalDraftOnce(
            row = null, nowMillis = NOW,
            markUploading = { marked += "?" },
            upload = { uploaded += it.id; DraftSaveOutcome.SAVED },
            discard = { discarded += it.id },
            record = { error("nothing to record for a row that does not exist") },
        )

        assertEquals(LocalDraftUploadResult(LocalDraftUploadStep.GONE, null), result)
        assertEquals(emptyList<String>(), uploaded)
    }

    @Test fun `a worker being stopped is not a failed attempt`() {
        // A CancellationException must not be written down as an attempt: the count feeds the
        // backoff, and a phone that stops the worker often (doze, a low-memory kill) would push a
        // perfectly reachable draft out to six-hourly retries without a single real failure.
        val stored = write(row())

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { attempt(stored) { throw CancellationException("stopped") } }
        }

        val row = requireNotNull(read(stored.id))
        assertEquals("0", row["attemptCount"])
        assertNull(row["lastError"])
        assertEquals("nothing was consumed either", emptyList<String>(), discarded)
    }

    // ---- 2b. what ONE attempt books for itself, being stopped included ----------------------------

    /** Every booking made, in order: the delay, and whether it must give way to an existing one. */
    private val booked = mutableListOf<Pair<Long, Boolean>>()

    @Test fun `an attempt books the instant the row named, and books nothing when it named none`() = runTest {
        // The re-booking the worker used to do inline, executed here — with BOTH arguments pinned.
        // A seam that books "something" at the wrong instant is a draft that leaves hours late or a
        // worker that spins; one that books it under the wrong policy is the next test's subject.
        val kept = uploadLocalDraftRebooking(
            now = { NOW },
            upload = { LocalDraftUploadResult(LocalDraftUploadStep.KEPT_FOR_LATER, NOW + 90_000L) },
            rebook = { delay, onlyIfNothingBooked -> booked += delay to onlyIfNothingBooked },
        )

        assertEquals(LocalDraftUploadResult(LocalDraftUploadStep.KEPT_FOR_LATER, NOW + 90_000L), kept)
        // `false`: the row has just been given a NEW backoff, and that instant must win over
        // whatever was booked for this draft before it. Keeping the old one here is a draft that
        // wakes on a stale instant for ever.
        assertEquals("the distance from now to the instant the row carries", listOf(90_000L to false), booked)

        // And NOTHING for a verdict that names no instant: the row is gone, or a composer is
        // holding it and its own save re-arms it — a wake-up booked here fires at once, and again,
        // for as long as the user keeps typing.
        booked.clear()
        uploadLocalDraftRebooking({ NOW }, { LocalDraftUploadResult(LocalDraftUploadStep.UPLOADED, null) }, rebook())
        uploadLocalDraftRebooking({ NOW }, { LocalDraftUploadResult(LocalDraftUploadStep.GONE, null) }, rebook())
        assertEquals("no instant, no work item", emptyList<Pair<Long, Boolean>>(), booked)

        // An instant already past goes at once, never at a negative delay.
        uploadLocalDraftRebooking(
            { NOW },
            { LocalDraftUploadResult(LocalDraftUploadStep.KEPT_FOR_LATER, NOW - 5_000L) },
            rebook(),
        )
        assertEquals(listOf(0L to false), booked)
    }

    @Test fun `the delay is measured when the attempt ENDS, and not when it began`() = runTest {
        // The clock is a FUNCTION for this one reason. Passed as a value, it is read before the
        // attempt runs — and the attempt is now allowed to take up to LOCAL_DRAFT_UPLOAD_BUDGET_MS.
        // The delay would then carry the attempt's whole duration on top of the row's backoff: the
        // draft comes back later than the row itself says, and the slower the link the later it is.
        var clock = NOW

        uploadLocalDraftRebooking(
            now = { clock },
            upload = {
                clock = NOW + 47_000L // 47 s spent on the wire before the server gave up
                LocalDraftUploadResult(LocalDraftUploadStep.KEPT_FOR_LATER, NOW + 90_000L)
            },
            rebook = rebook(),
        )

        // 90 s of backoff, 47 of them already served. 90_000 here is the clock read too early.
        assertEquals(listOf(43_000L to false), booked)
    }

    @Test fun `a worker stopped mid-upload books its own retry before it lets the stop through`() = runTest {
        // THE hole measured at the bench (#95, gesture B9): the system stops the worker while the
        // APPEND is in flight, the coroutine is cancelled, and from that instant EVERY suspending
        // call throws — `recordAttempt` included. So nothing is written on the row and nothing books
        // a wake-up: the draft sits in UPLOADING until the app is killed and started again.
        //
        // **This coroutine is REALLY cancelled**, and that is the whole design of the test. Made
        // to `throw CancellationException()` from a live job instead, it passes against code that
        var returnedNormally = false
        var clockReads = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            uploadLocalDraftRebooking(
                now = { clockReads++; NOW },
                upload = { awaitCancellation() },
                rebook = rebook(),
            )
            returnedNormally = true
        }

        job.cancelAndJoin()

        // Half a minute, NOT zero: a worker stopped under system pressure that re-books itself
        // for `now` is stopped again at once, and again — a loop against the very condition that
        assertEquals(listOf(30_000L to true), booked)
        assertTrue("a cancelled job is what this pins", job.isCancelled)
        assertFalse(
            "⛔ the stop must still reach the caller — swallowed, WorkManager is handed an " +
                "ordinary, successful run",
            returnedNormally,
        )
        // And the clock is not read on this path at all: reading it here means it is read BEFORE
        // the attempt, which is what inflates the ordinary delay by the attempt's own duration.
        assertEquals("the stop path has no use for a clock", 0, clockReads)
    }

    /** The recorder, as the shipped two-argument seam. */
    private fun rebook(): (Long, Boolean) -> Unit = { delay, onlyIfNothingBooked ->
        booked += delay to onlyIfNothingBooked
    }

    // ---- 3. what a startup books ------------------------------------------------------------------

    @Test fun `a startup books one job per waiting row, each at its own instant`() {
        // Backed-off rows INCLUDED, with a delay. Skipping them — "they are not due" — leaves a
        // draft that failed ten minutes before the reboot with no work item at all: WorkManager's
        // jobs are what a reboot loses, and this re-arm is the only thing that puts them back.
        val jobs = localDraftUploadJobs(
            "acc-1",
            listOf(
                row(id = "local-draft:due", notBeforeMillis = NOW - 5_000L),
                row(id = "local-draft:exactly-now", notBeforeMillis = NOW),
                row(id = "local-draft:backed-off", notBeforeMillis = NOW + 90_000L),
            ),
            NOW,
        )

        assertEquals(
            listOf(
                LocalDraftUploadJob("acc-1", "local-draft:due", 0L),
                LocalDraftUploadJob("acc-1", "local-draft:exactly-now", 0L),
                LocalDraftUploadJob("acc-1", "local-draft:backed-off", 90_000L),
            ),
            jobs,
        )
        assertEquals("no rows, no work", emptyList<LocalDraftUploadJob>(), localDraftUploadJobs("acc-1", emptyList(), NOW))
        assertEquals("a delay is never negative", 0L, localDraftScheduleDelayMillis(NOW - 1_000L, NOW))
        assertEquals(1_000L, localDraftScheduleDelayMillis(NOW + 1_000L, NOW))
    }

    // ---- 4. the attachments a worker has, days later ----------------------------------------------

    @Test fun `a deferred upload rebuilds its attachments from the row`() {
        // There is no composer left: the parts can only come from the descriptors the staging wrote.
        // And the COUNT matters as much as the bytes — `uploadDraft` weighs it before it lets
        // the original be destroyed (#63), so an emptyList() here would upload the draft amputated
        // AND call the amputation a faithful replacement.
        val json = OutboxAttachments.encode(
            listOf(
                OutboxAttachment(
                    kind = OutboxAttachments.KIND_IMAP_FILE, path = "/data/local-drafts/d1/photo.jpg",
                    type = "image/jpeg", name = "photo.jpg", size = 4L, cid = "cid1", disposition = "inline",
                ),
                OutboxAttachment(
                    kind = OutboxAttachments.KIND_JMAP_BLOB, blobId = "G1234",
                    type = "application/pdf", name = "bill.pdf", size = 9L,
                ),
            ),
        )

        val parts = localDraftUploadAttachments(json)

        assertEquals(2, parts.size)
        assertEquals("/data/local-drafts/d1/photo.jpg", parts[0].partId)
        assertNull(parts[0].blobId)
        assertEquals("image/jpeg", parts[0].type)
        assertEquals("photo.jpg", parts[0].name)
        assertEquals(4L, parts[0].size)
        assertEquals("cid1", parts[0].cid)
        assertEquals("inline", parts[0].disposition)
        assertEquals("G1234", parts[1].blobId)
        assertNull("a JMAP blob has no file to read", parts[1].partId)
        assertEquals("bill.pdf", parts[1].name)
        assertEquals(emptyList<Any>(), localDraftUploadAttachments("[]"))
        assertEquals(emptyList<Any>(), localDraftUploadAttachments(null))
    }

    // ---- 5. the account that goes away ------------------------------------------------------------

    @Test fun `removing an account takes its drafts and their staged bytes, and only its own`() = runTest {
        // Codeberg #121's neighbourhood, with a sharper victim: what is left behind here is text the
        // user typed and the server never received, plus whatever she attached to it.
        val mine = write(row(accountId = "acc-1", id = LOCAL_DRAFT_ID_PREFIX + "d1"))
        val alsoMine = write(row(accountId = "acc-1", id = LOCAL_DRAFT_ID_PREFIX + "d2"))
        val neighbour = write(row(accountId = "acc-2", id = LOCAL_DRAFT_ID_PREFIX + "d3"))
        val files = listOf(mine, alsoMine, neighbour).associate { entity ->
            entity.id to File(filesDir, localDraftDirName(entity.id)).apply { mkdirs() }
                .let { File(it, "notes.txt").apply { writeText("bytes of ${entity.id}") } }
        }

        purgeLocalDraftsOfAccount(
            ids = { idsOf("acc-1") },
            deleteRows = { deleteForAccount("acc-1") },
            dirOf = { File(filesDir, localDraftDirName(it)) },
        )

        assertNull(read(mine.id))
        assertNull(read(alsoMine.id))
        assertFalse("⛔ the attachments of a removed account go too", files.getValue(mine.id).exists())
        assertFalse(files.getValue(alsoMine.id).exists())
        assertEquals(
            "⛔ and the neighbouring account keeps its own draft, row and bytes",
            "text of ${neighbour.id}", requireNotNull(read(neighbour.id))["textBody"],
        )
        assertTrue(files.getValue(neighbour.id).exists())
    }

    @Test fun `the drafts are named before they are deleted, or their files can never be found`() {
        // The ORDER, and it is the whole of the second half: the directories are named after the
        // ids and nothing else names them. Read after the DELETE, the list is empty and the bytes
        // of the account just removed stay on the phone for good.
        val order = mutableListOf<String>()

        kotlinx.coroutines.runBlocking {
            purgeLocalDraftsOfAccount(
                ids = { order += "read the ids"; listOf("local-draft:d1") },
                deleteRows = { order += "delete the rows" },
                dirOf = { order += "name the directory of $it"; File(filesDir, localDraftDirName(it)) },
            )
        }

        assertEquals(
            listOf("read the ids", "name the directory of local-draft:d1", "delete the rows"),
            order,
        )
    }

    // ---- the harness ------------------------------------------------------------------------------

    /** One shipped attempt at [row], with the real statements behind every adapter. */
    private suspend fun attempt(
        row: LocalDraftEntity,
        nowMillis: Long = NOW,
        upload: suspend (LocalDraftEntity) -> DraftSaveOutcome,
    ): LocalDraftUploadResult = uploadLocalDraftOnce(
        row = row,
        nowMillis = nowMillis,
        markUploading = { marked += row.id; setState(row.accountId, row.id, LocalDraftState.UPLOADING) },
        upload = { uploaded += it.id; upload(it) },
        discard = {
            discarded += it.id
            deleteById(it.accountId, it.id)
            File(filesDir, localDraftDirName(it.id)).deleteRecursively()
        },
        record = { written -> recordAttempt(row.accountId, row.id, written) },
    )

    /** What an APPEND does with no network — the real message the composer used to display. */
    private fun offline(): Nothing =
        throw java.net.UnknownHostException("Unable to resolve host \"mail.example.org\"")

    private fun write(row: LocalDraftEntity): LocalDraftEntity {
        val fields = LocalDraftEntity::class.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
        val columns = fields.joinToString(", ") { "`${it.name}`" }
        val holes = fields.joinToString(", ") { "?" }
        db.prepareStatement("INSERT OR REPLACE INTO local_drafts ($columns) VALUES ($holes)").use { ps ->
            fields.forEachIndexed { i, field ->
                field.isAccessible = true
                when (val value = field.get(row)) {
                    null -> ps.setObject(i + 1, null)
                    is Boolean -> ps.setInt(i + 1, if (value) 1 else 0)
                    is Enum<*> -> ps.setString(i + 1, value.name)
                    else -> ps.setObject(i + 1, value)
                }
            }
            ps.executeUpdate()
        }
        return row
    }

    private fun read(id: String): Map<String, String?>? =
        db.prepareStatement("SELECT * FROM local_drafts WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                val meta = rs.metaData
                (1..meta.columnCount).associate { meta.getColumnName(it) to rs.getString(it) }
            }
        }

    private fun idsOf(accountId: String): List<String> =
        db.prepareStatement("SELECT id FROM local_drafts WHERE accountId = ?").use { ps ->
            ps.setString(1, accountId)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }

    /** A shipped `LocalDraftDao` statement, bound by name and executed. */
    private fun run(function: String, values: Map<String, Any?>) {
        val (sql, order) = DaoQuerySource.bindOrder(DaoQuerySource.daoQuery("LocalDraftDao", function))
        db.prepareStatement(sql).use { ps ->
            order.forEachIndexed { i, name ->
                require(name in values) { "$function binds :$name and the harness did not supply it" }
                ps.setObject(i + 1, values[name])
            }
            ps.executeUpdate()
        }
    }

    private fun deleteById(accountId: String, id: String) =
        run("deleteById", mapOf("accountId" to accountId, "id" to id))

    private fun deleteForAccount(accountId: String) =
        run("deleteForAccount", mapOf("accountId" to accountId))

    private fun setState(accountId: String, id: String, state: LocalDraftState) =
        run("setState", mapOf("accountId" to accountId, "id" to id, "state" to state.name))

    private fun recordAttempt(accountId: String, id: String, attempt: LocalDraftAttempt) = run(
        "recordAttempt",
        mapOf(
            "accountId" to accountId, "id" to id,
            // PENDING, always: there is no state to park a draft in (LocalDraftState).
            "state" to LocalDraftState.PENDING.name,
            "attemptCount" to attempt.attemptCount, "lastError" to attempt.error,
            "lastAttemptMillis" to attempt.atMillis, "notBeforeMillis" to attempt.notBeforeMillis,
        ),
    )

    @Suppress("LongParameterList")
    private fun row(
        accountId: String = ACCOUNT,
        id: String = LOCAL_DRAFT_ID_PREFIX + "d1",
        state: LocalDraftState = LocalDraftState.PENDING,
        notBeforeMillis: Long = NOW,
        attemptCount: Int = 0,
        textBody: String? = null,
    ) = LocalDraftEntity(
        accountId = accountId, id = id, messageId = "mid@example.org",
        toAddresses = "bob@example.org", subject = "Half written",
        textBody = textBody ?: "text of $id",
        createdAtMillis = NOW, updatedAtMillis = NOW, notBeforeMillis = notBeforeMillis,
        attemptCount = attemptCount, state = state,
    )

    private companion object {
        const val ACCOUNT = "acc"
        const val NOW = 1_760_000_000_000L
        const val TEXT = "I will be there at six, do not wait for me at the station"
    }
}
