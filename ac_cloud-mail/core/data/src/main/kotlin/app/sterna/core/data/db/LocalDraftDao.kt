package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** The drafts written on this phone and not yet on the server; every statement is scoped by `accountId` (#31) except
 *  [accountsWithDrafts] (ids only). */
@Dao
interface LocalDraftDao {
    /** Writes a draft, replacing the row with the same `(accountId, id)` — autosave is idempotent. */
    @Upsert
    suspend fun upsert(draft: LocalDraftEntity)

    @Query("SELECT * FROM local_drafts WHERE accountId = :accountId AND id = :id")
    suspend fun byId(accountId: String, id: String): LocalDraftEntity?

    /** Row this account keeps for the SERVER draft [replacesEmailId]; `LIMIT 1` newest-first, not a unique index. */
    @Query(
        "SELECT * FROM local_drafts WHERE accountId = :accountId AND replacesEmailId = :replacesEmailId " +
            "ORDER BY updatedAtMillis DESC LIMIT 1",
    )
    suspend fun forServerDraft(accountId: String, replacesEmailId: String): LocalDraftEntity?

    /** Every local draft of one account, newest edit first — what the Drafts list merges in. */
    @Query("SELECT * FROM local_drafts WHERE accountId = :accountId ORDER BY updatedAtMillis DESC")
    fun observeForAccount(accountId: String): Flow<List<LocalDraftEntity>>

    /** Rows to pick up at [nowMillis]: not open, backoff expired; `STAGING` excluded (rescued by
     *  [revertStagedToPendingLossy]). */
    @Query(
        "SELECT * FROM local_drafts WHERE accountId = :accountId AND state IN ('PENDING', 'UPLOADING') " +
            "AND notBeforeMillis <= :nowMillis ORDER BY notBeforeMillis ASC, createdAtMillis ASC",
    )
    suspend fun pending(accountId: String, nowMillis: Long): List<LocalDraftEntity>

    /** Accounts with at least one draft not on the server, for a startup re-arm — ids only; EVERY row counts. */
    @Query("SELECT DISTINCT accountId FROM local_drafts")
    suspend fun accountsWithDrafts(): List<String>

    /** Take a row out for editing and give it back — see [LocalDraftState.EDITING]. */
    @Query("UPDATE local_drafts SET state = :state WHERE accountId = :accountId AND id = :id")
    suspend fun setState(accountId: String, id: String, state: LocalDraftState)

    /** Brings back one account's rows left mid-upload (#70); [LocalDraftState.EDITING] stays untouched. */
    @Query("UPDATE local_drafts SET state = 'PENDING' WHERE accountId = :accountId AND state = 'UPLOADING'")
    suspend fun revertUploadingToPending(accountId: String)

    /** Brings process-killed `STAGING` rows back to `PENDING`, flagged [LocalDraftEntity.bodyIsLossy]. */
    @Query(
        "UPDATE local_drafts SET state = 'PENDING', bodyIsLossy = 1 " +
            "WHERE accountId = :accountId AND state = 'STAGING'",
    )
    suspend fun revertStagedToPendingLossy(accountId: String)

    /** Records a failed upload attempt; [LocalDraftEntity.attemptCount] only feeds the backoff, never caps. */
    @Query(
        "UPDATE local_drafts SET state = :state, attemptCount = :attemptCount, lastError = :lastError, " +
            "lastAttemptMillis = :lastAttemptMillis, notBeforeMillis = :notBeforeMillis " +
            "WHERE accountId = :accountId AND id = :id",
    )
    suspend fun recordAttempt(
        accountId: String,
        id: String,
        state: LocalDraftState,
        attemptCount: Int,
        lastError: String?,
        lastAttemptMillis: Long?,
        notBeforeMillis: Long,
    )

    /** Consume one row — the draft has reached the server (or the user threw it away). */
    @Query("DELETE FROM local_drafts WHERE accountId = :accountId AND id = :id")
    suspend fun deleteById(accountId: String, id: String)

    /** Everything one account holds, for a removed account (#121). Scoped, like the rest. */
    @Query("DELETE FROM local_drafts WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)
}
