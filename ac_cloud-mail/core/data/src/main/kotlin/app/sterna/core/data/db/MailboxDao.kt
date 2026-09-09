package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.sterna.core.data.mail.mailboxEvictions
import kotlinx.coroutines.flow.Flow

@Dao
interface MailboxDao {

    @Query("SELECT * FROM mailboxes WHERE accountId = :accountId ORDER BY sortOrder, name")
    fun observeAll(accountId: String): Flow<List<MailboxEntity>>

    @Upsert
    suspend fun upsertAll(mailboxes: List<MailboxEntity>)

    @Query("DELETE FROM mailboxes")
    suspend fun deleteAll()

    /** Drop one account's folder rows (sign-out / clear-account-cache). */
    @Query("DELETE FROM mailboxes WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)

    /** Every account id with a folder row here, for the orphan sweep
     *  ([app.sterna.core.data.storage.StorageRepository.purgeOrphanedAccounts]). */
    @Query("SELECT DISTINCT accountId FROM mailboxes")
    suspend fun accountIds(): List<String>

    /** The id (IMAP path) of the account's first mailbox with the given role, if any. */
    @Query("SELECT id FROM mailboxes WHERE accountId = :accountId AND role = :role LIMIT 1")
    suspend fun idForRole(accountId: String, role: String): String?

    /** Each account's cached Sent-role folder, reactively — seen as soon as it first syncs. */
    @Query("SELECT accountId, id FROM mailboxes WHERE accountId IN (:accountIds) AND role = 'sent' ORDER BY accountId, id")
    fun observeSentMailboxes(accountIds: List<String>): Flow<List<AccountMailboxId>>

    /** Every cached (account, folder) → role of the given accounts, reactively — needed for unfolded conversations
     *  spanning other folders (#115). */
    @Query("SELECT accountId, id, role FROM mailboxes WHERE accountId IN (:accountIds) AND role IS NOT NULL")
    fun observeRoles(accountIds: List<String>): Flow<List<AccountMailboxRole>>

    /** Id of the account's folder whose lowercased name is one of [names] — finds an archive folder when the server set
     *  no `archive` role. */
    @Query(
        "SELECT id FROM mailboxes WHERE accountId = :accountId AND LOWER(name) IN (:names) " +
            "ORDER BY (parentId IS NULL) DESC LIMIT 1",
    )
    suspend fun idForAnyName(accountId: String, names: List<String>): String?

    /** Every cached folder of one account, ordered inbox-first (Junk/Trash last) since IMAP can only search one
     *  SELECTed folder at a time. */
    @Query(
        "SELECT id, role FROM mailboxes WHERE accountId = :accountId ORDER BY " +
            "CASE role WHEN 'inbox' THEN 0 WHEN 'archive' THEN 1 WHEN 'sent' THEN 2 " +
            "WHEN 'drafts' THEN 3 WHEN 'junk' THEN 5 WHEN 'trash' THEN 6 ELSE 4 END, id",
    )
    suspend fun searchOrder(accountId: String): List<MailboxIdRole>

    /** The role of an account's mailbox by id (e.g. to tell if a message is in Junk). */
    @Query("SELECT role FROM mailboxes WHERE accountId = :accountId AND id = :id LIMIT 1")
    suspend fun roleForId(accountId: String, id: String): String?

    /** Nudge a folder's cached counters after a local move; the next sync corrects drift. */
    @Query(
        "UPDATE mailboxes SET totalEmails = MAX(0, totalEmails + :totalDelta), " +
            "unreadEmails = MAX(0, unreadEmails + :unreadDelta) WHERE accountId = :accountId AND id = :id",
    )
    suspend fun adjustCounts(accountId: String, id: String, totalDelta: Int, unreadDelta: Int)

    /** Every cached folder id of one account, input to [replaceAll]'s purge; binds ONE variable whatever the list size
     *  (#142). */
    @Query("SELECT id FROM mailboxes WHERE accountId = :accountId")
    suspend fun idsForAccount(accountId: String): List<String>

    /** Drop folders from the cache (drawer) — e.g. while a folder delete awaits its undo window. */
    @Query("DELETE FROM mailboxes WHERE accountId = :accountId AND id IN (:ids)")
    suspend fun deleteByIds(accountId: String, ids: List<String>)

    /** Replaces ONE account's folder rows; purges BY ID in batches, not `id NOT IN (:keepIds)` (#142); upsert BEFORE
     *  purge. */
    @Transaction
    suspend fun replaceAll(accountId: String, mailboxes: List<MailboxEntity>) {
        upsertAll(mailboxes)
        val keepIds = mailboxes.mapTo(HashSet()) { it.id }
        for (batch in mailboxEvictions(idsForAccount(accountId), keepIds)) {
            deleteByIds(accountId, batch)
        }
    }
}

data class AccountMailboxId(
    val accountId: String,
    val id: String,
)

data class AccountMailboxRole(
    val accountId: String,
    val id: String,
    val role: String?,
)

data class MailboxIdRole(
    val id: String,
    val role: String?,
)
