package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** SQL behind [PurgeSnapshotDao.unlistEmails], kept as a constant so `TrashPurgeSqlTest` runs the exact same statement.
 *  */
const val PURGE_SNAPSHOT_UNLIST_SQL: String =
    "DELETE FROM purge_snapshot WHERE accountId = :accountId AND emailId IN (:emailIds)"

/** The frozen destroy list of a confirmed "Empty trash"; every read is scoped by `purgeId` AND `accountId` (#31, #99).
 *  */
@Dao
interface PurgeSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<PurgeSnapshotEntity>)

    /** The next wave of ids to destroy; deleted as consumed, so repeating drains the snapshot. */
    @Query(
        "SELECT emailId FROM purge_snapshot WHERE purgeId = :purgeId AND accountId = :accountId " +
            "ORDER BY rowid LIMIT :limit",
    )
    suspend fun wave(purgeId: String, accountId: String, limit: Int): List<String>

    @Query("SELECT COUNT(*) FROM purge_snapshot WHERE purgeId = :purgeId AND accountId = :accountId")
    suspend fun count(purgeId: String, accountId: String): Int

    /** What the snapshot says: which folder it froze and under which UIDVALIDITY; null means no snapshot (destroy
     *  nothing). */
    @Query(
        "SELECT mailboxId, uidValidity FROM purge_snapshot " +
            "WHERE purgeId = :purgeId AND accountId = :accountId LIMIT 1",
    )
    suspend fun head(purgeId: String, accountId: String): PurgeSnapshotHead?

    @Query(
        "DELETE FROM purge_snapshot WHERE purgeId = :purgeId AND accountId = :accountId " +
            "AND emailId IN (:emailIds)",
    )
    suspend fun deleteIds(purgeId: String, accountId: String, emailIds: List<String>)

    /** A message left its folder mid-purge: withdraw it from EVERY destroy list of that account (#99), not scoped by
     *  `purgeId`. */
    @Query(PURGE_SNAPSHOT_UNLIST_SQL)
    suspend fun unlistEmails(accountId: String, emailIds: List<String>)

    /** Drop a whole snapshot: the purge finished, gave up, or was undone. */
    @Query("DELETE FROM purge_snapshot WHERE purgeId = :purgeId")
    suspend fun deleteSnapshot(purgeId: String)

    /** Undo, by folder: the confirmation is withdrawn, so no id from that Trash may be destroyed. */
    @Query("DELETE FROM purge_snapshot WHERE accountId = :accountId AND mailboxId = :mailboxId")
    suspend fun deleteForMailbox(accountId: String, mailboxId: String)

    /** Sign-out / account pruning: a removed account's destroy list goes with it. */
    @Query("DELETE FROM purge_snapshot WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)

    /** Sweep abandoned snapshots (process killed between the snapshot and the schedule): nothing may accumulate here
     *  indefinitely. */
    @Query("DELETE FROM purge_snapshot WHERE createdAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}

data class PurgeSnapshotHead(
    val mailboxId: String,
    val uidValidity: Long?,
)
