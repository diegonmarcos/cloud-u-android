package app.sterna.core.data.db

import androidx.room.Embedded

/**
 * One collapsed conversation row: latest message of a thread, plus counts for the
 * conversation-grouping paging query.
 */
data class ConversationRow(
    @Embedded val email: EmailEntity,
    /** Messages of the thread in the viewed mailbox(es); the chip shows this. */
    val threadCount: Int,
    /** Cached messages of the thread across the whole account — gates expandability. */
    val threadTotal: Int,
    /** 0 (falsy) when any message in the thread is unread: MIN(seen) over the group. */
    val threadUnread: Int,
)
