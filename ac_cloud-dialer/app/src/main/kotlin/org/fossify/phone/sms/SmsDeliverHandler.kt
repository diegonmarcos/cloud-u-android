package org.fossify.phone.sms

/** One PDU's worth of an incoming SMS, as the SMS_DELIVER broadcast carries it. */
data class IncomingSms(val address: String, val body: String, val timestampMs: Long, val subscriptionId: Int)

/** Where received messages go: the Telephony provider (only writable while we are the default SMS app). */
interface SmsInbox {
    /** Stores a received message; returns its provider id, or null if the provider refused. */
    fun insertReceived(address: String, body: String, dateMs: Long, subscriptionId: Int, read: Boolean): Long?
}

/** The dialer's block list (the system blocked-number store the call screening already uses). */
fun interface SmsBlockList {
    fun isBlocked(address: String): Boolean
}

fun interface SmsNotifier {
    fun notifyReceived(address: String, body: String, messageId: Long)
}

data class DeliverResult(val stored: Int, val notified: Int, val blocked: Int, val ignored: Boolean)

/**
 * What the SMS_DELIVER receiver does. The platform sends that broadcast ONLY to the default SMS app,
 * and only the default app may write the provider; this handler still checks, because a handler that
 * writes the provider on a guess is the bug that loses messages on a role switch.
 *
 *  - not the default app: nothing is written, nothing is posted ([DeliverResult.ignored]);
 *  - default: the PDUs of one message (same sender) are joined into ONE row, stored unread;
 *  - a blocked sender is stored already read and never notified, so it cannot badge or ring.
 */
class SmsDeliverHandler(
    private val isDefaultSmsApp: () -> Boolean,
    private val inbox: SmsInbox,
    private val blockList: SmsBlockList,
    private val notifier: SmsNotifier,
) {
    fun handle(pdus: List<IncomingSms>): DeliverResult {
        if (!isDefaultSmsApp()) return DeliverResult(0, 0, 0, ignored = true)
        var stored = 0
        var notified = 0
        var blocked = 0
        for (message in merge(pdus)) {
            val isBlocked = blockList.isBlocked(message.address)
            val id = inbox.insertReceived(message.address, message.body, message.timestampMs, message.subscriptionId, read = isBlocked)
                ?: continue
            stored++
            if (isBlocked) {
                blocked++
            } else {
                notifier.notifyReceived(message.address, message.body, id)
                notified++
            }
        }
        return DeliverResult(stored, notified, blocked, ignored = false)
    }

    companion object {
        /** The parts of one multipart message arrive as several PDUs from one sender: one message, bodies in order. */
        fun merge(pdus: List<IncomingSms>): List<IncomingSms> {
            val out = LinkedHashMap<String, IncomingSms>()
            for (p in pdus) {
                val prior = out[p.address]
                out[p.address] = if (prior == null) p else prior.copy(body = prior.body + p.body)
            }
            return out.values.toList()
        }
    }
}
