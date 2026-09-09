package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SnoozedDao {
    @Upsert
    suspend fun upsert(entity: SnoozedEntity)

    // Keyed (accountId, emailId): a snooze must never match — or clear — another account's
    // same-id message (issue #31).
    @Query("SELECT * FROM snoozed WHERE accountId = :accountId AND emailId = :emailId")
    suspend fun byId(accountId: String, emailId: String): SnoozedEntity?

    @Query("SELECT * FROM snoozed")
    suspend fun all(): List<SnoozedEntity>

    @Query("DELETE FROM snoozed WHERE accountId = :accountId AND emailId = :emailId")
    suspend fun delete(accountId: String, emailId: String)

    // Purge a pruned account's snoozes with the rest of its cache (issue #31).
    @Query("DELETE FROM snoozed WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)

    /**
     * The snoozed messages with the headers the "Snoozed" screen shows. A LEFT JOIN, so a row
     */
    @Query(
        """
        SELECT s.emailId AS emailId, s.accountId AS accountId, s.until AS until,
               e.subject AS subject, e.fromName AS fromName, e.fromEmail AS fromEmail
        FROM snoozed s LEFT JOIN emails e ON e.id = s.emailId AND e.accountId = s.accountId
        ORDER BY s.until ASC
        """,
    )
    fun observeAll(): Flow<List<SnoozedListRow>>
}
