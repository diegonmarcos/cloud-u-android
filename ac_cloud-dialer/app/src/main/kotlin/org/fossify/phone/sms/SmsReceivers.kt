package org.fossify.phone.sms

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.Telephony
import org.fossify.commons.extensions.isNumberBlocked

/**
 * SMS_DELIVER: the broadcast only the default SMS app gets. The role requires this receiver to be
 * protected by BROADCAST_SMS so nothing but the platform can feed it.
 */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val subscriptionId = intent.getIntExtra("subscription", -1)
        val pdus = messages.mapNotNull { m ->
            val from = m.originatingAddress ?: return@mapNotNull null
            IncomingSms(from, m.messageBody.orEmpty(), m.timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis(), subscriptionId)
        }
        val pending = goAsync()
        Thread {
            try {
                handler(context).handle(pdus)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        /** The deliver handler wired to the real provider, block list and notifications. */
        fun handler(context: Context): SmsDeliverHandler {
            val repo = TelephonyRepo(context)
            return SmsDeliverHandler(
                isDefaultSmsApp = { SmsRole.isDefault(context) },
                inbox = repo,
                blockList = { address -> isBlocked(context, address) },
                notifier = AndroidSmsNotifier(context, repo),
            )
        }

        /** The dialer's own block list: the system blocked-number store the call screening reads. */
        fun isBlocked(context: Context, address: String): Boolean = try {
            context.isNumberBlocked(address)
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * RESPOND_VIA_MESSAGE: the "reply with a message" the phone app offers on an incoming call. The
 * role requires this service, protected by SEND_RESPOND_VIA_MESSAGE; it sends the text and stops.
 */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == RESPOND_VIA_MESSAGE) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val to = recipientsOf(intent.data)
            if (text.isNotBlank() && to.isNotEmpty() && SmsRole.isDefault(this)) {
                val app = applicationContext
                Thread {
                    val sender = SmsSender(app)
                    sender.send(to, text, -1)
                }.start()
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    companion object {
        const val RESPOND_VIA_MESSAGE = "android.intent.action.RESPOND_VIA_MESSAGE"

        /** The numbers of an sms:/smsto:/mms:/mmsto: URI ("sms:+1555,+1666?body=x"). */
        fun recipientsOf(uri: Uri?): List<String> {
            val ssp = uri?.schemeSpecificPart ?: return emptyList()
            return ssp.substringBefore('?').split(',', ';').map { Uri.decode(it).trim() }.filter { it.isNotEmpty() }
        }
    }
}
