package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Two fields the outbox badge needs, so counting never loads bodies (see
 *  [OutboxLogic.badgeCount]). */
data class OutboxBadgeItem(
    val state: OutboxState,
    val notBeforeMillis: Long,
)

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(entity: OutboxEntity): Long

    @Update
    suspend fun update(entity: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE id = :id")
    suspend fun byId(id: Long): OutboxEntity?

    @Query("SELECT * FROM outbox ORDER BY createdAtMillis ASC")
    suspend fun all(): List<OutboxEntity>

    @Query("SELECT * FROM outbox ORDER BY createdAtMillis ASC")
    fun observeAll(): Flow<List<OutboxEntity>>

    /** Items still in flight (not parked), for a startup re-arm; `SENDING` stays since a dead
     *  WorkManager job needs re-arming. */
    @Query("SELECT * FROM outbox WHERE state IN ('HELD', 'QUEUED', 'SENDING') ORDER BY createdAtMillis ASC")
    suspend fun unfinished(): List<OutboxEntity>

    /** Badge inputs, counted in [OutboxLogic.badgeCount] not SQL — a HELD row's state never
     *  changes (#70). */
    @Query("SELECT state, notBeforeMillis FROM outbox")
    fun observeBadgeItems(): Flow<List<OutboxBadgeItem>>

    /** Flip one row's state — used to take an item out for editing and to give it back (#70). */
    @Query("UPDATE outbox SET state = :state WHERE id = :id")
    suspend fun setState(id: Long, state: OutboxState)

    /** Parks exhausted mid-edit rows as FAILED (#70); runs BEFORE [parkAllEditingAsInterrupted]. */
    @Query("UPDATE outbox SET state = 'FAILED' WHERE state = 'EDITING' AND attemptCount >= :maxAttempts")
    suspend fun revertEditingExhaustedToFailed(maxAttempts: Int)

    /** Parks every row left mid-edit (#70) as FAILED — must not restore to QUEUED (re-sends it). */
    @Query("UPDATE outbox SET state = 'FAILED', attemptCount = :maxAttempts, lastError = :lastError WHERE state = 'EDITING'")
    suspend fun parkAllEditingAsInterrupted(maxAttempts: Int, lastError: String)

    /** Parks a composer-closed row with a LOST body: FAILED, one write, mirroring
     *  [MailRepository.releaseOutboxEdit]'s guard. */
    @Query("UPDATE outbox SET state = 'FAILED', attemptCount = :maxAttempts, lastError = :lastError WHERE id = :id AND state = 'EDITING'")
    suspend fun parkEditingAsInterrupted(id: Long, maxAttempts: Int, lastError: String)

    @Query("UPDATE outbox SET state = :state, attemptCount = :attemptCount, lastError = :lastError, lastAttemptMillis = :lastAttemptMillis WHERE id = :id")
    suspend fun updateState(
        id: Long,
        state: OutboxState,
        attemptCount: Int,
        lastError: String?,
        lastAttemptMillis: Long?,
    )

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: Long)
}
