package org.fossify.phone.sms

/**
 * Cloud Dialer SMS: the pure model. Nothing in this file touches Android, so the thread grouping,
 * the status mapping and the conversation ordering run as plain JVM unit tests.
 *
 * Telephony.Sms message types and statuses are spelled out as constants (they are the provider's
 * documented values) so this file needs no android.provider import.
 */
object SmsType {
    const val INBOX = 1
    const val SENT = 2
    const val DRAFT = 3
    const val OUTBOX = 4
    const val FAILED = 5
    const val QUEUED = 6
}

object SmsStatusCode {
    const val NONE = -1
    const val COMPLETE = 0
    const val PENDING = 32
    const val FAILED = 64
}

/** One image (or other) part of an MMS: where it can be read from, and what it is. */
data class MmsAttachment(val partId: Long, val mimeType: String, val uri: String) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
}

/** One message of a conversation: an SMS row, or an MMS flattened to its text plus attachments. */
data class SmsMessage(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    /** Epoch milliseconds (an MMS row's seconds are converted by the repository). */
    val date: Long,
    val type: Int,
    val read: Boolean,
    val status: Int = SmsStatusCode.NONE,
    val subscriptionId: Int = -1,
    val isMms: Boolean = false,
    val attachments: List<MmsAttachment> = emptyList(),
)

enum class DeliveryState { Received, Draft, Sending, Sent, Delivered, Failed }

val SmsMessage.isOutgoing: Boolean get() = type != SmsType.INBOX

/** What a bubble's status line says. A delivery report (status COMPLETE) upgrades Sent to Delivered. */
fun SmsMessage.deliveryState(): DeliveryState = when (type) {
    SmsType.INBOX -> DeliveryState.Received
    SmsType.DRAFT -> DeliveryState.Draft
    SmsType.OUTBOX, SmsType.QUEUED -> DeliveryState.Sending
    SmsType.FAILED -> DeliveryState.Failed
    else -> when (status) {
        SmsStatusCode.COMPLETE -> DeliveryState.Delivered
        SmsStatusCode.FAILED -> DeliveryState.Failed
        else -> DeliveryState.Sent
    }
}

/** A failed message can be sent again; nothing else can. */
val SmsMessage.canRetry: Boolean get() = deliveryState() == DeliveryState.Failed

/** A row of Telephony.Threads (the "simple" conversations view). */
data class ThreadRow(
    val id: Long,
    val date: Long,
    val messageCount: Int,
    /** Space separated canonical-address ids. */
    val recipientIds: String,
    val snippet: String,
    val hasError: Boolean = false,
)

data class Conversation(
    val threadId: Long,
    val addresses: List<String>,
    val title: String,
    val snippet: String,
    val date: Long,
    val unreadCount: Int,
    val messageCount: Int,
    val pinned: Boolean,
    val archived: Boolean,
    val hasFailed: Boolean,
    val draft: String? = null,
) {
    val isGroup: Boolean get() = addresses.size > 1
    val isUnread: Boolean get() = unreadCount > 0
}

object Conversations {
    /**
     * The conversation list from the provider's thread rows: addresses resolved from the canonical
     * address table, names from contacts, unread counts and drafts per thread, then ordered pinned
     * first and newest first. A thread whose recipients cannot be resolved is dropped (the provider
     * keeps empty husks of deleted conversations).
     */
    fun build(
        rows: List<ThreadRow>,
        addressOf: (Long) -> String?,
        nameOf: (String) -> String?,
        unread: Map<Long, Int> = emptyMap(),
        pinned: Set<Long> = emptySet(),
        archived: Set<Long> = emptySet(),
        drafts: Map<Long, String> = emptyMap(),
    ): List<Conversation> {
        val built = rows.mapNotNull { row ->
            val addresses = row.recipientIds.split(' ', ',').filter { it.isNotBlank() }
                .mapNotNull { id -> id.toLongOrNull()?.let(addressOf) }
            if (addresses.isEmpty() || (row.messageCount <= 0 && drafts[row.id] == null)) return@mapNotNull null
            val names = addresses.map { nameOf(it) ?: it }
            val draft = drafts[row.id]
            Conversation(
                threadId = row.id,
                addresses = addresses,
                title = names.joinToString(", "),
                snippet = draft ?: row.snippet,
                date = row.date,
                unreadCount = unread[row.id] ?: 0,
                messageCount = row.messageCount,
                pinned = row.id in pinned,
                archived = row.id in archived,
                hasFailed = row.hasError,
                draft = draft,
            )
        }
        return order(built)
    }

    /** Pinned first, then newest first; ties broken by thread id so the order is stable. */
    fun order(list: List<Conversation>): List<Conversation> =
        list.sortedWith(compareByDescending<Conversation> { it.pinned }.thenByDescending { it.date }.thenByDescending { it.threadId })

    /** What the list shows: the archive or the inbox, narrowed by the search text (title, number, snippet). */
    fun visible(all: List<Conversation>, showArchived: Boolean, query: String): List<Conversation> {
        val q = query.trim()
        return all.filter { it.archived == showArchived }.filter {
            q.isEmpty() || it.title.contains(q, ignoreCase = true) ||
                it.addresses.any { a -> a.contains(q, ignoreCase = true) } ||
                it.snippet.contains(q, ignoreCase = true)
        }
    }
}

object Threads {
    /** Messages grouped by their thread, each thread oldest first (the order a conversation is read in). */
    fun group(messages: List<SmsMessage>): Map<Long, List<SmsMessage>> =
        messages.groupBy { it.threadId }.mapValues { (_, list) -> list.sortedWith(compareBy({ it.date }, { it.id })) }

    /** The same grouping, as conversations: used by message search, where hits come back as loose rows. */
    fun conversationsOf(
        messages: List<SmsMessage>,
        nameOf: (String) -> String?,
        pinned: Set<Long> = emptySet(),
        archived: Set<Long> = emptySet(),
    ): List<Conversation> = Conversations.order(group(messages).map { (threadId, list) ->
        val last = list.last()
        val addresses = list.map { it.address }.filter { it.isNotBlank() }.distinct()
        Conversation(
            threadId = threadId,
            addresses = addresses,
            title = addresses.joinToString(", ") { nameOf(it) ?: it },
            snippet = last.body,
            date = last.date,
            unreadCount = list.count { !it.read && it.type == SmsType.INBOX },
            messageCount = list.size,
            pinned = threadId in pinned,
            archived = threadId in archived,
            hasFailed = list.any { it.deliveryState() == DeliveryState.Failed },
        )
    })

    /** Day boundaries of an oldest-first message list: the index of each message that starts a new day. */
    fun dayStarts(messages: List<SmsMessage>, dayOf: (Long) -> Long): List<Int> =
        messages.indices.filter { i -> i == 0 || dayOf(messages[i].date) != dayOf(messages[i - 1].date) }
}
