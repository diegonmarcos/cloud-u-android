package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/** A search hit projected from [EmailFtsEntity] (no rowid) — enough to render a result row. */
data class FtsHit(
    val emailId: String,
    val accountId: String,
    val mailboxId: String,
    val threadId: String?,
    val subject: String,
    val preview: String?,
    val receivedAt: String?,
    val fromName: String?,
    val fromEmail: String?,
    val seen: Boolean,
    val flagged: Boolean,
    val hasAttachment: Boolean,
)

@Dao
interface EmailFtsDao {

    @Query("DELETE FROM email_fts")
    suspend fun clearAll()

    @Query("DELETE FROM email_fts WHERE accountId = :accountId")
    suspend fun clearAccount(accountId: String)

    /** Every account id with an index row here, for the orphan sweep
     *  ([app.sterna.core.data.storage.StorageRepository.purgeOrphanedAccounts]). */
    @Query("SELECT DISTINCT accountId FROM email_fts")
    suspend fun accountIds(): List<String>

    // Deletes are scoped by accountId: email ids collide across accounts (#31), and an unscoped delete-by-id would drop
    // another account's rows.
    @Query("DELETE FROM email_fts WHERE accountId = :accountId AND emailId IN (:ids)")
    suspend fun deleteByIds(accountId: String, ids: List<String>)

    @Insert
    suspend fun insert(rows: List<EmailFtsEntity>)

    /** Idempotent upsert: replace any existing rows for these ids (FTS has no unique constraint). */
    @Transaction
    suspend fun upsert(rows: List<EmailFtsEntity>) {
        if (rows.isEmpty()) return
        rows.groupBy { it.accountId }.forEach { (accountId, group) ->
            deleteByIds(accountId, group.map { it.emailId })
        }
        insert(rows)
    }

    @Query(
        "DELETE FROM email_fts WHERE EXISTS (SELECT 1 FROM emails " +
            "WHERE emails.id = email_fts.emailId AND emails.accountId = email_fts.accountId)",
    )
    suspend fun deleteCachedRows()

    @Query(
        "INSERT INTO email_fts(emailId, accountId, mailboxId, threadId, subject, sender, body, " +
            "preview, receivedAt, fromName, fromEmail, seen, flagged, hasAttachment, sortKey) " +
            "SELECT id, accountId, mailboxId, threadId, COALESCE(subject, ''), " +
            "TRIM(COALESCE(fromName, '') || ' ' || COALESCE(fromEmail, '')), '', " +
            "preview, receivedAt, fromName, fromEmail, seen, flagged, hasAttachment, sortKey " +
            "FROM emails WHERE NOT EXISTS (SELECT 1 FROM mailboxes " +
            "WHERE mailboxes.id = emails.mailboxId AND mailboxes.accountId = emails.accountId " +
            "AND LOWER(TRIM(COALESCE(mailboxes.role, ''))) IN (:excludedRoles))",
    )
    suspend fun insertFromEmails(excludedRoles: Collection<String>)

    /** Seeds the index from the display cache for an instant coverage floor while the full crawl runs in the
     *  background. Not a cleanup: [deleteCachedRows]
     *  only clears rows whose message is STILL cached — a row left by a gone message survives re-seeding. */
    @Transaction
    suspend fun seedFromEmails(excludedRoles: Collection<String>) {
        deleteCachedRows()
        insertFromEmails(excludedRoles)
    }

    /** Prefix FTS4 search ([match]), newest-first, capped at [limit]. Deleted mail is excluded by two independent
     *  defenses — this hides mislabelled hits
     * ([excludedRoles]) but does NOT catch a moved JMAP message. Empty [accountIds] filters nothing on purpose
     * ([accountScopeCount]). */
    @Query(
        "SELECT emailId, accountId, mailboxId, threadId, subject, preview, receivedAt, " +
            "fromName, fromEmail, seen, flagged, hasAttachment " +
            "FROM email_fts WHERE email_fts MATCH :match " +
            "AND (:accountScopeCount = 0 OR email_fts.accountId IN (:accountIds)) " +
            "AND NOT EXISTS (SELECT 1 FROM mailboxes " +
            "WHERE mailboxes.id = email_fts.mailboxId AND mailboxes.accountId = email_fts.accountId " +
            "AND LOWER(TRIM(COALESCE(mailboxes.role, ''))) IN (:excludedRoles)) " +
            "ORDER BY sortKey DESC LIMIT :limit",
    )
    suspend fun search(
        match: String,
        excludedRoles: Collection<String>,
        accountIds: Collection<String>,
        accountScopeCount: Int,
        limit: Int,
    ): List<FtsHit>
}
