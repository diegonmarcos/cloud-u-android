package app.sterna.ui.scheduled

import app.sterna.core.data.db.ScheduledSendEntity
import app.sterna.core.data.mail.DraftSaveOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cancelling a scheduled send must not destroy the message (#170).
 */
class ScheduledSendCancelTest {

    private fun row(
        id: Long = 7L,
        accountId: String = "accA",
        recipients: String = "bob@example.org",
        cc: String? = null,
        bcc: String? = null,
        inReplyTo: String? = null,
        references: String? = null,
        fromName: String? = null,
        fromEmail: String? = null,
        requestReceipt: Boolean = false,
        htmlBody: String? = null,
        textBody: String = "see you there",
    ) = ScheduledSendEntity(
        id = id,
        accountId = accountId,
        recipients = recipients,
        cc = cc,
        bcc = bcc,
        subject = "Six o'clock",
        textBody = textBody,
        htmlBody = htmlBody,
        fromName = fromName,
        fromEmail = fromEmail,
        inReplyTo = inReplyTo,
        references = references,
        sendAtMillis = 1_700_000_000_000L,
        draftEmailId = "d1",
        draftUidValidity = 42L,
        requestReceipt = requestReceipt,
    )

    /** One cancellation, with every seam journalled. */
    private class Run {
        val log = mutableListOf<String>()
        var askedFor: String? = null
        var askedGoneFor: String? = null
        var saved: ScheduledSendDraft? = null
    }

    private suspend fun cancel(
        entity: ScheduledSendEntity?,
        credentials: String? = "creds",
        accountIsGone: Boolean = true,
        outcome: DraftSaveOutcome = DraftSaveOutcome.SAVED,
        throwOnSave: Throwable? = null,
        run: Run,
    ): ScheduledCancelReport? = cancelScheduledSend(
        id = 7L,
        row = { entity },
        credentials = { accountId -> run.askedFor = accountId; credentials },
        accountIsGone = { accountId -> run.askedGoneFor = accountId; accountIsGone },
        saveDraft = { _, draft ->
            run.log += "save"
            run.saved = draft
            throwOnSave?.let { throw it }
            outcome
        },
        dropWorker = { run.log += "dropWorker" },
        deleteRow = { run.log += "delete" },
    )

    // --- 1. the order: the copy exists before anything is destroyed -----------------------------

    @Test fun `a successful deposit files the draft before it drops the job and the row`() = runTest {
        val r = Run()
        val report = cancel(row(), run = r)

        assertEquals(ScheduledCancelReport.RETURNED_TO_DRAFTS, report)
        assertEquals(
            "the queued row is the ONLY copy of the text: the deposit has to be complete before " +
                "the delete, never after it and never in a finally — and the row goes BEFORE the " +
                "job, because deleting it is a committed write while cancelling the job proves " +
                "nothing when it returns. A row that survives is re-armed at every process start",
            listOf("save", "delete", "dropWorker"),
            r.log,
        )
        assertTrue(
            "'save' must come before 'delete' — the log was ${r.log}",
            r.log.indexOf("save") < r.log.indexOf("delete"),
        )
    }

    // --- 2. a deposit that throws destroys nothing ----------------------------------------------

    @Test fun `a deposit that throws leaves the scheduled row exactly where it was`() = runTest {
        val r = Run()
        val report = cancel(row(), throwOnSave = IllegalStateException("no Drafts folder"), run = r)

        assertEquals(
            "nothing else holds this text: a failed deposit must say so and keep the row",
            ScheduledCancelReport.NOT_CANCELLED,
            report,
        )
        assertEquals(
            "neither the row nor its job may be dropped when the deposit failed — the log was ${r.log}",
            listOf("save"),
            r.log,
        )
    }

    // --- 3/4. every save outcome that means "a copy exists" drops the row -----------------------

    @Test fun `a draft kept on the device is a success and the row goes`() = runTest {
        val r = Run()
        val report = cancel(row(), outcome = DraftSaveOutcome.KEPT_ON_DEVICE, run = r)

        assertEquals(
            "an offline deposit writes a durable local row and books the upload: reading it as a " +
                "failure keeps the schedule, and the cancelled message GOES OUT at its hour",
            ScheduledCancelReport.RETURNED_TO_DRAFTS,
            report,
        )
        assertEquals(listOf("save", "delete", "dropWorker"), r.log)
    }

    @Test fun `a draft saved on the server is a success and the row goes`() = runTest {
        val r = Run()
        assertEquals(
            ScheduledCancelReport.RETURNED_TO_DRAFTS,
            cancel(row(), outcome = DraftSaveOutcome.SAVED, run = r),
        )
        assertEquals(listOf("save", "delete", "dropWorker"), r.log)
    }

    @Test fun `a deposit that spared some original is a success and the row goes`() = runTest {
        val r = Run()
        assertEquals(
            ScheduledCancelReport.RETURNED_TO_DRAFTS,
            cancel(row(), outcome = DraftSaveOutcome.ORIGINAL_KEPT, run = r),
        )
        assertEquals(listOf("save", "delete", "dropWorker"), r.log)
    }

    // --- 5. an account that is gone ---------------------------------------------------------

    @Test fun `an account that no longer exists cancels without a deposit and says so`() = runTest {
        val r = Run()
        val report = cancel(row(), credentials = null, run = r)

        assertEquals(ScheduledCancelReport.ACCOUNT_GONE, report)
        assertEquals(
            "there is no account left to file it under and none left to send it either, so the " +
                "row goes first — a committed write, and the only thing the re-arming at start-up " +
                "reads — and the job after it. No deposit was attempted and the screen must not " +
                "claim one",
            listOf("delete", "dropWorker"),
            r.log,
        )
        assertNull(r.saved)
    }

    // --- 6. a row that is no longer there --------------------------------------------------

    @Test fun `a row that already fired is nothing to do and nothing to say`() = runTest {
        val r = Run()
        assertNull(cancel(null, run = r))
        assertEquals(emptyList<String>(), r.log)
    }

    // --- 7. the account asked for is the row's -----------------------------------------------

    @Test fun `the credentials asked for are the row's account, not a displayed one`() = runTest {
        val r = Run()
        cancel(row(accountId = "accB"), run = r)

        assertEquals(
            "the Scheduled screen is not filtered by account and does not show which one a line " +
                "belongs to: filing under anything but the row's account files into the wrong " +
                "Drafts, or fails to file at all",
            "accB",
            r.askedFor,
        )
    }

    // --- 8. the mapping, executed --------------------------------------------------------------

    @Test fun `a scheduled row reads back into the fields a draft is saved from`() {
        val draft = draftFromScheduledSend(
            row(
                recipients = "bob@example.org, carol@example.org",
                cc = "dan@example.org",
                bcc = null,
                inReplyTo = "<a@x>",
                references = "<a@x> <b@x>",
                fromName = "Alex",
                fromEmail = "alex@masto.top",
                requestReceipt = true,
                htmlBody = "<p>see you there</p>",
            ),
        )

        assertEquals(listOf("bob@example.org", "carol@example.org"), draft.to)
        assertEquals(listOf("dan@example.org"), draft.cc)
        assertEquals(emptyList<String>(), draft.bcc)
        assertEquals("Six o'clock", draft.subject)
        assertEquals("see you there", draft.body)
        assertEquals(listOf("<a@x>"), draft.inReplyTo)
        assertEquals(listOf("<a@x>", "<b@x>"), draft.references)
        assertEquals(
            "without the chosen identity the returned draft goes back under the login account's " +
                "address instead of the one the message was written as (#31)",
            "Alex",
            draft.fromName,
        )
        assertEquals(
            "without the chosen identity the returned draft goes back under the login account's " +
                "address instead of the one the message was written as (#31)",
            "alex@masto.top",
            draft.fromEmail,
        )
        assertTrue("a message that asked for a receipt must still ask for one", draft.requestReceipt)
    }

    // --- 8 bis. the styling a cancelled message was written with (#131) -------------------------

    @Test fun `a styled message cancelled from the schedule is filed with its styling`() {
        // The row is the only copy: the deposit is what the user gets back. Filed on the text
        // alone — which is what happened until #131 — the bold she applied is gone for good, with
        // nothing on screen having said so.
        val draft = draftFromScheduledSend(row(htmlBody = "<b>see</b> you there"))

        assertEquals("see you there", draft.body)
        assertEquals(
            "…and it is stored in the form a REOPEN can read back, not the row's html copied over",
            "<b>see</b> you there",
            draft.html,
        )
    }

    @Test fun `the deposit takes its text and its styling out of the same part`() {
        // The witness the test above cannot be: there the row's two columns say the same words,
        // so `body = row.textBody` passes it. Here they DIFFER — which a row is free to do — and
        val draft = draftFromScheduledSend(
            row(htmlBody = "<b>see</b> you there", textBody = "ok"),
        )

        assertEquals("see you there", draft.body)
        assertEquals("<b>see</b> you there", draft.html)
    }

    @Test fun `a cancelled message keeps its link, in the html AND in the text filed`() {
        // Both halves, and the text one is the mutation this exists for. `rich.text` there —
        // which is what this file said until the links (#131) — files a draft whose text part is
        // "see you there" while the html part beside it, built from the SAME body, carries the
        // anchor. One deposit, two parts, two different messages about one address.
        val draft = draftFromScheduledSend(
            row(htmlBody = """see <a href="https://x">you</a> there"""),
        )

        assertEquals(
            "the text part must be the ONE plain-text answer the composer's own save writes",
            "see you <https://x> there",
            draft.body,
        )
        assertEquals("""see <a href="https://x">you</a> there""", draft.html)
    }

    @Test fun `a message with no html is filed exactly as it always was`() {
        val draft = draftFromScheduledSend(row(htmlBody = null))

        assertEquals("see you there", draft.body)
        assertNull("a plain deposit stores no html part at all", draft.html)
    }

    @Test fun `a message whose html this editor cannot read is filed as text`() {
        // A forward (its carried original appended), or an imported HTML signature: the deposit
        // flattens it, exactly as "Save draft" does — and says so by storing no html.
        val draft = draftFromScheduledSend(
            row(htmlBody = "see you there<br><br><div>-------- Forwarded --------</div>"),
        )

        assertEquals("see you there", draft.body)
        assertNull(
            "⛔ storing the row's html as it stands would file a draft that reopens as " +
                "unparseable markup, i.e. as text, one save later — and claim a fidelity nothing " +
                "downstream can honour",
            draft.html,
        )
    }

    // --- 5 bis. no credentials is NOT proof the account is gone --------------------------------

    @Test fun `no credentials while the account is still there destroys nothing`() = runTest {
        val r = Run()
        val report = cancel(row(), credentials = null, accountIsGone = false, run = r)

        assertEquals(
            "`AccountStore.credentials` answers null for a secret that will not decode as well as " +
                "for an account that is gone, and an unreadable account blob answers an EMPTY " +
                "list: destroying the row on that null deletes the only copy of the text because " +
                "a Keystore key rotated, and tells the user her account is gone",
            ScheduledCancelReport.NOT_CANCELLED,
            report,
        )
        assertEquals(
            "nothing may be destroyed on an unproven null — the log was ${'$'}{r.log}",
            emptyList<String>(),
            r.log,
        )
    }

    @Test fun `the account looked up for the proof is the row's too`() = runTest {
        val r = Run()
        cancel(row(accountId = "accB"), credentials = null, run = r)

        assertEquals("accB", r.askedGoneFor)
    }

    // --- the cancellation shelter, exercised ----------------------------------------------------

    @Test fun `a cancellation landing mid-deposit still finishes the deposit and the delete`() = runTest {
        val r = Run()
        val depositing = CompletableDeferred<Unit>()
        val job = launch {
            cancelScheduledSend(
                id = 7L,
                row = { row() },
                credentials = { "creds" },
                accountIsGone = { false },
                saveDraft = { _, _ ->
                    r.log += "save"
                    depositing.complete(Unit)
                    yield()
                    DraftSaveOutcome.SAVED
                },
                dropWorker = { r.log += "dropWorker" },
                deleteRow = { r.log += "delete" },
            )
        }
        depositing.await()
        job.cancel()
        job.join()

        assertEquals(
            "cancellation landing between the deposit and the delete leaves the message in Drafts " +
                "AND still scheduled, so the cancelled message goes out at its hour anyway",
            listOf("save", "delete", "dropWorker"),
            r.log,
        )
    }

    @Test fun `a cancellation that arrives before anything is written is honoured`() = runTest {
        val r = Run()
        val job = launch {
            cancelScheduledSend(
                id = 7L,
                row = { yield(); row() },
                credentials = { "creds" },
                accountIsGone = { false },
                saveDraft = { _, _ -> r.log += "save"; DraftSaveOutcome.SAVED },
                dropWorker = { r.log += "dropWorker" },
                deleteRow = { r.log += "delete" },
            )
        }
        job.cancel()
        job.join()

        assertEquals(
            "nothing is written yet at that point, so the message simply stays scheduled — which " +
                "is where the user last saw it. A shelter stretched over the wait in front of it " +
                "would file a draft for a tap that never completed",
            emptyList<String>(),
            r.log,
        )
    }

    // --- 9. the fields REALLY handed to saveDraft ------------------------------------------------

    @Test fun `the draft handed to saveDraft is the row, whole`() = runTest {
        val r = Run()
        cancel(
            row(
                recipients = "bob@example.org, carol@example.org",
                cc = "dan@example.org",
                bcc = "eve@example.org",
                inReplyTo = "<a@x>",
                references = "<a@x> <b@x>",
                fromName = "Alex",
                fromEmail = "alex@masto.top",
                requestReceipt = true,
                htmlBody = "<p>see you there</p>",
            ),
            run = r,
        )

        assertEquals(
            "a mapping that is correct but NOT PLUGGED IN files an empty draft and reads green " +
                "everywhere else",
            ScheduledSendDraft(
                to = listOf("bob@example.org", "carol@example.org"),
                cc = listOf("dan@example.org"),
                bcc = listOf("eve@example.org"),
                subject = "Six o'clock",
                body = "see you there",
                // The row's html is a `<p>`, which this editor cannot read back: the draft is
                // filed on its text and stores no html at all (#131).
                html = null,
                inReplyTo = listOf("<a@x>"),
                references = listOf("<a@x>", "<b@x>"),
                fromName = "Alex",
                fromEmail = "alex@masto.top",
                requestReceipt = true,
            ),
            r.saved,
        )
    }
}
