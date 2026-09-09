package app.sterna.ui.inbox

import app.sterna.core.data.mail.ConversationScope
import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.mail.emailKey
import app.sterna.core.jmap.model.Email
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Which conversation, in which account. Servers number threads per account, so on a bare thread
 *  id unfolding A's row also unfolds B's homonym (#92). One opaque string on the route. */
data class ThreadKey(val accountId: String?, val threadId: String) {
    /** The key as a single route argument. The account id (a UUID) never contains '|'. */
    fun encode(): String = "${accountId.orEmpty()}|$threadId"

    companion object {
        /** Parse an [encode]d route argument. Null for anything that isn't one, a bare thread id
         *  from an older version included: the reader then falls back to the single message. */
        fun decode(raw: String): ThreadKey? {
            val cut = raw.indexOf('|')
            if (cut < 0) return null
            val thread = raw.substring(cut + 1)
            if (thread.isEmpty()) return null
            return ThreadKey(raw.substring(0, cut).ifEmpty { null }, thread)
        }
    }
}

/** The scope the list on screen was built with: the folder(s) it pages and the Sent resolution its
 * chips counted over. Recorded where the pager is built, never re-read at unfold time — after a
 *  switch the old rows stay drawn, and a chip tapped then unfolds with the new scope. */
internal data class ListScope(
    val viewedMailboxIds: List<String> = emptyList(),
    val sentMailboxes: List<Pair<String, String>> = emptyList(),
) {
    /** The folders a conversation of [accountId] covers here: the viewed folder(s) plus that
     *  account's Sent, from the [ConversationScope] decision the chip's query is bound with.
     * [accountId] is the representative's, never the current account's (#92). */
    fun folders(accountId: String?): List<String> =
        ConversationScope.folders(viewedMailboxIds, sentMailboxes, accountId).toList()
}

/** The messages beneath the unfolded rows, as a live reading of the cache. Chip and unfold must
 *  read the same table through the same [ConversationScope] decision, as a flow: against a snapshot
 *  a reply in an open thread moves the chip to 4 over three messages. */
internal object ThreadMemberStream {

    /** The whole membership of every unfolded conversation, keyed by thread — what the row's chip
     * counts. [snoozed] are the ids a snooze hides, which the chip's SQL excludes too. The
     *  representative is not taken out here: an id recorded at tap time is the wrong one. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun members(
        expanded: Flow<Set<ThreadKey>>,
        scope: Flow<ListScope>,
        snoozed: Flow<Set<EmailKey>>,
        fallbackAccountId: () -> String?,
        read: (accountId: String, folders: List<String>, threadKey: String) -> Flow<List<Email>>,
    ): Flow<Map<ThreadKey, List<Email>>> =
        combine(expanded, scope) { keys, s -> keys to s }
            .flatMapLatest { (keys, s) ->
                if (keys.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    combine(keys.map { key -> one(key, s, fallbackAccountId, read) }) { it.toMap() }
                }
            }
            .combine(snoozed) { all, hidden ->
                if (hidden.isEmpty()) all
                else all.mapValues { (_, members) -> members.filterNot { it.emailKey() in hidden } }
            }

    private fun one(
        key: ThreadKey,
        scope: ListScope,
        fallbackAccountId: () -> String?,
        read: (accountId: String, folders: List<String>, threadKey: String) -> Flow<List<Email>>,
    ): Flow<Pair<ThreadKey, List<Email>>> {
        val accountId = key.accountId ?: fallbackAccountId() ?: return flowOf(key to emptyList())
        // The same decision, on the same recorded resolution, the chip's query was bound with.
        return read(accountId, scope.folders(accountId), key.threadId).map { all -> key to all }
    }

    /** What to draw after a live reading: [live] decides membership, the copies already [drawn]
     *  decide content, so a row under the reader's eyes is never rewritten mid-read (#63). [removed]
     *  are members a swipe took off screen while its round-trip is still in flight. */
    fun reconcile(drawn: List<Email>, live: List<Email>, removed: Set<EmailKey>): List<Email> {
        val byKey = drawn.associateBy { it.emailKey() }
        return live.mapNotNull { m ->
            val key = m.emailKey()
            if (key in removed) null else byKey[key] ?: m
        }
    }
}

/** The members masked from the live reading of the unfolded rows: a gesture has taken a row off
 * screen but its cache row leaves only on the server's word. [hiding] is the whole API — mask,
 * call, lift in a `finally`. A set, not a counter: overlapping calls would lift too early. */
internal class ThreadMemberMask {
    private val hidden = mutableSetOf<EmailKey>()

    /** The masked members, for the reading to drop — see [ThreadMemberStream.reconcile]. */
    val keys: Set<EmailKey> get() = hidden

    /** Mask [keys] for the duration of [op], and no longer. */
    suspend fun <T> hiding(keys: Set<EmailKey>, op: suspend () -> T): T {
        hidden += keys
        return try {
            op()
        } finally {
            hidden -= keys
        }
    }

    /** Forget everything: the rows the mask applied to are no longer on screen at all. */
    fun clear() = hidden.clear()
}

/** The swipe context of every conversation a message has been opened from, in the order the row
 * showed it. Recorded from the screen, never rebuilt from a representative remembered since the
 *  unfold — the row re-picks its representative on every write ([membersBelow]). */
internal class ThreadOrders {
    private val orders = mutableMapOf<ThreadKey, List<Pair<String, String?>>>()

    /** Record what [key]'s row is drawing, and answer with that order. */
    fun record(key: ThreadKey, representative: Email, members: List<Email>): List<Pair<String, String?>> =
        ConversationExpansion.threadEntries(representative.id, representative.accountId, members)
            .also { orders[key] = it }

    /** The conversation a message was opened from; empty when nothing was recorded for [key], the
     *  reader then falling back to the single message it was given. */
    fun entries(key: ThreadKey): List<Pair<String, String?>> = orders[key].orEmpty()

    /** Forget [key]'s order: its conversation is leaving the list. */
    fun drop(key: ThreadKey) {
        orders -= key
    }

    /** Forget every order: the list itself changed underneath (folder, account). */
    fun clear() = orders.clear()
}

/** Pure helpers for inline conversation expansion in the inbox list. */
internal object ConversationExpansion {
    /** The conversation a message belongs to: its account plus its threadId, or its own id when
     * thread-less. Account-qualified, always: see [ThreadKey]. */
    fun threadKey(accountId: String?, threadId: String?, id: String): ThreadKey =
        ThreadKey(accountId, threadId ?: id)

    /** Toggle a thread key in (or out of) the set of currently-expanded threads. */
    fun toggle(expanded: Set<ThreadKey>, key: ThreadKey): Set<ThreadKey> =
        if (key in expanded) expanded - key else expanded + key

    /** The members to list beneath an unfolded row: the thread minus the representative the row
     * draws. Called at the draw site, with the id that row shows at that instant — the paging
     *  query re-picks it on every write, and a remembered one draws the newcomer twice. */
    fun membersBelow(all: List<Email>, representativeId: String): List<Email> =
        all.filter { it.id != representativeId }

    /** The messages of an unfolded conversation in the order the list shows them: representative
     *  first, then [membersBelow], each paired with its owning account for the reader's pager.
     *  Deduped by id, so a member copy of the representative cannot produce two pages. */
    fun threadEntries(
        representativeId: String,
        representativeAccountId: String?,
        members: List<Email>,
    ): List<Pair<String, String?>> =
        (listOf(representativeId to representativeAccountId) + members.map { it.id to it.accountId })
            .distinctBy { it.first }

}
