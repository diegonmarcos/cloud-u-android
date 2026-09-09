package app.sterna.core.data.mail

import app.sterna.core.data.db.EmailBodyDao
import app.sterna.core.data.db.MailboxUidValidityDao
import app.sterna.core.data.db.MailboxUidValidityEntity
import app.sterna.core.data.db.PurgeSnapshotDao

/**
 * What an IMAP folder's UIDVALIDITY licenses (RFC 3501 §2.3.1.1, #99). After a shifting
 * renumbering, a UID we still hold names another message, not merely a missing one.
 */
object UidValidity {

    enum class Verdict {
        UNVERIFIABLE,

        FIRST_SIGHT,

        SAME,

        /** Renumbered: nothing keyed by UID may be acted on until the caches are dropped. */
        CHANGED,
    }

    fun verdict(recorded: Long?, observed: Long): Verdict = when {
        observed <= 0L -> Verdict.UNVERIFIABLE
        recorded == null -> Verdict.FIRST_SIGHT
        recorded <= 0L -> Verdict.UNVERIFIABLE
        recorded == observed -> Verdict.SAME
        else -> Verdict.CHANGED
    }

    /** The numbering a server stated, or null when it stated none (`0L`). The one normalisation:
     *  a row stored under `null` never matches a lookup at `0L` (#99, #187). */
    fun stated(uidValidity: Long?): Long? = uidValidity?.takeIf { it > 0L }

    /** `false` without a numbering: an unverifiable destroy list destroys nothing. IMAP only. */
    fun mayDestroy(snapshotUidValidity: Long?): Boolean = snapshotUidValidity != null && snapshotUidValidity > 0L

    /** Both numberings real and equal, or refuse. Deliberately stricter than `core/imap`, whose
     *  SELECT refuses only when two positive numbers disagree, letting a silent server pass (#99). */
    fun mayDestroyUnderStatedNumbering(frozen: Long?, stated: Long): Boolean =
        stated > 0L && frozen != null && frozen > 0L && frozen == stated

    /** All-or-nothing, [expected] being one number. Not a licence to merge waves: folding rows read
     *  under two numberings into one call sends the older ids out under the newer number (#99). */
    fun destroyableUnderNumbering(requested: List<String>, expected: Long?): List<String> =
        if (mayDestroy(expected)) requested else emptyList()

    /** [refused] belongs in `BulkResult.failed`: their rows were evicted when the hold-back started,
     *  and `failed` is what makes the caller re-query and bring them back. */
    data class ImapDestroyPlan(val byFolder: Map<String, List<String>>, val refused: List<String>)

    /** [destroyableUnderNumbering] plus the fan-out. The only caller that expunges, so a server
     *  announcing no UIDVALIDITY blocks IMAP permanent delete of a selection entirely, knowingly. */
    fun imapDestroyPlan(requested: List<String>, expected: Long?): ImapDestroyPlan {
        val destroyable = destroyableUnderNumbering(requested, expected)
        val grouped = destroyable.groupBy { ImapMailService.mailboxOf(it) }
        return ImapDestroyPlan(
            byFolder = grouped.filterKeys { it != null }.mapKeys { (folder, _) -> folder!! },
            refused = (requested - destroyable.toSet()) + grouped[null].orEmpty(),
        )
    }

    /** Ids of one folder read under one numbering, in arrival order. [uidValidity] is what those
     *  UIDs belong to, not the folder's current record; null refuses. */
    data class ImapDestroyRoute(
        val mailboxId: String,
        val emailIds: List<String>,
        val uidValidity: Long?,
    )

    /** Splits by (folder frozen at the confirmation, numbering the ids were read under): one
     *  `UID STORE` + `UID EXPUNGE` can oppose only one number (#99). */
    fun imapDestroyRoutes(
        targets: List<Pair<String, String>>,
        numberingOf: (String) -> Long?,
    ): List<ImapDestroyRoute> {
        val byRoute = LinkedHashMap<Pair<String, Long?>, MutableList<String>>()
        targets.forEach { (emailId, mailboxId) ->
            byRoute.getOrPut(mailboxId to numberingOf(emailId)) { mutableListOf() } += emailId
        }
        return byRoute.map { (route, ids) -> ImapDestroyRoute(route.first, ids.toList(), route.second) }
    }

    /** [drifted] is what the line has been replaced under since it was ticked: same key, another
     *  message. Both keep input order. */
    data class TickedNumberingSplit<T>(val kept: List<T>, val drifted: List<T>)

    /** A row drifts if and only if both stamps are real and disagree. An absent stamp passes, unlike
     *  on the move to another account (#189), because `uidValidity` is null on every JMAP row and
     *  this caller cannot say whether a tick happened (#99). */
    fun <T> destroyableUnderTheNumberingItWasTickedUnder(
        rows: List<T>,
        tickedUnder: (T) -> Long?,
        readUnderNow: (T) -> Long?,
    ): TickedNumberingSplit<T> {
        val kept = mutableListOf<T>()
        val drifted = mutableListOf<T>()
        rows.forEach { row ->
            val ticked = tickedUnder(row)
            val now = readUnderNow(row)
            if (ticked != null && now != null && ticked != now) drifted += row else kept += row
        }
        return TickedNumberingSplit(kept, drifted)
    }

    /** [frozenUidValidity] is [currentFolder]'s numbering as `COPYUID` stated it when the forward
     *  move landed the mail there: the number [messages]' UIDs belong to. */
    data class ImapUndoRoute(
        val currentFolder: String,
        val sourceFolder: String,
        val frozenUidValidity: Long?,
        /** (UID in [currentFolder], emailId), in the order the targets came. */
        val messages: List<Pair<Long, String>>,
    ) {
        val uids: List<Long> get() = messages.map { it.first }
    }

    /** Splits by (folder the mail sits in now, folder it goes home to, numbering that folder stated
     *  on landing). An id [landedAt] knows nothing about is dropped silently, no COPYUID for it. */
    fun imapUndoRoutes(
        targets: List<Pair<String, String>>,
        landedAt: (String) -> ImapLoc?,
    ): List<ImapUndoRoute> {
        val byRoute = LinkedHashMap<Triple<String, String, Long?>, MutableList<Pair<Long, String>>>()
        targets.forEach { (emailId, sourceFolder) ->
            val loc = landedAt(emailId) ?: return@forEach
            byRoute.getOrPut(Triple(loc.mailboxId, sourceFolder, loc.uidValidity)) { mutableListOf() } += loc.uid to emailId
        }
        return byRoute.map { (route, messages) ->
            ImapUndoRoute(route.first, route.second, route.third, messages.toList())
        }
    }

    /** The `LIKE` pattern for one IMAP folder's cached ids, SQL wildcards escaped: a folder called
     *  `a_b` would otherwise match `axb` and take a neighbour's bodies. Backslash first. */
    fun bodyCacheIdPrefix(accountId: String, mailboxId: String): String =
        (ImapMailService.emailId(accountId, mailboxId, 0L).dropLast(1))
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_") + "%"
}

/** Where a forward IMAP move put a message, as `COPYUID` stated it, for the Undo (#99).
 *  [uidValidity] is a freeze, never a reading: a `UID MOVE` never SELECTs its destination. */
data class ImapLoc(val mailboxId: String, val uid: Long, val uidValidity: Long?)

/** A destroy frozen under [expected] was refused before a command naming a UID went out (#99).
 *  Deliberately neither `ImapUidValidityChanged`, which would let a momentarily quiet server throw
 *  away a confirmed "Empty trash", nor an `ImapException`, which `withSession`'s retry absorbs. */
class ImapNumberingUnconfirmed(
    val mailbox: String,
    val expected: Long?,
) : Exception("The server stated no UIDVALIDITY for $mailbox; a destroy frozen under $expected was refused")

/** Where the per-folder UIDVALIDITY lives, seen from [ImapMailService], which has a connection and
 *  no database. [None] remembers nothing, so every SELECT goes out unverified. */
interface UidValidityStore {

    suspend fun recorded(accountId: String, mailboxId: String): Long?

    suspend fun record(accountId: String, mailboxId: String, uidValidity: Long)

    /** Drop what is keyed by UID and cannot heal itself, then record the new numbering so the next
     *  call is not refused for the same reason. */
    suspend fun invalidate(accountId: String, mailboxId: String, uidValidity: Long)

    /** Called with `(accountId, mailboxId)` on invalidation, for the notification baseline in `:app`.
     *  Cleared, the next pass seeds silently: a diff against the old baseline re-announces read mail. */
    var onRenumbered: ((String, String) -> Unit)?

    object None : UidValidityStore {
        override suspend fun recorded(accountId: String, mailboxId: String): Long? = null
        override suspend fun record(accountId: String, mailboxId: String, uidValidity: Long) = Unit
        override suspend fun invalidate(accountId: String, mailboxId: String, uidValidity: Long) = Unit
        override var onRenumbered: ((String, String) -> Unit)? = null
    }
}

/** The one place that says what a renumbering invalidates: the folder's cached bodies and its
 *  pending destroy lists. Surviving rows heal only in columns the walk overwrites unconditionally;
 *  `preview` is written back from the cache, so its read is bounded by [UidValidity.stated], and any
 *  future column filled that way owes the same bound (#187). */
class MailboxUidValidityStore(
    private val dao: MailboxUidValidityDao,
    private val bodies: EmailBodyDao,
    private val purgeSnapshots: PurgeSnapshotDao,
) : UidValidityStore {

    override var onRenumbered: ((String, String) -> Unit)? = null

    override suspend fun recorded(accountId: String, mailboxId: String): Long? =
        dao.recorded(accountId, mailboxId)

    override suspend fun record(accountId: String, mailboxId: String, uidValidity: Long) {
        if (uidValidity <= 0L) return
        dao.record(MailboxUidValidityEntity(accountId, mailboxId, uidValidity))
    }

    override suspend fun invalidate(accountId: String, mailboxId: String, uidValidity: Long) {
        bodies.deleteForIdPrefix(accountId, UidValidity.bodyCacheIdPrefix(accountId, mailboxId))
        purgeSnapshots.deleteForMailbox(accountId, mailboxId)
        record(accountId, mailboxId, uidValidity)
        // Last and best effort: the app-layer hook must never cost the invalidation.
        onRenumbered?.let { hook -> runCatching { hook(accountId, mailboxId) } }
    }
}
