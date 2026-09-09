package app.sterna.drafts

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.sterna.container
import app.sterna.core.data.mail.uploadLocalDraftRebooking

/**
 * Puts one draft the phone kept (#95) onto the server, whenever the network allows. WorkManager
 */
class LocalDraftUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val accountId = inputData.getString(LocalDraftUploads.KEY_ACCOUNT) ?: return Result.success()
        val id = inputData.getString(LocalDraftUploads.KEY_ID) ?: return Result.success()
        val container = (applicationContext as android.app.Application).container
        // No credentials: the account is gone, or its store cannot be read right now. The row is
        // LEFT ALONE — dropping it here is the one deletion this whole chantier exists to forbid,
        // and it would be one transient store failure away. A signed-out account's drafts are
        // purged by StorageRepository.purgeAccount, on the event that actually means "done with it".
        val credentials = container.accountStore.credentials(accountId) ?: return Result.success()
        // Every re-booking decision — including the one taken when this worker is STOPPED
        // mid-upload, where nothing suspending can run any more — is `uploadLocalDraftRebooking`'s,
        // executed by a JVM test. What is left here is the store, the queue, and a clock.
        uploadLocalDraftRebooking(
            now = { System.currentTimeMillis() },
            upload = { container.mailRepository.uploadLocalDraft(credentials, id) },
            rebook = { delay, onlyIfNothingBooked ->
                LocalDraftUploads.enqueue(applicationContext, accountId, id, delay, onlyIfNothingBooked)
            },
        )
        return Result.success()
    }
}
