package app.sterna.drafts

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Books the WorkManager job that puts ONE draft kept on the phone (#95) onto the server. Modelled on
 */
object LocalDraftUploads {
    const val KEY_ACCOUNT = "local_draft_account"
    const val KEY_ID = "local_draft_id"

    /**
     * One work item per (account, draft). Both halves are in the key: the row is keyed on both
     */
    fun enqueue(
        context: Context,
        accountId: String,
        id: String,
        delayMillis: Long = 0,
        keepExisting: Boolean = false,
    ) {
        val request = OneTimeWorkRequestBuilder<LocalDraftUploadWorker>()
            .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_ACCOUNT to accountId, KEY_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        val policy = if (keepExisting) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName(accountId, id), policy, request)
    }

    private fun workName(accountId: String, id: String) = "local-draft-upload-$accountId-$id"
}
