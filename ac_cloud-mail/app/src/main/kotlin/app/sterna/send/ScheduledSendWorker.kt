package app.sterna.send

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.sterna.container
import app.sterna.core.data.mail.accountDepartureIsProven
import app.sterna.core.data.settings.NotificationContent
import app.sterna.push.Notifications
import kotlinx.coroutines.flow.first

/**
 * Fires a message scheduled for a future time. WorkManager persists and fires this; the message
 */
class ScheduledSendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1L)
        if (id < 0) return Result.success()
        val container = (applicationContext as android.app.Application).container
        val repo = container.mailRepository
        val row = repo.scheduledSend(id) ?: return Result.success() // already fired or cancelled
        val credentials = container.accountStore.credentials(row.accountId) ?: run {
            // A `null` here is NOT proof that the account left, and this row is the ONLY copy of
            // the message: `credentials` also answers null for a secret that will not decode, and an
            // unreadable blob makes `accounts()` empty. So the departure is proved first, in the one
            // place a JVM test can execute the rule.
            val departed = accountDepartureIsProven(
                accountsUnreadable = container.accountStore.accountsUnreadable(),
                accountStillListed = container.accountStore.accounts().any { it.id == row.accountId },
            )
            if (departed) {
                // Nothing can ever send this message again: the row goes, as it always has.
                repo.deleteScheduledSend(id)
                return Result.success()
            }
            // Unproven ⇒ nothing is destroyed and nothing is written. retry() and not success():
            // the "Scheduled" screen is still announcing this send, so success() would leave the row
            return Result.retry()
        }
        return try {
            repo.enqueueSend(
                credentials = credentials,
                to = row.recipients.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                subject = row.subject,
                body = row.textBody,
                inReplyTo = row.inReplyTo?.split(" ")?.filter { it.isNotBlank() } ?: emptyList(),
                references = row.references?.split(" ")?.filter { it.isNotBlank() } ?: emptyList(),
                htmlBody = row.htmlBody,
                fromName = row.fromName,
                fromEmail = row.fromEmail,
                cc = row.cc?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
                bcc = row.bcc?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
                // A scheduled send of an edited draft (#63) still replaces the stored draft. The row
                // carries no fidelity verdict, so the id IS the verdict: `scheduleSend` writes
                // draftEmailId only when the body verdict is clean, and `false` here may say so ONLY
                // because of that gate.
                draftEmailId = row.draftEmailId,
                // …and destroys it under the numbering the composer read it in, not one read at
                // delivery (#99): dropped here, the send would destroy blind, hours later, on a
                // folder the server may have renumbered.
                draftUidValidity = row.draftUidValidity,
                bodyIsLossy = false,
                // …and one the user asked a read receipt for still asks: dropped here, a request
                // that only lived on the scheduled row would be lost.
                requestReceipt = row.requestReceipt,
            )
            repo.deleteScheduledSend(id)
            Result.success()
        } catch (_: Throwable) {
            if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                // Give up after retries — but notify instead of dropping it silently, and clear the
                // now-overdue row. The banner names the message only as much as the
                // notification-content setting allows.
                //
                // GUARDED: this read sits INSIDE the catch, and the DataStore has no corruption
                // handler, so a throw would escape doWork() and skip both the banner and the
                // delete. An unreadable setting falls back to the quietest position.
                val content = runCatching { container.settingsRepository.notificationContent.first() }
                    .getOrDefault(NotificationContent.NONE)
                Notifications.notifySendFailed(applicationContext, row.subject, content)
                repo.deleteScheduledSend(id)
                Result.success()
            }
        }
    }

    companion object {
        const val KEY_ID = "scheduled_send_id"
        private const val MAX_ATTEMPTS = 3
    }
}
