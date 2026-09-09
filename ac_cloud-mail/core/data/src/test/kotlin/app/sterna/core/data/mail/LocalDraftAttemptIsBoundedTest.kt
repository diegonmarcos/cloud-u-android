package app.sterna.core.data.mail

import app.sterna.core.data.db.LOCAL_DRAFT_ID_PREFIX
import app.sterna.core.data.db.LocalDraftEntity
import app.sterna.core.data.db.LocalDraftState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The bound on ONE deferred attempt at a draft** (#95), EXECUTED — never read off the source.
 */
class LocalDraftAttemptIsBoundedTest {

    /** What the shipped decision handed each seam, in order — so "never" and "exactly once" count. */
    private val recorded = mutableListOf<LocalDraftAttempt>()
    private val discarded = mutableListOf<String>()
    private val marked = mutableListOf<String>()

    @Test fun `the shipped bound is five minutes — above one read, under WorkManager's stop`() {
        // Written out, not derived from the two numbers around it: a test that recomputes
        // "somewhere between a read and ten minutes" stays green for any value in that range, and
        // the two ends are what the choice is made of.
        assertEquals(
            "⛔ five minutes. Raised past WorkManager's ten, the worker is stopped mid-attempt and " +
                "the failure is never recorded — which is the bug, back, with the bound in place. " +
                "Lowered towards a minute, this bound fires before the READ budget does and the row " +
                "is given \"the upload did not finish\" instead of the transport's own true cause.",
            300_000L,
            LOCAL_DRAFT_ATTEMPT_BUDGET_MS,
        )
        assertEquals(
            "the per-read budget this one has to stay an order of magnitude above, so that a " +
                "bounded READ always fails first and writes the real reason on the row",
            60_000,
            LOCAL_DRAFT_UPLOAD_BUDGET_MS,
        )
    }

    @Test fun `an attempt that never returns expires, and NOT as a cancellation`() {
        val started = System.currentTimeMillis()

        val thrown = assertThrows(Throwable::class.java) {
            runBlocking { withLocalDraftAttemptBudget(BUDGET_MS) { awaitCancellation() } }
        }
        val elapsed = System.currentTimeMillis() - started

        // A WINDOW AROUND THE BUDGET, not "some day this century". This is the only assertion in
        // the suite that ties the constant handed in to the deadline actually installed: the expiry
        assertTrue(
            "⛔ the call has to come back ON ITS BUDGET — the block never returns by itself, so " +
                "$elapsed ms is the deadline that was really installed for a ${BUDGET_MS} ms " +
                "budget. Below the window: the bound is tighter than it says and a healthy upload " +
                "is cut short. Above it: the bound is looser than it says, and past ten minutes it " +
                "is no bound at all",
            elapsed in 900L..1_800L,
        )
        assertTrue(
            "the expiry must arrive as LocalDraftAttemptExpired, got ${thrown.javaClass.name}",
            thrown is LocalDraftAttemptExpired,
        )
        assertFalse(
            "⛔ THE trap: a TimeoutCancellationException IS a CancellationException, and " +
                "uploadLocalDraftOnce rethrows one untouched — the row would stay UPLOADING with " +
                "no counter, no cause and no backoff, i.e. the measured symptom under a surface " +
                "that looks bounded",
            thrown is CancellationException,
        )
        assertEquals("and it says how long it waited", "The upload did not finish within 1 s.", thrown.message)
    }

    @Test fun `an expired attempt is recorded on the row like any refusal from the server`() = runBlocking {
        val row = row()

        val result = attempt(row) { withLocalDraftAttemptBudget(BUDGET_MS) { awaitCancellation() } }

        assertEquals(
            "⛔ an ordinary failure: kept for later, with the row's first backoff armed",
            LocalDraftUploadResult(LocalDraftUploadStep.KEPT_FOR_LATER, NOW + 30_000L),
            result,
        )
        // This is the assertion that says `withTimeout` cancelled its own CHILD and not the
        // coroutine calling it: `record` runs after the throw, in the caller, and it is suspending.
        assertEquals("exactly one attempt written down", 1, recorded.size)
        val written = recorded.single()
        assertEquals("counted, from the row", 1, written.attemptCount)
        assertEquals("The upload did not finish within 1 s.", written.error)
        assertEquals(NOW, written.atMillis)
        assertEquals("and the backoff moved", NOW + 30_000L, written.notBeforeMillis)
        assertEquals("it was marked in flight first", listOf(row.id), marked)
        assertEquals("⛔ and nothing was consumed — the text is still on the phone", emptyList<String>(), discarded)
    }

    @Test fun `an attempt that finishes inside its budget is handed through untouched`() = runBlocking {
        // The witness. Without it, a bound of zero — or one that throws whatever the block did —
        // would satisfy every assertion above and no draft would ever leave the phone again.
        assertEquals(
            "the block's own answer comes back, not the wrapper's",
            "what the server said",
            withLocalDraftAttemptBudget(60_000L) { delay(20); "what the server said" },
        )

        val row = row()
        val result = attempt(row) {
            withLocalDraftAttemptBudget(60_000L) { delay(20); DraftSaveOutcome.SAVED }
        }

        assertEquals(LocalDraftUploadResult(LocalDraftUploadStep.UPLOADED, null), result)
        assertEquals("the row is consumed, exactly as with no bound at all", listOf(row.id), discarded)
        assertEquals("and nothing is written down as a failure", emptyList<LocalDraftAttempt>(), recorded)
    }

    @Test fun `a worker really stopped still comes out as a cancellation, not as a failed attempt`() = runBlocking {
        // The other witness, and the one mutation that is invisible everywhere else: catching
        // CancellationException in general instead of the expiry alone. WorkManager stopping the
        //
        // REALLY cancelled, not a CancellationException thrown by hand: the budget here is ten
        // minutes, so nothing but the cancellation can end this attempt.
        var thrown: Throwable? = null
        val row = row()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            @Suppress("TooGenericExceptionCaught")
            try {
                attempt(row) { withLocalDraftAttemptBudget(600_000L) { awaitCancellation() } }
            } catch (stopped: Throwable) {
                thrown = stopped
            }
        }

        job.cancelAndJoin()

        val seen = thrown
        assertTrue("the job under test is the one that was cancelled", job.isCancelled)
        assertTrue(
            "⛔ a system stop must stay a cancellation all the way out, got ${seen?.javaClass?.name}",
            seen is CancellationException,
        )
        assertFalse("⛔ and it must not be dressed up as an expiry", seen is LocalDraftAttemptExpired)
        assertEquals(
            "⛔ a stop is not a failed attempt: written down, a phone that dozes often would push a " +
                "perfectly reachable draft out to six-hourly retries without one real failure",
            emptyList<LocalDraftAttempt>(),
            recorded,
        )
        assertEquals(emptyList<String>(), discarded)
    }

    @Test fun `a stop landing WHILE the expiry is being written down still writes it`() = runBlocking {
        // THE case the test above cannot see: it cancels BEFORE the deadline, so the attempt never
        // reaches `record` at all. Here the two are IMBRICATED, and they arrive together by
        //
        // Without `NonCancellable` around that write, `record` throws at its first suspension point
        // and NOTHING is written: no counter, no cause, no backoff — the row stays UPLOADING and the
        val row = row()
        val writing = CompletableDeferred<Unit>()
        val letTheWriteFinish = CompletableDeferred<Unit>()
        var result: LocalDraftUploadResult? = null
        var cameOutAs: Throwable? = null

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            @Suppress("TooGenericExceptionCaught")
            try {
                result = uploadLocalDraftOnce(
                    row = row,
                    nowMillis = NOW,
                    markUploading = { marked += row.id },
                    // A REAL expiry, through the shipped wrapper — not a hand-thrown exception.
                    upload = { withLocalDraftAttemptBudget(BUDGET_MS) { awaitCancellation() } },
                    discard = { discarded += it.id },
                    record = {
                        // The Room write has begun and has not landed: `recordAttempt` is a
                        // suspending DAO call, so it HAS a suspension point in the middle of it.
                        writing.complete(Unit)
                        letTheWriteFinish.await()
                        recorded += it
                    },
                )
            } catch (stopped: Throwable) {
                cameOutAs = stopped
            }
        }

        writing.await()
        job.cancel()
        letTheWriteFinish.complete(Unit)
        job.join()

        assertTrue("the worker really was stopped mid-write", job.isCancelled)
        assertEquals(
            "⛔ the write must not throw the stop back at the caller either, got " +
                "${cameOutAs?.javaClass?.name}",
            null,
            cameOutAs,
        )
        assertEquals(
            "⛔ the attempt must be on the row anyway — dropped, the draft is left UPLOADING with " +
                "attemptCount frozen, lastError null and notBeforeMillis unmoved, which is the " +
                "measured symptom back in full",
            1,
            recorded.size,
        )
        val written = recorded.single()
        assertEquals("counted, from the row", 1, written.attemptCount)
        assertEquals("with the expiry's own words", "The upload did not finish within 1 s.", written.error)
        assertEquals(NOW, written.atMillis)
        assertEquals("and the backoff really moved", NOW + 30_000L, written.notBeforeMillis)
        assertEquals("⛔ and nothing was consumed — the text is still on the phone", emptyList<String>(), discarded)
        assertEquals("it was marked in flight first", listOf(row.id), marked)
        assertEquals(
            "⛔ NonCancellable covers the WRITE, not the whole attempt: the caller is still a " +
                "cancelled coroutine and must be told to come back",
            LocalDraftUploadResult(LocalDraftUploadStep.KEPT_FOR_LATER, NOW + 30_000L),
            result,
        )
    }

    // ---- the harness ------------------------------------------------------------------------------

    /** One shipped attempt at [row], with every seam recording what it was handed. */
    private suspend fun attempt(
        row: LocalDraftEntity,
        upload: suspend (LocalDraftEntity) -> DraftSaveOutcome,
    ): LocalDraftUploadResult = uploadLocalDraftOnce(
        row = row,
        nowMillis = NOW,
        markUploading = { marked += row.id },
        upload = upload,
        discard = { discarded += it.id },
        record = { recorded += it },
    )

    private fun row() = LocalDraftEntity(
        accountId = "acc",
        id = LOCAL_DRAFT_ID_PREFIX + "d1",
        messageId = "mid@example.org",
        toAddresses = "bob@example.org",
        subject = "Half written",
        textBody = "I will be there at six, do not wait for me at the station",
        createdAtMillis = NOW,
        updatedAtMillis = NOW,
        notBeforeMillis = NOW,
        attemptCount = 0,
        state = LocalDraftState.PENDING,
    )

    private companion object {
        const val NOW = 1_760_000_000_000L

        /** Short on purpose: the shipped value is five minutes, and a suite may not cost five. */
        const val BUDGET_MS = 1_000L
    }
}
