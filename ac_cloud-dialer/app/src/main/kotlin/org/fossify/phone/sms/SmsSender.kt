package org.fossify.phone.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager

/**
 * Sending. Every outgoing message is written to the provider first (outbox) and then handed to the
 * radio; [SmsStatusReceiver] moves the row to sent / failed from the sent broadcast and records the
 * delivery report. A long text goes out as a multipart message through the platform's own divider.
 * Writing needs the default-SMS role, so a non-default install sends nothing (the composer is
 * disabled there; this returns an empty list rather than send a message it cannot record).
 */
class SmsSender(context: Context, private val repo: TelephonyRepo = TelephonyRepo(context)) {
    private val context = context.applicationContext

    /** The active SIMs, slot order; empty without the phone-state grant or without a SIM. */
    fun activeSims(): List<SimInfo> {
        val manager = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        val list = try { manager.activeSubscriptionInfoList } catch (e: SecurityException) { null } ?: return emptyList()
        return list.map { SimInfo(it.subscriptionId, it.simSlotIndex, it.displayName?.toString() ?: "SIM ${it.simSlotIndex + 1}") }
            .sortedBy { it.slotIndex }
    }

    fun systemDefaultSubscription(): Int = SmsManager.getDefaultSmsSubscriptionId()

    @Suppress("DEPRECATION")
    private fun manager(subscriptionId: Int): SmsManager {
        val sdk = Build.VERSION.SDK_INT
        if (subscriptionId < 0) {
            return if (sdk >= Build.VERSION_CODES.S) context.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        }
        return if (sdk >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscriptionId)
        } else {
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }
    }

    /** Sends [body] to each recipient on [subscriptionId] (-1 = the system default). Returns the provider ids of the messages stored. */
    fun send(addresses: List<String>, body: String, subscriptionId: Int): List<Long> {
        val ids = ArrayList<Long>()
        if (body.isBlank()) return ids
        for (address in addresses.map { it.trim() }.filter { it.isNotEmpty() }) {
            val id = repo.insertOutgoing(address, body, subscriptionId) ?: continue
            dispatch(id, address, body, subscriptionId)
            ids.add(id)
        }
        return ids
    }

    /** Sends a failed message again, on its own SIM unless the owner picked another. */
    fun retry(messageId: Long, subscriptionId: Int? = null): Boolean {
        val message = repo.sms(messageId) ?: return false
        if (!message.canRetry) return false
        val sub = subscriptionId ?: message.subscriptionId
        if (!repo.setType(messageId, SmsType.OUTBOX, SmsStatusCode.NONE)) return false
        dispatch(messageId, message.address, message.body, sub)
        return true
    }

    private fun dispatch(id: Long, address: String, body: String, subscriptionId: Int) {
        try {
            val sms = manager(subscriptionId)
            val parts = sms.divideMessage(body)
            val sent = ArrayList<PendingIntent>()
            val delivered = ArrayList<PendingIntent>()
            for (i in parts.indices) {
                sent.add(statusIntent(SmsStatusReceiver.ACTION_SENT, id, i, parts.size, mutable = false))
                delivered.add(statusIntent(SmsStatusReceiver.ACTION_DELIVERED, id, i, parts.size, mutable = true))
            }
            if (parts.size > 1) {
                sms.sendMultipartTextMessage(address, null, parts, sent, delivered)
            } else {
                sms.sendTextMessage(address, null, body, sent[0], delivered[0])
            }
        } catch (e: Exception) {
            repo.setType(id, SmsType.FAILED, SmsStatusCode.FAILED)
        }
    }

    private fun statusIntent(action: String, id: Long, part: Int, parts: Int, mutable: Boolean): PendingIntent {
        val intent = Intent(context, SmsStatusReceiver::class.java).setAction(action)
            .putExtra(SmsStatusReceiver.EXTRA_ID, id).putExtra(SmsStatusReceiver.EXTRA_PART, part).putExtra(SmsStatusReceiver.EXTRA_PARTS, parts)
        // The delivery report's PDU is filled into the intent by the platform, so that one must be mutable.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        return PendingIntent.getBroadcast(context, ((id * 31 + part) * 2 + (if (mutable) 1 else 0)).toInt(), intent, flags)
    }
}

/** The sent / delivered broadcasts of an outgoing message: they move the provider row on. */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id < 0) return
        val repo = TelephonyRepo(context)
        when (intent.action) {
            ACTION_SENT -> {
                if (resultCode == Activity.RESULT_OK) {
                    // A failed part wins: a later successful part must not turn the message back to sent.
                    if (repo.sms(id)?.type != SmsType.FAILED) repo.setType(id, SmsType.SENT)
                } else {
                    repo.setType(id, SmsType.FAILED, SmsStatusCode.FAILED)
                }
            }

            ACTION_DELIVERED -> repo.setStatus(id, deliveryStatus(intent))
        }
    }

    private fun deliveryStatus(intent: Intent): Int {
        val pdu = intent.getByteArrayExtra("pdu")
        val format = intent.getStringExtra("format")
        if (pdu != null) {
            try {
                val status = android.telephony.SmsMessage.createFromPdu(pdu, format).status
                return when {
                    status == 0 -> SmsStatusCode.COMPLETE
                    status >= 64 -> SmsStatusCode.FAILED
                    else -> SmsStatusCode.PENDING
                }
            } catch (e: Exception) {
            }
        }
        return if (resultCode == Activity.RESULT_OK) SmsStatusCode.COMPLETE else SmsStatusCode.FAILED
    }

    companion object {
        const val ACTION_SENT = "org.fossify.phone.sms.SENT"
        const val ACTION_DELIVERED = "org.fossify.phone.sms.DELIVERED"
        const val EXTRA_ID = "message_id"
        const val EXTRA_PART = "part"
        const val EXTRA_PARTS = "parts"
    }
}
