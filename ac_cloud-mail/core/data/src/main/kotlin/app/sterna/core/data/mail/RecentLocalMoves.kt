package app.sterna.core.data.mail

import java.util.concurrent.ConcurrentHashMap

/** Ids of messages this app itself just moved into another folder, stamped at the server ack. The
 *  notifier diffs every watched folder against a persisted baseline, so such a message would
 *  ~30 min, hence 45 minutes. Keyed by account + id: JMAP ids are per account (#92). */
class RecentLocalMoves(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val movedAt = ConcurrentHashMap<EmailKey, Long>()

    /** Record a self-move of [emailId] on [accountId], on server ack; for IMAP, with the id AT ITS
     *  DESTINATION — an IMAP move changes the id. */
    fun mark(accountId: String, emailId: String) {
        val now = clock()
        movedAt.entries.removeIf { now - it.value > ttlMs }
        movedAt[EmailKey(accountId, emailId)] = now
    }

    /** Whether [key] is still inside its self-move window. Non-consuming: it expires by TTL. */
    operator fun contains(key: EmailKey): Boolean {
        val at = movedAt[key] ?: return false
        if (clock() - at > ttlMs) {
            movedAt.remove(key)
            return false
        }
        return true
    }

    companion object {
        const val DEFAULT_TTL_MS = 45L * 60 * 1000
    }
}
