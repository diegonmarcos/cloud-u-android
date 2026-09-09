package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/** `LIKE … ESCAPE '\'`, not a subquery: body rows must go regardless of list rows; caller
 *  escapes `%`, `_`, `\`. */
const val BODY_CACHE_ID_PREFIX_SQL: String =
    "DELETE FROM email_bodies WHERE accountId = :accountId AND id LIKE :idPrefix ESCAPE '\\'"

/** DELETE for [EmailBodyDao.deleteAll], shared with its JVM test. */
const val BODY_CACHE_CLEAR_SQL: String = "DELETE FROM email_bodies"

@Dao
interface EmailBodyDao {

    @Upsert
    suspend fun upsert(body: EmailBodyEntity)

    // Keyed (accountId, id) (#31): an id alone could match another account's cached body.
    @Query("SELECT * FROM email_bodies WHERE accountId = :accountId AND id = :id LIMIT 1")
    suspend fun byId(accountId: String, id: String): EmailBodyEntity?

    @Query("SELECT id FROM email_bodies WHERE accountId = :accountId AND id IN (:ids)")
    suspend fun cachedIds(accountId: String, ids: List<String>): List<String>

    @Query("SELECT COUNT(*) FROM email_bodies WHERE accountId = :accountId")
    suspend fun countForAccount(accountId: String): Int

    /** LRU eviction: keep the [keep] most recently fetched bodies for an account, drop the rest. */
    @Query(
        "DELETE FROM email_bodies WHERE accountId = :accountId AND id NOT IN " +
            "(SELECT id FROM email_bodies WHERE accountId = :accountId ORDER BY fetchedAt DESC LIMIT :keep)",
    )
    suspend fun pruneForAccount(accountId: String, keep: Int)

    @Query("DELETE FROM email_bodies WHERE accountId = :accountId AND id = :id")
    suspend fun deleteById(accountId: String, id: String)

    /** Drops cached bodies whose id starts with [idPrefix] (one IMAP folder). Does not
     *  self-heal: a stale body under a renumbered UID could render under the wrong text (#99). */
    @Query(BODY_CACHE_ID_PREFIX_SQL)
    suspend fun deleteForIdPrefix(accountId: String, idPrefix: String)

    @Query("DELETE FROM email_bodies WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)

    /** Every account id with a cached body here, for the orphan sweep
     *  ([app.sterna.core.data.storage.StorageRepository.purgeOrphanedAccounts]). */
    @Query("SELECT DISTINCT accountId FROM email_bodies")
    suspend fun accountIds(): List<String>

    /** Drops every cached body for every account, once per upgrade. Scope pinned to this table
     *  only — widening it would silently wipe messages/attachments/search too. */
    @Query(BODY_CACHE_CLEAR_SQL)
    suspend fun deleteAll()
}
