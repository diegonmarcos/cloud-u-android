package app.sterna.core.data.mail

import app.sterna.core.data.db.ScheduledSendEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **What a startup books for the `scheduled_sends` table**, executed — the shipped
 */
class ScheduledSendRearmTest {

    @Test fun `a startup books one job per scheduled row, overdue ones included`() {
        val jobs = scheduledSendJobs(
            listOf(
                row(id = 1L, sendAtMillis = NOW - 5_000L),
                row(id = 2L, sendAtMillis = NOW),
                row(id = 3L, sendAtMillis = NOW + 90_000L),
            ),
            NOW,
        )

        // THE OVERDUE ROW IS IN THE LIST, at delay zero — that is the decision of this seam and
        // the whole reason it exists. Dropping it (or hiding it behind a "too old to bother"
        assertEquals(
            listOf(
                ScheduledSendJob(1L, 0L),
                ScheduledSendJob(2L, 0L),
                ScheduledSendJob(3L, 90_000L),
            ),
            jobs,
        )
        // Nothing waiting, nothing booked — the re-arm must be free on the overwhelmingly common
        // start where no message is scheduled at all.
        assertEquals("no rows, no work", emptyList<ScheduledSendJob>(), scheduledSendJobs(emptyList(), NOW))
        // A negative delay is what WorkManager would be handed for a lapsed hour; coerced here so
        // the send goes AT ONCE instead of the request being built on a nonsense number.
        assertEquals("a delay is never negative", 0L, scheduledSendDelayMillis(NOW - 1_000L, NOW))
        // …and a future hour is carried through untouched: coercing everything to zero would fire
        // every scheduled message the moment the app is opened.
        assertEquals(1_000L, scheduledSendDelayMillis(NOW + 1_000L, NOW))
    }

    private fun row(id: Long, sendAtMillis: Long) = ScheduledSendEntity(
        id = id,
        accountId = "acc-1",
        recipients = "someone@example.test",
        subject = "later",
        textBody = "body",
        htmlBody = null,
        fromName = null,
        fromEmail = null,
        inReplyTo = null,
        references = null,
        sendAtMillis = sendAtMillis,
    )

    private companion object {
        const val NOW = 1_760_000_000_000L
    }
}
