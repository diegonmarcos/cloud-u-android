package app.sterna.core.data.mail

import app.sterna.core.data.db.PurgeSnapshotEntity
import kotlinx.coroutines.CancellationException

/** A message reaching the Trash after the confirmation is, by construction, not in the list (#99). */
object TrashPurge {

    /** Ids one snapshot may hold. Past it the surplus survives and emptying again clears it;
     *  re-reading the folder to catch up is exactly the bug. */
    const val SNAPSHOT_MAX = 10_000

    /** Ids per destroy request (RFC 8620 `maxObjectsInSet` floor), i.e. one wave. */
    const val DESTROY_WAVE = 500

    const val MAX_WAVES = SNAPSHOT_MAX / DESTROY_WAVE + 1

    /** How long an abandoned snapshot may sit before the sweep collects it. */
    const val SNAPSHOT_TTL_MS = 24L * 60 * 60 * 1000

    /**
     * Each row carries its account and folder, so it can never be resolved against another (#31).
     * [uidValidity] is part of the order: the ids mean nothing under another number.
     */
    fun snapshotRows(
        purgeId: String,
        accountId: String,
        mailboxId: String,
        ids: List<String>,
        now: Long,
        cap: Int = SNAPSHOT_MAX,
        uidValidity: Long? = null,
    ): List<PurgeSnapshotEntity> =
        ids.asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .take(cap.coerceAtLeast(0))
            .map { PurgeSnapshotEntity(purgeId, accountId, mailboxId, it, now, uidValidity) }
            .toList()

    /**
     * The WHOLE folder as the server enumerates it, not merely the synced window; [cap] takes the
     */
    suspend fun imapSnapshotIds(
        accountId: String,
        mailboxId: String,
        serverUids: suspend () -> List<Long>,
        cached: suspend () -> List<String>,
        cap: Int = SNAPSHOT_MAX,
    ): List<String> =
        try {
            serverUids()
                .take(cap.coerceAtLeast(0))
                .map { ImapMailService.emailId(accountId, mailboxId, it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unreachable: Throwable) {
            cached()
        }

    /**
     * A JMAP id does not change when the message changes folder, so one rescued out of the Trash by
     */
    fun destroyableIds(
        requested: List<String>,
        expectedMailboxId: String?,
        serverMailboxIds: Map<String, Set<String>>,
    ): List<String> {
        if (expectedMailboxId.isNullOrBlank()) return emptyList()
        val expected = setOf(expectedMailboxId)
        return requested.filter { serverMailboxIds[it] == expected }
    }

    /**
     * What the server reported, or the numbering the CACHED ids belong to. Without that fallback
     * every offline "Empty trash" would produce an unverifiable order. Null destroys nothing.
     */
    fun snapshotUidValidity(observed: Long?, recorded: Long?): Long? =
        observed?.takeIf { it > 0L } ?: recorded?.takeIf { it > 0L }
}
