package app.sterna.core.data.db

import androidx.room.Entity

/**
 * Cached full body of an opened/prefetched message; separate from [EmailEntity] since bodies
 */
@Entity(
    tableName = "email_bodies",
    primaryKeys = ["accountId", "id"],
)
data class EmailBodyEntity(
    val id: String,
    val accountId: String,
    val bodyJson: String,
    val inlineImagesJson: String,
    /** Epoch millis when fetched, for LRU eviction. */
    val fetchedAt: Long,
)
