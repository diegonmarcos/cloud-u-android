package org.fossify.phone.sms

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony

/**
 * The Telephony provider, read and written. Reads need READ_SMS; every write needs this app to be
 * the default SMS app (the platform enforces it), so each write catches SecurityException and
 * reports failure instead of crashing a non-default install.
 */
class TelephonyRepo(context: Context) : SmsInbox {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver

    private val smsUri: Uri = Telephony.Sms.CONTENT_URI
    private val mmsUri: Uri = Telephony.Mms.CONTENT_URI
    private val simpleThreads: Uri = Uri.parse("content://mms-sms/conversations?simple=true")
    private val canonical: Uri = Uri.parse("content://mms-sms/canonical-addresses")

    private fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))
    private fun Cursor.text(name: String): String = getString(getColumnIndexOrThrow(name)) ?: ""

    // ---- conversations ---------------------------------------------------------------------

    fun threads(): List<ThreadRow> {
        val out = ArrayList<ThreadRow>()
        try {
            resolver.query(simpleThreads, arrayOf("_id", "date", "message_count", "recipient_ids", "snippet", "error"), null, null, "date DESC")?.use { c ->
                while (c.moveToNext()) {
                    out.add(ThreadRow(c.long("_id"), c.long("date"), c.int("message_count"), c.text("recipient_ids"), c.text("snippet"), c.int("error") != 0))
                }
            }
        } catch (e: Exception) {
            // no READ_SMS: the list is empty and the screen asks for the grant
        }
        return out
    }

    fun canonicalAddresses(): Map<Long, String> {
        val out = HashMap<Long, String>()
        try {
            resolver.query(canonical, arrayOf("_id", "address"), null, null, null)?.use { c ->
                while (c.moveToNext()) out[c.long("_id")] = c.text("address")
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun unreadByThread(): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        try {
            resolver.query(smsUri, arrayOf("thread_id"), "read = 0 AND type = ${SmsType.INBOX}", null, null)?.use { c ->
                while (c.moveToNext()) out[c.long("thread_id")] = (out[c.long("thread_id")] ?: 0) + 1
            }
            resolver.query(mmsUri, arrayOf("thread_id"), "read = 0 AND msg_box = 1", null, null)?.use { c ->
                while (c.moveToNext()) out[c.long("thread_id")] = (out[c.long("thread_id")] ?: 0) + 1
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun drafts(): Map<Long, String> {
        val out = HashMap<Long, String>()
        try {
            resolver.query(smsUri, arrayOf("thread_id", "body"), "type = ${SmsType.DRAFT}", null, "date DESC")?.use { c ->
                while (c.moveToNext()) out.putIfAbsent(c.long("thread_id"), c.text("body"))
            }
        } catch (e: Exception) {
        }
        return out
    }

    /** Thread ids with a message body containing [query] (the list's search reaches inside conversations). */
    fun searchThreadIds(query: String): Set<Long> {
        val out = HashSet<Long>()
        if (query.length < 2) return out
        try {
            resolver.query(smsUri, arrayOf("thread_id"), "body LIKE ?", arrayOf("%$query%"), null)?.use { c ->
                while (c.moveToNext()) out.add(c.long("thread_id"))
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun threadIdFor(address: String): Long =
        try { Telephony.Threads.getOrCreateThreadId(context, address) } catch (e: Exception) { 0L }

    fun threadIdFor(addresses: Set<String>): Long =
        try { Telephony.Threads.getOrCreateThreadId(context, addresses) } catch (e: Exception) { 0L }

    // ---- messages of one conversation -----------------------------------------------------

    fun messages(threadId: Long): List<SmsMessage> {
        val out = ArrayList<SmsMessage>()
        try {
            resolver.query(smsUri, arrayOf("_id", "thread_id", "address", "body", "date", "type", "read", "status", "sub_id"), "thread_id = ?", arrayOf(threadId.toString()), "date ASC")?.use { c ->
                while (c.moveToNext()) out.add(smsRow(c))
            }
            resolver.query(mmsUri, arrayOf("_id", "thread_id", "date", "msg_box", "read", "sub_id"), "thread_id = ?", arrayOf(threadId.toString()), "date ASC")?.use { c ->
                while (c.moveToNext()) {
                    val id = c.long("_id")
                    val box = c.int("msg_box")
                    val (text, attachments) = mmsParts(id)
                    out.add(
                        SmsMessage(
                            id = id, threadId = c.long("thread_id"), address = mmsAddress(id, from = box == 1),
                            body = text, date = c.long("date") * 1000L, type = if (box == 1) SmsType.INBOX else box.coerceIn(SmsType.SENT, SmsType.QUEUED),
                            read = c.int("read") != 0, subscriptionId = c.int("sub_id"), isMms = true, attachments = attachments,
                        ),
                    )
                }
            }
        } catch (e: Exception) {
        }
        return out.sortedWith(compareBy({ it.date }, { it.id }))
    }

    private fun smsRow(c: Cursor) = SmsMessage(
        id = c.long("_id"), threadId = c.long("thread_id"), address = c.text("address"), body = c.text("body"),
        date = c.long("date"), type = c.int("type"), read = c.int("read") != 0, status = c.int("status"), subscriptionId = c.int("sub_id"),
    )

    fun sms(id: Long): SmsMessage? = try {
        resolver.query(ContentUris.withAppendedId(smsUri, id), arrayOf("_id", "thread_id", "address", "body", "date", "type", "read", "status", "sub_id"), null, null, null)?.use { c ->
            if (c.moveToFirst()) smsRow(c) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun mmsAddress(mmsId: Long, from: Boolean): String {
        val type = if (from) 137 else 151
        try {
            resolver.query(Uri.parse("content://mms/$mmsId/addr"), arrayOf("address"), "type = $type", null, null)?.use { c ->
                while (c.moveToNext()) {
                    val a = c.getString(0)
                    if (!a.isNullOrBlank() && a != "insert-address-token") return a
                }
            }
        } catch (e: Exception) {
        }
        return ""
    }

    private fun mmsParts(mmsId: Long): Pair<String, List<MmsAttachment>> {
        val text = StringBuilder()
        val attachments = ArrayList<MmsAttachment>()
        try {
            resolver.query(Uri.parse("content://mms/part"), arrayOf("_id", "ct", "text"), "mid = ?", arrayOf(mmsId.toString()), null)?.use { c ->
                while (c.moveToNext()) {
                    val ct = c.text("ct")
                    when {
                        ct == "text/plain" -> text.append(c.text("text"))
                        ct == "application/smil" -> {}
                        else -> attachments.add(MmsAttachment(c.long("_id"), ct, "content://mms/part/${c.long("_id")}"))
                    }
                }
            }
        } catch (e: Exception) {
        }
        return text.toString() to attachments
    }

    // ---- writes (default SMS app only) -----------------------------------------------------

    override fun insertReceived(address: String, body: String, dateMs: Long, subscriptionId: Int, read: Boolean): Long? =
        insertSms(address, body, dateMs, SmsType.INBOX, subscriptionId, read)

    /** Stores an outgoing message in the outbox; the sent/delivery receivers move it on. */
    fun insertOutgoing(address: String, body: String, subscriptionId: Int, threadId: Long = 0L): Long? =
        insertSms(address, body, System.currentTimeMillis(), SmsType.OUTBOX, subscriptionId, read = true, threadId = threadId)

    private fun insertSms(address: String, body: String, dateMs: Long, type: Int, subscriptionId: Int, read: Boolean, threadId: Long = 0L): Long? = try {
        val values = ContentValues().apply {
            put("address", address)
            put("body", body)
            put("date", dateMs)
            put("type", type)
            put("read", if (read) 1 else 0)
            put("seen", if (read) 1 else 0)
            put("sub_id", subscriptionId)
            put("thread_id", if (threadId > 0) threadId else threadIdFor(address))
        }
        resolver.insert(smsUri, values)?.let { ContentUris.parseId(it) }
    } catch (e: Exception) {
        null
    }

    fun setType(id: Long, type: Int, status: Int? = null): Boolean = try {
        val values = ContentValues().apply {
            put("type", type)
            if (status != null) put("status", status)
        }
        resolver.update(ContentUris.withAppendedId(smsUri, id), values, null, null) > 0
    } catch (e: Exception) {
        false
    }

    fun setStatus(id: Long, status: Int): Boolean = try {
        resolver.update(ContentUris.withAppendedId(smsUri, id), ContentValues().apply { put("status", status) }, null, null) > 0
    } catch (e: Exception) {
        false
    }

    fun markThreadRead(threadId: Long) {
        val values = ContentValues().apply { put("read", 1); put("seen", 1) }
        try {
            resolver.update(smsUri, values, "thread_id = ? AND read = 0", arrayOf(threadId.toString()))
            resolver.update(mmsUri, values, "thread_id = ? AND read = 0", arrayOf(threadId.toString()))
        } catch (e: Exception) {
        }
    }

    fun markRead(id: Long) {
        try {
            resolver.update(ContentUris.withAppendedId(smsUri, id), ContentValues().apply { put("read", 1); put("seen", 1) }, null, null)
        } catch (e: Exception) {
        }
    }

    fun deleteThread(threadId: Long): Boolean = try {
        resolver.delete(Uri.parse("content://mms-sms/conversations/$threadId"), null, null) >= 0
    } catch (e: Exception) {
        false
    }

    fun deleteMessage(message: SmsMessage): Boolean = try {
        val base = if (message.isMms) mmsUri else smsUri
        resolver.delete(ContentUris.withAppendedId(base, message.id), null, null) > 0
    } catch (e: Exception) {
        false
    }

    // ---- drafts ----------------------------------------------------------------------------

    fun draftFor(threadId: Long): String? = try {
        resolver.query(smsUri, arrayOf("body"), "thread_id = ? AND type = ${SmsType.DRAFT}", arrayOf(threadId.toString()), "date DESC")?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /** One draft per thread: the old one is replaced; an empty text clears it. */
    fun saveDraft(threadId: Long, address: String, body: String) {
        try {
            resolver.delete(smsUri, "thread_id = ? AND type = ${SmsType.DRAFT}", arrayOf(threadId.toString()))
            if (body.isNotBlank()) insertSms(address, body, System.currentTimeMillis(), SmsType.DRAFT, -1, read = true, threadId = threadId)
        } catch (e: Exception) {
        }
    }
}
