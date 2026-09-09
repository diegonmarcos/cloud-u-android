package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * A person we've emailed (a send recipient). Received senders come from the cached
 */
@Entity(tableName = "recent_contacts")
data class RecentContactEntity(
    @PrimaryKey val email: String,
    val name: String?,
    val lastSeen: Long,
)

@Dao
interface RecentContactDao {
    @Upsert
    suspend fun upsert(contact: RecentContactEntity)

    @Query(
        "SELECT email, name FROM recent_contacts " +
            "WHERE email LIKE '%' || :q || '%' OR name LIKE '%' || :q || '%' " +
            "ORDER BY lastSeen DESC LIMIT :limit",
    )
    suspend fun search(q: String, limit: Int): List<ContactRow>
}

/** A name/email pair returned by contact-suggestion queries. */
data class ContactRow(val email: String, val name: String?)
