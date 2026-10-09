package app.sterna.push

import android.app.Application
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.sterna.container

/**
 * Re-posts the notification a Remind tap took down. A message read or deleted since is not re-posted
 * (the explicit cancel normally got there first; this is the belt for a read made where no cancel ran).
 */
class RemindWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val emailId = inputData.getString(KEY_ID) ?: return Result.success()
        val accountId = inputData.getString(KEY_ACCOUNT) ?: return Result.success()
        val container = (applicationContext as Application).container
        val email = container.mailRepository.cachedEmail(accountId, emailId) ?: return Result.success()
        if (email.isSeen) return Result.success()
        val (_, content) = NewMailNotifier.options(applicationContext)
        Notifications.notifyNewMail(
            applicationContext, email, accountId,
            // A reminder that arrives silently is not one.
            silent = false,
            folderName = null,
            mailboxId = email.mailboxId,
            content = content,
            summarised = false,
        )
        return Result.success()
    }

    companion object {
        const val KEY_ID = "remind_email_id"
        const val KEY_ACCOUNT = "remind_account_id"
    }
}
