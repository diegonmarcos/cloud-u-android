package app.sterna.send

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object ScheduledSends {
    /**
     * One unique work item per scheduled-send id, so everything here books under ONE name and the
     */
    fun enqueue(
        context: Context,
        id: Long,
        initialDelayMillis: Long,
        keepExisting: Boolean = false,
    ) {
        val request = OneTimeWorkRequestBuilder<ScheduledSendWorker>()
            .setInitialDelay(initialDelayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(ScheduledSendWorker.KEY_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        val policy = if (keepExisting) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
        WorkManager.getInstance(context).enqueueUniqueWork(workName(id), policy, request)
    }

    fun cancel(context: Context, id: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
    }

    private fun workName(id: Long) = "scheduled-send-$id"
}
