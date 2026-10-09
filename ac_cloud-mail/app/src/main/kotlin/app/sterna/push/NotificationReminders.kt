package app.sterna.push

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * The Remind button of a new-mail notification: take the banner down now, put the same one back after
 * the configured delay. WorkManager, so the reminder survives the app dying and a reboot.
 *
 * One unique work name per message, so a second tap replaces the first and [cancel] has one thing to
 * name. [cancel] is called wherever a message stops being a pending new mail (read, deleted).
 */
object NotificationReminders {
    private const val FILE = "mail_notifications"
    const val KEY_DELAY_MINUTES = "remind_delay_minutes"
    const val DEFAULT_DELAY_MINUTES = 60

    /** The choices Configs offers, in minutes. */
    val DELAY_CHOICES = listOf(15, 30, 60, 120, 240)

    fun delayMinutes(context: Context): Int =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_DELAY_MINUTES, DEFAULT_DELAY_MINUTES).takeIf { it > 0 } ?: DEFAULT_DELAY_MINUTES

    fun setDelayMinutes(context: Context, minutes: Int) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt(KEY_DELAY_MINUTES, minutes).apply()
    }

    fun workName(accountId: String, emailId: String) = "remind:$accountId:$emailId"

    /** The request the Remind tap enqueues: the configured delay, and the message it re-posts. */
    fun delayMillis(minutes: Int): Long = TimeUnit.MINUTES.toMillis(minutes.toLong())

    fun inputData(accountId: String, emailId: String): Data = Data.Builder()
        .putString(RemindWorker.KEY_ACCOUNT, accountId)
        .putString(RemindWorker.KEY_ID, emailId)
        .build()

    /** The re-post, due [minutes] from now. Built apart from WorkManager so the delay is testable. */
    fun requestFor(minutes: Int, accountId: String, emailId: String): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<RemindWorker>()
            .setInitialDelay(delayMillis(minutes), TimeUnit.MILLISECONDS)
            .setInputData(inputData(accountId, emailId))
            .build()

    fun schedule(context: Context, accountId: String, emailId: String) {
        val request = requestFor(delayMinutes(context), accountId, emailId)
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(workName(accountId, emailId), ExistingWorkPolicy.REPLACE, request)
    }

    /** Drop a pending reminder: the message was read or deleted in the meantime. */
    fun cancel(context: Context, accountId: String, emailId: String) {
        runCatching { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(accountId, emailId)) }
    }
}
