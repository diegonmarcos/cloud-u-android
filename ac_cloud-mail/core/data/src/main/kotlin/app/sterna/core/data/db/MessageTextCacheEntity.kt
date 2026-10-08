package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** What a [MessageTextCacheEntity] row holds. */
object MessageTextKind {
    /** The reader's HTML fragment with its text translated in place, for one target language. */
    const val TRANSLATION = "translation"

    /** The summary of the message, written in one language. */
    const val SUMMARY = "summary"

    /** The language the message was detected to be in (payload; "" = could not tell), once per message. */
    const val SOURCE = "source"
}

/**
 * One AI result about one message, kept so that reopening the message shows it at once instead of
 * calling the engine again: the translated fragment ([MessageTextKind.TRANSLATION]) or the summary
 * ([MessageTextKind.SUMMARY]), per message AND per target language.
 *
 * [sourceHash] is the hash of the text the result was made FROM (the reader's fragment, or the
 * summary's source text). A row whose hash no longer matches what the reader would translate now -
 * the sanitiser changed, the message was re-fetched with a different body, the reading mode flipped -
 * is a miss, never a stale answer.
 *
 * A cache and nothing else: it is device state (never migrates), it is bounded by [pruneOlderThan],
 * and losing it costs one engine call per message.
 */
@Entity(tableName = "message_text_cache", primaryKeys = ["accountId", "emailId", "kind", "lang"])
data class MessageTextCacheEntity(
    val accountId: String,
    val emailId: String,
    val kind: String,
    val lang: String,
    val sourceHash: String,
    val payload: String,
    val createdAt: Long,
)

@Dao
interface MessageTextCacheDao {
    @Query(
        "SELECT * FROM message_text_cache WHERE accountId = :accountId AND emailId = :emailId " +
            "AND kind = :kind AND lang = :lang",
    )
    suspend fun get(accountId: String, emailId: String, kind: String, lang: String): MessageTextCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: MessageTextCacheEntity)

    /** Whether the message has ANY translation kept, in any language (enables Show Translated). */
    @Query(
        "SELECT COUNT(*) FROM message_text_cache WHERE accountId = :accountId AND emailId = :emailId " +
            "AND kind = 'translation'",
    )
    suspend fun translationCount(accountId: String, emailId: String): Int

    @Query("DELETE FROM message_text_cache WHERE createdAt < :before")
    suspend fun pruneOlderThan(before: Long): Int

    @Query("DELETE FROM message_text_cache WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)

    @Query("DELETE FROM message_text_cache")
    suspend fun deleteAll()
}
