package app.sterna.mail

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import app.sterna.container
import java.util.concurrent.TimeUnit

/** Executes a held-back permanent destroy (in-Trash delete, or an Empty-trash purge) after its undo
 *  window. WorkManager persistence means it survives the ViewModel and the process: with the
 *  hold-back in viewModelScope, killing the app within the window silently dropped a destroy the
 *  user had confirmed. A new hold-back commits the pending one at once via [flushNow]. */
class MessageDestroyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val accountId = inputData.getString(KEY_ACCOUNT_ID) ?: return Result.success()
        val container = (applicationContext as Application).container
        val credentials = container.accountStore.allCredentials().firstOrNull { it.id == accountId }
            ?: return Result.success()
        val repo = container.mailRepository
        val purgeId = inputData.getString(KEY_PURGE_ID)
        return runCatching {
            if (purgeId != null) {
                // The set the user confirmed, recorded at that instant — NOT whatever the Trash
                // holds now (#99). An erased snapshot destroys nothing.
                repo.purgeSnapshot(credentials, purgeId)
            } else {
                val ids = inputData.getStringArray(KEY_EMAIL_IDS)?.toList().orEmpty()
                // The folder those ids sat in when the user confirmed. On JMAP the destroy checks it
                // against the server wave by wave and spares whatever has moved (#122).
                val mailboxId = inputData.getString(KEY_MAILBOX_ID)
                // The IMAP numbering that folder was under WHEN THE USER CONFIRMED (#99). Never
                // re-read here: the folder's current numbering is whatever a refresh has recorded
                // since, so opposing it would compare it with itself and the renumbered case would
                // expunge UIDs that now name other messages. Absent it reads 0 and destroys nothing.
                val uidValidity = inputData.getLong(KEY_UID_VALIDITY, 0L).takeIf { it > 0L }
                if (ids.isNotEmpty() && repo.destroyAll(credentials, ids, mailboxId, uidValidity).failed.isNotEmpty()) {
                    // Some ids were rejected: still on the server, their rows evicted when the
                    // hold-back started. Drop the sync cursors so the next refresh re-queries.
                    repo.resetSyncState()
                }
            }
        }.fold(
            { Result.success() },
            {
                Log.w(TAG, "Held-back destroy failed", it)
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry()
                else {
                    // Given up for good: drop the destroy list rather than leave a standing order
                    // nothing will ever execute.
                    purgeId?.let { id -> runCatching { repo.discardPurgeSnapshot(id) } }
                    repo.resetSyncState() // undestroyed mail reappears on the next full re-query
                    Result.failure()
                }
            },
        )
    }

    /** One folder's share of a held-back destroy: the ids the user confirmed, the folder they were
     *  in and the numbering it was under, grouped so a caller cannot enqueue the ids of one folder
     *  stamped with the numbering of another. [uidValidity] is null on JMAP and on an IMAP folder
     *  never observed; either way the destroy is refused rather than run blind. */
    data class FolderDestroy(val mailboxId: String, val emailIds: List<String>, val uidValidity: Long?)

    companion object {
        private const val TAG = "MessageDestroyWorker"
        private const val KEY_ACCOUNT_ID = "accountId"
        private const val KEY_EMAIL_IDS = "emailIds"

        /** The folder [KEY_EMAIL_IDS] were in when the user confirmed — ONE per request, which is
         *  why the ids are grouped by folder before they are chunked. Null from a version predating
         *  this key, which on JMAP destroys nothing. */
        private const val KEY_MAILBOX_ID = "mailboxId"

        /** The IMAP UIDVALIDITY [KEY_MAILBOX_ID] was under when the user confirmed (#99). 0 means
         *  "none to oppose", and on IMAP that destroys NOTHING: the ids come back in
         *  `BulkResult.failed`, which triggers the re-query below. */
        private const val KEY_UID_VALIDITY = "uidValidity"

        /** The snapshot to destroy. Only the KEY travels in the worker's `Data`, which is capped at
         *  10 KiB while an Empty trash can carry ten thousand ids. */
        private const val KEY_PURGE_ID = "purgeId"
        private const val MAX_ATTEMPTS = 3

        /** Margin over the snackbar window so an Undo cancel always wins the race. */
        private const val DELAY_MARGIN_MS = 1_000L

        /** Byte budget for one request's ids: WorkManager throws past 10240 bytes at enqueue, and
         *  long IMAP ids can blow a fixed per-count chunk. */
        private const val MAX_IDS_BYTES_PER_REQUEST = 6 * 1024

        /**
         * Hold the permanent destroy of [folders] back for [holdBackMs], then run it. The ids come
         */
        fun schedule(context: Context, accountId: String, folders: List<FolderDestroy>, holdBackMs: Long) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                destroyWorkName(accountId),
                ExistingWorkPolicy.REPLACE,
                destroyRequests(accountId, folders, holdBackMs + DELAY_MARGIN_MS),
            )
        }

        /** Commit a pending held-back destroy immediately: the delayed work is cancelled and the
         *  same set re-enqueued with no delay, REPLACE alone silently dropping it. [folders] is the
         *  set captured at THAT confirmation — nothing here may be read afresh. */
        fun flushNow(context: Context, accountId: String, folders: List<FolderDestroy>) {
            cancelDestroy(context, accountId)
            destroyRequests(accountId, folders, delayMs = 0L)
                .forEach { WorkManager.getInstance(context).enqueue(it) }
        }

        /**
         * Destroy [folders] as soon as there is a network, without taking the account's unique
         */
        suspend fun destroyDurably(context: Context, accountId: String, folders: List<FolderDestroy>) {
            destroyRequests(accountId, folders, delayMs = 0L)
                .forEach { WorkManager.getInstance(context).enqueue(it).await() }
        }

        /** Undo: cancel the held-back destroy for [accountId] (nothing was destroyed yet). */
        fun cancelDestroy(context: Context, accountId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(destroyWorkName(accountId))
        }

        /** Hold the Empty-trash purge of snapshot [purgeId] back for [holdBackMs]. [mailboxId] names
         *  the work; it does NOT define what gets destroyed — [purgeId] does (#99). */
        fun schedulePurge(context: Context, accountId: String, mailboxId: String, purgeId: String, holdBackMs: Long) {
            val request = OneTimeWorkRequestBuilder<MessageDestroyWorker>()
                .setInitialDelay(holdBackMs + DELAY_MARGIN_MS, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_ACCOUNT_ID to accountId, KEY_PURGE_ID to purgeId))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(purgeWorkName(accountId, mailboxId), ExistingWorkPolicy.REPLACE, request)
        }

        /** Undo: cancel the held-back purge of [mailboxId] (nothing was destroyed yet). */
        fun cancelPurge(context: Context, accountId: String, mailboxId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(purgeWorkName(accountId, mailboxId))
        }

        private fun destroyRequests(
            accountId: String,
            folders: List<FolderDestroy>,
            delayMs: Long,
        ): List<OneTimeWorkRequest> =
            folders.flatMap { folder ->
                chunkBySize(folder.emailIds).map { chunk ->
                    OneTimeWorkRequestBuilder<MessageDestroyWorker>()
                        .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .setInputData(
                            workDataOf(
                                KEY_ACCOUNT_ID to accountId,
                                KEY_EMAIL_IDS to chunk.toTypedArray(),
                                KEY_MAILBOX_ID to folder.mailboxId,
                                // Data holds no null: 0 IS "nothing to oppose".
                                KEY_UID_VALIDITY to (folder.uidValidity ?: 0L),
                            ),
                        )
                        .build()
                }
            }

        /** Split [emailIds] so each chunk's ids stay under [MAX_IDS_BYTES_PER_REQUEST]. */
        private fun chunkBySize(emailIds: List<String>): List<List<String>> {
            val chunks = mutableListOf<List<String>>()
            var chunk = mutableListOf<String>()
            var bytes = 0
            emailIds.forEach { id ->
                val size = id.encodeToByteArray().size + Long.SIZE_BYTES // UTF-8 + per-entry margin
                if (chunk.isNotEmpty() && bytes + size > MAX_IDS_BYTES_PER_REQUEST) {
                    chunks += chunk
                    chunk = mutableListOf()
                    bytes = 0
                }
                chunk += id
                bytes += size
            }
            if (chunk.isNotEmpty()) chunks += chunk
            return chunks
        }

        private fun destroyWorkName(accountId: String) = "message-destroy-$accountId"

        // The account is part of the name: mailbox ids can collide between same-server accounts,
        // and a colliding name would let one account's purge REPLACE the other's pending one.
        private fun purgeWorkName(accountId: String, mailboxId: String) = "trash-purge-$accountId-$mailboxId"
    }
}
