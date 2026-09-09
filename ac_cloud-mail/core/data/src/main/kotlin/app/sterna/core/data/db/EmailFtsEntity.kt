package app.sterna.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.PrimaryKey

/**
 * Full-text search index over mail, separate from [EmailEntity] so it can outlive the display
 */
@Fts4(
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics=1"],
    notIndexed = [
        "emailId", "accountId", "mailboxId", "threadId", "preview", "receivedAt",
        "fromName", "fromEmail", "seen", "flagged", "hasAttachment", "sortKey",
    ],
)
@Entity(tableName = "email_fts")
data class EmailFtsEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "rowid") val rowid: Int = 0,
    val emailId: String,
    val accountId: String,
    val mailboxId: String,
    val threadId: String?,
    // Indexed (searchable) columns:
    val subject: String,
    /** Sender name + address, so a search can match either. */
    val sender: String,
    /** Truncated plain-text body (empty until phase 3 populates it). */
    val body: String,
    // notIndexed columns — carried so a hit renders without a join to `emails`:
    val preview: String?,
    val receivedAt: String?,
    val fromName: String?,
    val fromEmail: String?,
    val seen: Boolean,
    val flagged: Boolean,
    val hasAttachment: Boolean,
    val sortKey: Long,
)
