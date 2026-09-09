package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledSendDao {
    @Insert
    suspend fun insert(entity: ScheduledSendEntity): Long

    @Query("SELECT * FROM scheduled_sends WHERE id = :id")
    suspend fun byId(id: Long): ScheduledSendEntity?

    @Query("SELECT * FROM scheduled_sends ORDER BY sendAtMillis ASC")
    suspend fun all(): List<ScheduledSendEntity>

    @Query("SELECT * FROM scheduled_sends ORDER BY sendAtMillis ASC")
    fun observeAll(): Flow<List<ScheduledSendEntity>>

    @Query("DELETE FROM scheduled_sends WHERE id = :id")
    suspend fun delete(id: Long)
}
