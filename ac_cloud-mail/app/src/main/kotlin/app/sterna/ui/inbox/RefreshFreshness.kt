package app.sterna.ui.inbox

/*
 * "Has this very view just been reconciled?" (#178). Persisted, because the case defended is the
 * cold start, which an in-memory register covers not at all. The key carries the account id next
 */

/** How long a successful reconcile answers for its view. 30 s is the gesture being defended: leave
 *  the app, come back. Bypassed by the pull gesture and the two Retry buttons. */
internal const val REFRESH_FRESHNESS_WINDOW_MS = 30_000L

/** One (account, folder) pair reconciled successfully, and when. */
internal data class FreshScope(val accountId: String, val mailboxId: String, val at: Long)

/** Between the three fields of an entry. */
private const val FIELD_SEPARATOR = "\t"

/** Between entries. */
private const val ENTRY_SEPARATOR = "\n"

/** Whether this entry can be written down and read back as itself: both separators are refused in
 *  both ids, and so is an empty id, indistinguishable from a truncated line at decode time. */
private fun FreshScope.isStorable(): Boolean =
    accountId.isNotEmpty() && mailboxId.isNotEmpty() &&
        !accountId.contains(FIELD_SEPARATOR) && !accountId.contains(ENTRY_SEPARATOR) &&
        !mailboxId.contains(FIELD_SEPARATOR) && !mailboxId.contains(ENTRY_SEPARATOR)

/** Whether [this] entry still answers for its view at [now]. The lower bound matters as much as
 *  the upper: the clock is `SystemClock.elapsedRealtime`, which restarts at a reboot, so a stamp
 *  written before one lies in the future and must read as stale. */
private fun FreshScope.isFreshAt(now: Long): Boolean =
    at <= now && at > now - REFRESH_FRESHNESS_WINDOW_MS

/** The register as stored, or an empty list. Every malformed line is dropped silently: the cost of
 *  a dropped line is one refresh, the cost of a throw a crash in a ViewModel constructor. */
internal fun decodeFreshness(stored: String?): List<FreshScope> {
    if (stored.isNullOrEmpty()) return emptyList()
    return stored.split(ENTRY_SEPARATOR).mapNotNull { line ->
        val parts = line.split(FIELD_SEPARATOR)
        if (parts.size != 3) return@mapNotNull null
        val at = parts[2].toLongOrNull() ?: return@mapNotNull null
        FreshScope(parts[0], parts[1], at).takeIf { it.isStorable() }
    }
}

/** [entries] as the opaque string the store holds, or `null` when there is nothing worth storing.
 *  Entries that cannot be written unambiguously are dropped rather than escaped: see [isStorable]. */
internal fun encodeFreshness(entries: List<FreshScope>): String? {
    val lines = entries.filter { it.isStorable() }
        .map { "${it.accountId}$FIELD_SEPARATOR${it.mailboxId}$FIELD_SEPARATOR${it.at}" }
    return if (lines.isEmpty()) null else lines.joinToString(ENTRY_SEPARATOR)
}

/** Whether a refresh over [scopes] can be skipped at [now]: every scope must have been reconciled
 * inside the window — one stale account in the unified inbox is a stale unified inbox. An empty
 *  [scopes] is never fresh: it is an account that never synced, and skipping freezes an empty list. */
internal fun isFresh(entries: List<FreshScope>, scopes: List<Pair<String, String>>, now: Long): Boolean {
    if (scopes.isEmpty()) return false
    return scopes.all { (accountId, mailboxId) ->
        entries.any { it.accountId == accountId && it.mailboxId == mailboxId && it.isFreshAt(now) }
    }
}

/** The register after [scopes] were reconciled successfully at [now]. Everything outside the window
 *  is pruned on the way through, which bounds its size; an existing entry is replaced, not doubled. */
internal fun recordFresh(
    entries: List<FreshScope>,
    scopes: List<Pair<String, String>>,
    now: Long,
): List<FreshScope> {
    val recorded = scopes.distinct()
    val keys = recorded.toSet()
    val kept = entries.filter { it.isFreshAt(now) && (it.accountId to it.mailboxId) !in keys }
    return kept + recorded.map { (accountId, mailboxId) -> FreshScope(accountId, mailboxId, now) }
}

/** The register once [removedIds] have been signed out, or `null` when nothing is left — the same
 *  promise as [prunedView]'s (`PRIVACY.md`), folder ids being IMAP paths the reader named herself.
 * A filter, never a wipe, and the `accountId` decides, not the `mailboxId`. */
internal fun prunedFreshness(stored: String?, removedIds: Set<String>): String? =
    encodeFreshness(decodeFreshness(stored).filterNot { it.accountId in removedIds })
