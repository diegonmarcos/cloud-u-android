package app.sterna.send

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
 * Enqueues/cancels the WorkManager job that delivers one persistent outbox item. One unique work
 */
object Outbox {
    /**
     * One unique work item per outbox id, so everything here books under ONE name and the policy
     */
    fun enqueue(
        context: Context,
        id: Long,
        initialDelayMillis: Long = 0,
        keepExisting: Boolean = false,
    ) {
        val request = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setInitialDelay(initialDelayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(OutboxWorker.KEY_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        val policy = if (keepExisting) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
        WorkManager.getInstance(context).enqueueUniqueWork(workName(id), policy, request)
    }

    fun cancel(context: Context, id: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
    }

    private fun workName(id: Long) = "outbox-send-$id"
}
