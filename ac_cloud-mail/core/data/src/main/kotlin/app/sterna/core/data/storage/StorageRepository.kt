package app.sterna.core.data.storage

import android.content.Context
import app.sterna.core.data.db.EmailBodyDao
import app.sterna.core.data.db.EmailDao
import app.sterna.core.data.db.EmailFtsDao
import app.sterna.core.data.db.LocalDraftDao
import app.sterna.core.data.db.MailboxDao
import app.sterna.core.data.db.MailboxUidValidityDao
import app.sterna.core.data.db.PurgeSnapshotDao
import app.sterna.core.data.db.SnoozedDao
import app.sterna.core.data.mail.localDraftDirName
import app.sterna.core.data.mail.purgeLocalDraftsOfAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/** On-device storage used by the cache, for the Storage & Sync settings screen. */
data class StorageUsage(
    val databaseBytes: Long,
    val attachmentBytes: Long,
    val perAccount: List<AccountUsage>,
) {
    val totalBytes: Long get() = databaseBytes + attachmentBytes
}

/** Cached-message count attributed to one account. */
data class AccountUsage(val accountId: String, val messageCount: Int)

/**
 * Reports and manages the on-device cache (Room DB + downloaded attachments) — device usage only,
 */
class StorageRepository(
    private val context: Context,
    private val emailDao: EmailDao,
    private val emailFtsDao: EmailFtsDao,
    private val emailBodyDao: EmailBodyDao,
    private val mailboxDao: MailboxDao,
    private val snoozedDao: SnoozedDao,
    private val purgeSnapshotDao: PurgeSnapshotDao,
    private val mailboxUidValidityDao: MailboxUidValidityDao,
    /** Drafts written on the phone and not yet on the server (#95) — user data, not cache. */
    private val localDraftDao: LocalDraftDao,
    /** Where those drafts' attachment bytes are staged; the same tree `MailRepository` writes to. */
    private val localDraftFilesDir: File,
) {
    private val attachmentsDir: File get() = File(context.cacheDir, "attachments")

    /**
     * Where compose stages outgoing-attachment bytes (#70). enqueueSend copies these into the
     */
    private val outgoingDir: File get() = File(context.cacheDir, "outgoing")

    suspend fun usage(): StorageUsage = withContext(Dispatchers.IO) {
        val dbBytes = DB_FILES.sumOf { context.getDatabasePath(it).safeLength() }
        val attachmentBytes = (attachmentsDir.listFiles()?.sumOf { it.safeLength() } ?: 0L) +
            (outgoingDir.listFiles()?.sumOf { it.safeLength() } ?: 0L)
        val perAccount = emailDao.countsByAccount()
            .map { AccountUsage(it.accountId, it.messageCount) }
        StorageUsage(dbBytes, attachmentBytes, perAccount)
    }

    /** Purge every cached message + mailbox + attachment, keeping accounts/settings. */
    suspend fun clearAllCache() = withContext(Dispatchers.IO) {
        emailDao.deleteAll()
        emailFtsDao.clearAll()
        emailBodyDao.deleteAll()
        mailboxDao.deleteAll()
        // The recorded IMAP numbering describes the cache that has just gone (#99): keeping it
        // would leave "clear cache" incomplete, and the first sync records it again anyway.
        mailboxUidValidityDao.deleteAll()
        clearAttachments()
    }

    /** Cached-message count for one account. */
    suspend fun accountMessageCount(accountId: String): Int = withContext(Dispatchers.IO) {
        emailDao.countForAccount(accountId)
    }

    /**
     * Purge one account's cached messages. Snoozes are user intent, not cache — kept.
     */
    suspend fun clearAccountCache(accountId: String) = withContext(Dispatchers.IO) {
        emailDao.deleteForAccount(accountId)
        emailFtsDao.clearAccount(accountId)
        emailBodyDao.deleteForAccount(accountId)
        mailboxDao.deleteForAccount(accountId)
        mailboxUidValidityDao.deleteForAccount(accountId)
        clearAttachments()
    }

    /**
     * On sign-out: purge the account's cached rows and the attachment cache, which is not namespaced
     */
    suspend fun purgeAccount(accountId: String): Unit = withContext(Dispatchers.IO) {
        runCatching { emailDao.deleteForAccount(accountId) }
        runCatching { emailFtsDao.clearAccount(accountId) }
        runCatching { emailBodyDao.deleteForAccount(accountId) }
        runCatching { mailboxDao.deleteForAccount(accountId) }
        runCatching { snoozedDao.deleteForAccount(accountId) }
        // A pending Empty-trash destroy list belongs to the account that is going away (#99).
        runCatching { purgeSnapshotDao.deleteForAccount(accountId) }
        runCatching { mailboxUidValidityDao.deleteForAccount(accountId) }
        // Every row of the account, EDITING and STAGING ones included — an upload queue read would
        // walk past exactly the draft whose composer was open when the user signed out.
        runCatching {
            purgeLocalDraftsOfAccount(
                ids = { localDraftDao.observeForAccount(accountId).first().map { it.id } },
                deleteRows = { localDraftDao.deleteForAccount(accountId) },
                dirOf = { File(localDraftFilesDir, localDraftDirName(it)) },
            )
        }
        runCatching { clearAttachments() }
    }

    /**
     * Sweep the cached mail of accounts that no longer exist, given the accounts that DO.
     */
    suspend fun purgeOrphanedAccounts(knownAccountIds: () -> Collection<String>): List<String> =
        withContext(Dispatchers.IO) {
            // The inventory FIRST: everything written after this snapshot belongs to an account the
            // list read below is guaranteed to contain.
            val cached = emailDao.countsByAccount().map { it.accountId } +
                mailboxDao.accountIds() + emailFtsDao.accountIds() + emailBodyDao.accountIds()
            val known = knownAccountIds()
            val orphans = OrphanedAccountCache.orphans(known, cached)
            orphans.forEach { accountId ->
                // One runCatching PER table: a failure in one must not leave the rest behind.
                runCatching { emailDao.deleteForAccount(accountId) }
                runCatching { emailFtsDao.clearAccount(accountId) }
                runCatching { emailBodyDao.deleteForAccount(accountId) }
                runCatching { mailboxDao.deleteForAccount(accountId) }
            }
            orphans
        }

        /**
         * Write a downloaded attachment to the cache under a name derived from [name], then enforce
         * the size/age cap.
         *
         * [name] is a string a STRANGER chose, so it goes through [SafeFileName] rather than through
         * a character replacement written inline here -- pure, in one place, and executable by a test.
         * The canonical-path check below is not redundant with it: it is the invariant that must hold
         * WHATEVER [SafeFileName] does, including after someone edits it. A write that would land
         * outside this directory throws instead of happening.
         */
    suspend fun cacheAttachment(name: String?, bytes: ByteArray): File = withContext(Dispatchers.IO) {
        val dir = attachmentsDir.apply { mkdirs() }
        val file = File(dir, SafeFileName.of(name))
        val root = dir.canonicalPath
        val target = file.canonicalFile
        // `canonicalPath` resolves `..` and any symlink, so this compares where the write would
        // ACTUALLY land, not where the string suggests it would. The separator guards against
        // `…/attachments-elsewhere` passing a bare prefix test.
        require(target.parentFile?.canonicalPath == root) {
            "Refusing to write an attachment outside the cache directory."
        }
        file.writeBytes(bytes)
        enforceAttachmentCap()
        file
    }

    /** LRU eviction: drop files past the age cap, then oldest-first past the size cap. */
    private fun enforceAttachmentCap() {
        // Bound the outgoing staging dir too (#70). The cap is far longer than any compose stays
        // open, so an in-progress attachment is never pruned from under the composer.
        val now0 = System.currentTimeMillis()
        outgoingDir.listFiles()?.forEach { if (now0 - it.lastModified() > MAX_AGE_MS) it.delete() }
        val files = attachmentsDir.listFiles()?.filter { it.isFile } ?: return
        val now = System.currentTimeMillis()
        val survivors = files.filter { file ->
            val tooOld = now - file.lastModified() > MAX_AGE_MS
            if (tooOld) file.delete()
            !tooOld
        }
        var total = survivors.sumOf { it.safeLength() }
        if (total <= MAX_ATTACHMENT_BYTES) return
        for (file in survivors.sortedBy { it.lastModified() }) {
            if (total <= MAX_ATTACHMENT_BYTES) break
            val size = file.safeLength()
            if (file.delete()) total -= size
        }
    }

    private fun clearAttachments() {
        clearAttachmentTrees(attachmentsDir, outgoingDir)
    }

    private fun File.safeLength(): Long = if (exists()) length() else 0L

    private companion object {
        val DB_FILES = listOf("sterna.db", "sterna.db-wal", "sterna.db-shm")
        const val MAX_ATTACHMENT_BYTES = 200L * 1024 * 1024 // 200 MB
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000 // 30 days
    }
}

/**
 * Empty the two attachment CACHE trees — the downloaded attachments and compose's outgoing staging
 */
internal fun clearAttachmentTrees(attachmentsDir: File, outgoingDir: File) {
    attachmentsDir.listFiles()?.forEach { it.delete() }
    outgoingDir.listFiles()?.forEach { it.delete() }
}
