package app.sterna.snooze

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Schedules/cancels the WorkManager job that un-snoozes a message at its time. */
object Snoozes {
    fun enqueue(context: Context, emailId: String, accountId: String, until: Long) {
        val delay = (until - System.currentTimeMillis()).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<SnoozeWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(SnoozeWorker.KEY_ID to emailId, SnoozeWorker.KEY_ACCOUNT to accountId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(accountId, emailId), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context, accountId: String, emailId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(accountId, emailId))
    }

    // The account is part of the name: email ids can collide between same-server accounts
    // (issue #31), and a colliding name would let one account's snooze REPLACE the other's.
    private fun workName(accountId: String, emailId: String) = "snooze-$accountId-$emailId"
}
