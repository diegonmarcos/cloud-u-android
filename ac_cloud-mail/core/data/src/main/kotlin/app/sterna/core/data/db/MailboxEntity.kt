package app.sterna.core.data.db

import androidx.room.Entity

/** Cached mailbox (folder) for the navigation drawer. Keyed (accountId, id): servers number
 *  mailboxes per-account, so two accounts can share a bare id without colliding. */
@Entity(tableName = "mailboxes", primaryKeys = ["accountId", "id"])
data class MailboxEntity(
    /** Local StoredAccount id owning this folder (NOT the JMAP accountId). */
    val accountId: String,
    val id: String,
    val name: String,
    val role: String?,
    /** Parent mailbox id for nested folders (JMAP); null = top-level. */
    val parentId: String? = null,
    val sortOrder: Int,
    val totalEmails: Int,
    val unreadEmails: Int,
    /** Whether the SERVER reports this folder subscribed (IMAP `LSUB`, JMAP `isSubscribed`,
     *  #174). Defaults `true`: unknown must never hide a folder ([MIGRATION_25_26] backfills
     *  pre-v26 rows the same way). */
    val isSubscribed: Boolean = true,
)
