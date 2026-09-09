package app.sterna.core.data.db

import androidx.room.Entity

/** Lifecycle of a draft written on the phone and not yet on the server. No terminal state — [attemptCount] only spaces
 *  retries. */
enum class LocalDraftState {
    /** Attachment bytes still being copied; excluded from [LocalDraftDao.pending] (#70); re-armed lossy by
     *  [LocalDraftDao.revertStagedToPendingLossy]. */
    STAGING,

    /** Saved locally, waiting to be uploaded to the server's Drafts folder. */
    PENDING,

    /** An upload attempt is in flight. */
    UPLOADING,

    /** Open in the composer (#70): held from the upload worker while edited; only the composer clears it. */
    EDITING,
}

/** A draft the user wrote, held until it reaches the server; its own table so the cache's silent prunes can't delete it
 *  (#95). Keyed `(accountId, id)` (#31). */
@Entity(tableName = "local_drafts", primaryKeys = ["accountId", "id"])
data class LocalDraftEntity(
    val accountId: String,
    /** Local id, minted by [newLocalDraftId]; must not collide with a cached server id — see [LOCAL_DRAFT_ID_PREFIX].
     *  */
    val id: String,
    /** RFC 5322 `Message-ID`, minted ONCE and frozen so an IMAP upload can dedupe (`draftIsAlreadyThere`). */
    val messageId: String,
    val toAddresses: String,
    val cc: String? = null,
    val bcc: String? = null,
    val subject: String,
    /** NOT NULL: the text the user typed has no "unknown" state — an empty draft is "", not null. */
    val textBody: String,
    val htmlBody: String? = null,
    val fromName: String? = null,
    val fromEmail: String? = null,
    val inReplyTo: String? = null,
    val references: String? = null,
    val attachmentsJson: String = "[]",
    /** Server draft this row replaces once uploaded (#63); null for a from-scratch draft. */
    val replacesEmailId: String? = null,
    /** UIDVALIDITY [replacesEmailId]'s folder had when opened (IMAP only): guards against expunging a stranger's mail
     *  if renumbered before upload. */
    val replacesUidValidity: Long? = null,
    /** Whether the body is a lossy reading of the replaced message; lets the upload refuse to destroy an original it
     *  can't reproduce. */
    val bodyIsLossy: Boolean = false,
    /** Whether the message asks for a read receipt when it is eventually sent (RFC 8098). */
    val requestReceipt: Boolean = false,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    /** Don't attempt an upload before this instant — the backoff, computed from [attemptCount]. */
    val notBeforeMillis: Long,
    /** Failed upload attempts. A COUNTER, not a cap — see [LocalDraftState]; nothing here gives up. */
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val lastAttemptMillis: Long? = null,
    val state: LocalDraftState = LocalDraftState.PENDING,
)

/** Prefix avoiding collision with `imap:` ids and JMAP ids (`A-Za-z0-9_-` only, RFC 8620 §1.2). */
const val LOCAL_DRAFT_ID_PREFIX: String = "local-draft:"

/** A fresh local draft id — [LOCAL_DRAFT_ID_PREFIX] plus a UUID, unique without asking anyone. */
fun newLocalDraftId(): String = LOCAL_DRAFT_ID_PREFIX + java.util.UUID.randomUUID()
