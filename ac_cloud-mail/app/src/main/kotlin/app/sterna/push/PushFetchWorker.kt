package app.sterna.push

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.sterna.container

/**
 * One-shot fetch+notify for a single account, expedited — the fetch path when a
 */
class PushFetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val accountId = inputData.getString(KEY_ACCOUNT_ID) ?: return Result.success()
        val reset = inputData.getBoolean(KEY_RESET_INBOX, false)
        val store = (applicationContext as Application).container.accountStore
        val credentials = store.allCredentials().firstOrNull { it.id == accountId }
            ?: return Result.success()
        if (!store.notificationsEnabled(accountId)) return Result.success()
        return runCatching { FetchAndNotify.run(applicationContext, credentials, resetBaselines = reset) }
            .fold(
                { Result.success() },
                {
                    Log.w(TAG, "Push-triggered fetch failed for account $accountId", it)
                    if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
                },
            )
    }

    companion object {
        private const val TAG = "PushFetchWorker"
        private const val KEY_ACCOUNT_ID = "accountId"
        private const val KEY_RESET_INBOX = "resetInbox"
        private const val MAX_ATTEMPTS = 3

        /** Unique per account, KEEP: one queued fetch covers coalesced pushes. */
        fun enqueue(context: Context, accountId: String, resetInbox: Boolean = false) {
            val builder = OneTimeWorkRequestBuilder<PushFetchWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_ACCOUNT_ID to accountId, KEY_RESET_INBOX to resetInbox))
            // Expedited work runs as a foreground service on Android 11 and lower, which requires
            // overriding getForegroundInfo(); the CoroutineWorker default throws "Not implemented"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            WorkManager.getInstance(context)
                .enqueueUniqueWork("push-fetch-$accountId", ExistingWorkPolicy.KEEP, builder.build())
        }
    }
}
