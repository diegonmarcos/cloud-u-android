package app.sterna.folders

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.sterna.container
import app.sterna.push.NewMailNotifier
import java.util.concurrent.TimeUnit

/**
 * Executes a confirmed folder delete after its undo window. WorkManager persistence
 */
class FolderDeleteWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val accountId = inputData.getString(KEY_ACCOUNT_ID) ?: return Result.success()
        val mailboxId = inputData.getString(KEY_MAILBOX_ID) ?: return Result.success()
        val container = (applicationContext as Application).container
        val credentials = container.accountStore.allCredentials().firstOrNull { it.id == accountId }
            ?: return Result.success()
        return runCatching {
            val deleted = container.mailRepository.deleteFolder(credentials, mailboxId)
            deleted.forEach { NewMailNotifier.clear(applicationContext, accountId, it) }
        }.fold(
            { Result.success() },
            {
                Log.w(TAG, "Folder delete failed for $mailboxId", it)
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            },
        )
    }

    companion object {
        private const val TAG = "FolderDeleteWorker"
        private const val KEY_ACCOUNT_ID = "accountId"
        private const val KEY_MAILBOX_ID = "mailboxId"
        private const val MAX_ATTEMPTS = 3

        /** Margin over the snackbar window so an Undo cancel always wins the race. */
        private const val DELAY_MARGIN_MS = 1_000L

        fun schedule(context: Context, accountId: String, mailboxId: String, holdBackMs: Long) {
            val request = OneTimeWorkRequestBuilder<FolderDeleteWorker>()
                .setInitialDelay(holdBackMs + DELAY_MARGIN_MS, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_ACCOUNT_ID to accountId, KEY_MAILBOX_ID to mailboxId))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName(accountId, mailboxId), ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context, accountId: String, mailboxId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(accountId, mailboxId))
        }

        // The account is part of the name: mailbox ids can collide between same-server accounts
        // (e.g. Stalwart, issue #31), and a colliding name would let one account's folder delete
        // REPLACE or cancel the other's pending one.
        private fun workName(accountId: String, mailboxId: String) = "folder-delete-$accountId-$mailboxId"
    }
}
