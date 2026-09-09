package app.sterna.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A message queued to be sent at a future time (Schedule send). */
@Entity(tableName = "scheduled_sends")
data class ScheduledSendEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: String,
    val recipients: String, // comma-separated
    val cc: String? = null, // comma-separated
    val bcc: String? = null, // comma-separated
    val subject: String,
    val textBody: String,
    val htmlBody: String?,
    val fromName: String?,
    val fromEmail: String?,
    val inReplyTo: String?, // space-separated message-ids
    val references: String?, // space-separated message-ids
    val sendAtMillis: Long,
    /** Server id of the saved draft this message was edited from (#63); destroyed on send. */
    val draftEmailId: String? = null,
    /** IMAP numbering [draftEmailId] was read under, frozen by the composer (#99); duplicated
     *  from [OutboxEntity.draftUidValidity] since this row waits in its own table until
     *  [app.sterna.send.ScheduledSendWorker] fires. `null` destroys nothing. */
    val draftUidValidity: Long? = null,
    /** Read receipt request (RFC 8098); duplicated from [OutboxEntity] for the same reason as
     *  [draftUidValidity] — missing here at hand-over, the message goes out without asking. */
    val requestReceipt: Boolean = false,
)
