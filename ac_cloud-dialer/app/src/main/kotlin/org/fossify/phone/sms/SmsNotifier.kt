package org.fossify.phone.sms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import org.fossify.phone.R

/**
 * Incoming-message notifications: one per conversation, with an inline reply and a mark-as-read
 * action. They go through the "Messages" channel, so the owner's per-channel choices (sound,
 * silent, off) in system settings are the only authority on how loud a message is.
 */
class AndroidSmsNotifier(
    context: Context,
    private val repo: TelephonyRepo = TelephonyRepo(context),
    private val contacts: ContactLookup = ContactLookup(context),
) : SmsNotifier {
    private val context = context.applicationContext

    override fun notifyReceived(address: String, body: String, messageId: Long) {
        notifyMessage(address, body, repo.sms(messageId)?.threadId ?: repo.threadIdFor(address))
    }

    /** One notification for the conversation [threadId]; a newer message replaces the older one. */
    fun notifyMessage(address: String, body: String, threadId: Long) {
        ensureChannel(context)
        val name = contacts.nameOf(address) ?: address
        val notificationId = notificationId(threadId)

        val open = PendingIntent.getActivity(
            context, notificationId,
            ConversationActivity.intent(context, threadId, listOf(address), name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val markRead = PendingIntent.getBroadcast(
            context, notificationId,
            Intent(context, SmsActionReceiver::class.java).setAction(SmsActionReceiver.ACTION_MARK_READ)
                .putExtra(SmsActionReceiver.EXTRA_THREAD, threadId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // RemoteInput writes the typed text into the intent, so this PendingIntent must be mutable.
        val reply = PendingIntent.getBroadcast(
            context, notificationId,
            Intent(context, SmsActionReceiver::class.java).setAction(SmsActionReceiver.ACTION_REPLY)
                .putExtra(SmsActionReceiver.EXTRA_THREAD, threadId).putExtra(SmsActionReceiver.EXTRA_ADDRESS, address),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
        )
        val remoteInput = RemoteInput.Builder(SmsActionReceiver.KEY_REPLY).setLabel(context.getString(R.string.sms_reply)).build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sms_vector)
            .setContentTitle(name)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setGroup(GROUP)
            .addAction(NotificationCompat.Action.Builder(0, context.getString(R.string.sms_reply), reply).addRemoteInput(remoteInput).setAllowGeneratedReplies(true).build())
            .addAction(NotificationCompat.Action.Builder(0, context.getString(R.string.sms_mark_read), markRead).build())
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted: the message is stored, it just does not announce itself
        }
    }

    companion object {
        const val CHANNEL_ID = "sms_messages"
        private const val GROUP = "cloud_dialer_sms"

        fun notificationId(threadId: Long): Int = (threadId and 0x3fffffff).toInt() + 0x40000000

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.sms_channel_name), NotificationManager.IMPORTANCE_HIGH),
                )
            }
        }

        fun cancel(context: Context, threadId: Long) {
            NotificationManagerCompat.from(context).cancel(notificationId(threadId))
        }
    }
}

/** The notification's inline reply and mark-as-read actions. */
class SmsActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val threadId = intent.getLongExtra(EXTRA_THREAD, -1L)
        if (threadId < 0) return
        val pending = goAsync()
        Thread {
            try {
                val repo = TelephonyRepo(context)
                when (intent.action) {
                    ACTION_MARK_READ -> {
                        repo.markThreadRead(threadId)
                        AndroidSmsNotifier.cancel(context, threadId)
                    }

                    ACTION_REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString().orEmpty()
                        val address = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
                        if (text.isNotBlank() && address.isNotEmpty() && SmsRole.isDefault(context)) {
                            val sender = SmsSender(context, repo)
                            val prefs = SmsPrefs(context)
                            val choice = SimSelection.choose(
                                sender.activeSims(), lastUsedInThread = prefs.lastSubscription(threadId),
                                appPreferred = prefs.preferredSubscription, systemDefault = sender.systemDefaultSubscription(),
                            )
                            val sub = (choice as? SimChoice.Use)?.sim?.subscriptionId ?: -1
                            sender.send(listOf(address), text, sub)
                            repo.markThreadRead(threadId)
                        }
                        AndroidSmsNotifier.cancel(context, threadId)
                    }
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_REPLY = "org.fossify.phone.sms.REPLY"
        const val ACTION_MARK_READ = "org.fossify.phone.sms.MARK_READ"
        const val EXTRA_THREAD = "thread_id"
        const val EXTRA_ADDRESS = "address"
        const val KEY_REPLY = "reply_text"
    }
}
