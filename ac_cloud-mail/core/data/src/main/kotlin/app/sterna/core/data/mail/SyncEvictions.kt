package app.sterna.core.data.mail

import android.os.SystemClock
import app.sterna.core.data.db.EmailRetentionRow
import app.sterna.core.data.getOrElseUnlessCancelled
import java.util.concurrent.ConcurrentHashMap

/** An id in both `removed` and `added` only changed position. [isProtected]'s spare can swallow a
 *  real destroy, which [ghostEvictions] heals (#107). */
internal fun deltaEvictions(
    removed: List<String>,
    added: Set<String>,
    destroyed: List<String>,
    isProtected: (String) -> Boolean,
): List<String> = ((removed.toSet() - added).toList() + destroyed).filterNot(isProtected)

/** Only ids the cache still holds in that mailbox: `EmailDao.deleteByIds` names no folder, so
 *  evicting an id whose row has moved meanwhile would delete it from the folder it is now in. */
internal fun mailboxScopedEvictions(
    candidates: List<String>,
    cachedInMailbox: Set<String>,
): List<String> = candidates.filter { it in cachedInMailbox }

/** The gate a notification banner passes before it is taken down (#134): a delta alone is not
 *  enough, a keyword set from another client being reported as `removed`. A failed [locate] confirms
 *  nothing and is swallowed on purpose, the action being cosmetic. */
internal suspend fun confirmedDepartures(
    candidates: List<String>,
    mailboxId: String,
    locate: suspend (List<String>) -> Map<String, Set<String>>,
): List<String> {
    if (candidates.isEmpty() || mailboxId.isBlank()) return emptyList()
    val located = runCatching { locate(candidates) }.getOrElseUnlessCancelled { return emptyList() }
    return candidates.filter { id ->
        // Absent = gone from the server; present but elsewhere = moved out; present here = a flag.
        val folders = located[id] ?: return@filter true
        mailboxId !in folders
    }
}

/** Why [deltaEvictions] kept a cached id, ordered by severity for the log. Only [DESTROY] leaves a
 *  true ghost; [REMOVAL] is usually a stale keyword change. */
internal enum class SpareReason(val log: String) {
    DESTROY("destroy"),

    REMOVAL("removal"),
}

/** The ids [deltaEvictions] would have evicted but did not, for the sync log. Pass the same
 *  [isProtected] instance, or the log can disagree with what was actually evicted. */
internal fun sparedEvictions(
    removed: List<String>,
    added: Set<String>,
    destroyed: List<String>,
    isProtected: (String) -> Boolean,
): List<Pair<String, SpareReason>> {
    val destroyedIds = destroyed.toSet()
    val vanished = removed.toSet() - added
    return (destroyedIds + vanished)
        .filter(isProtected)
        .map { it to if (it in destroyedIds) SpareReason.DESTROY else SpareReason.REMOVAL }
}

/** Only ids the server explicitly reported `notFound`. The recently-mutated spare is deliberately
 *  not consulted, a point lookup being authoritative; a null [notFound] prunes nothing. */
internal fun ghostEvictions(cachedIds: List<String>, notFound: Set<String>?): List<String> =
    if (notFound.isNullOrEmpty()) emptyList() else cachedIds.filter { it in notFound }

/** `added` ids the cache has never held, plus cached rows reported `updated`. Every id here lands in
 *  the cache by the end of the sync, so it is also the delta branch's `freshIds` for
 *  [retentionEvictions] (#110). */
internal fun deltaFetches(
    added: Set<String>,
    cachedIds: Set<String>,
    updated: List<String>,
): List<String> = ((added - cachedIds) + updated.filter { it in cachedIds }).distinct()

/** Which cached ids the retention window evicts after a refresh, none of them authoritative: the
 *  server still has every one. A row goes only when dated (`sortKey > 0`), older than [cutoffMillis],
 *  and outside [freshIds] (what the sync just fetched, #110), [spareIds] and the [keepNewest] newest
 *  rows; a null [freshIds] prunes nothing. */
internal fun retentionEvictions(
    cached: List<EmailRetentionRow>,
    cutoffMillis: Long,
    freshIds: Set<String>?,
    spareIds: Set<String>,
    keepNewest: Int,
): List<String> {
    if (freshIds == null) return emptyList()
    if (cached.size <= keepNewest) return emptyList()
    val floor = cached
        // Ties broken on id: a floor depending on SQLite's row order flickers a row in and out.
        .sortedWith(compareByDescending<EmailRetentionRow> { it.sortKey }.thenBy { it.id })
        .take(keepNewest)
        .mapTo(HashSet()) { it.id }
    return cached
        .filter {
            it.sortKey > 0 && it.sortKey < cutoffMillis &&
                it.id !in freshIds && it.id !in spareIds && it.id !in floor
        }
        .map { it.id }
}

/** Which cached ids a full-query reconcile drops, in batches no statement can choke on. Computed as
 *  a complement against the whole [keepIds] before chunking: SQLite binds at most 999 variables below
 *  Android 12 (#29), and chunking a `NOT IN` would delete the whole folder. */
internal fun reconcileEvictions(
    cachedIds: List<String>,
    keepIds: Set<String>,
    spareIds: Set<String>,
    chunk: Int = MAX_CHANGES,
): List<List<String>> =
    cachedIds.filter { it !in keepIds && it !in spareIds }.chunked(chunk)

/** The bound making a full re-query safe against writes it races (#165): the DAO re-reads the folder
 *  after the walk, so [cachedIds] holds whatever landed meanwhile. [evictableIds] is the folder as it
 *  stood before the walk; null means no bound, an empty set turns the reconcile off. */
internal fun evictableCachedIds(cachedIds: List<String>, evictableIds: Set<String>?): List<String> =
    if (evictableIds == null) cachedIds else cachedIds.filter { it in evictableIds }

/** On [firstThisSession] (which carries the retry of a sweep that failed in transport), on
 *  [vanishedFromMailbox], or on the floor, unconditionally: a destroy reported in no delta leaves a
 *  state that never moves again (#107). */
internal fun shouldSweepGhosts(
    firstThisSession: Boolean,
    stateAdvanced: Boolean,
    vanishedFromMailbox: Boolean,
    millisSinceLastSweep: Long,
    minIntervalMs: Long,
): Boolean = firstThisSession ||
    (stateAdvanced && vanishedFromMailbox) ||
    millisSinceLastSweep >= minIntervalMs

/** Which clause of [shouldSweepGhosts] decided, as a log token, in the same order of tests:
 *  `session`, `removal`, `floor`, `floor/idle` (the interval elapsed on an account whose deltas said
 *  nothing, #107), `skip/idle`, `skip/throttled`. A `skip` prefix means no sweep. */
internal fun sweepReason(
    firstThisSession: Boolean,
    stateAdvanced: Boolean,
    vanishedFromMailbox: Boolean,
    millisSinceLastSweep: Long,
    minIntervalMs: Long,
): String = when {
    firstThisSession -> "session"
    stateAdvanced && vanishedFromMailbox -> "removal"
    millisSinceLastSweep >= minIntervalMs -> if (stateAdvanced) "floor" else "floor/idle"
    stateAdvanced -> "skip/throttled"
    else -> "skip/idle"
}

/** Floor between two recurring existence sweeps of the same mailbox: the other trigger is an
 *  account-wide state, so without it the sweep fires on nearly every incremental sync. */
internal const val GHOST_SWEEP_MIN_INTERVAL_MS = 5 * 60_000L

/** When each mailbox may be existence-swept again. Scoped per (account, mailbox) because two
 *  accounts on one server share mailbox ids (#31/#92), in memory only so a cold start starts every
 *  mailbox eligible, and on a monotonic clock: a wall clock moving back would stall the floor. */
internal class GhostSweepSchedule(
    private val minIntervalMs: Long = GHOST_SWEEP_MIN_INTERVAL_MS,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val lastSweep = ConcurrentHashMap<String, Long>()

    /** Pairs whose once-per-process credit is spent: `add` both grants and records it. */
    private val sweptThisSession = ConcurrentHashMap.newKeySet<String>()

    // NUL-separated: "$account$mailbox" is ambiguous ("a1" + "b" collides with "a" + "1b").
    private fun key(accountId: String, mailboxId: String) = "$accountId\u0000$mailboxId"

    /** Decides and records the attempt. A granted claim consumes the floor at once, a refused one
     *  leaves the clock untouched, else refreshing every few seconds postpones the sweep for ever. */
    fun claim(
        accountId: String,
        mailboxId: String,
        stateAdvanced: Boolean,
        vanishedFromMailbox: Boolean,
    ): SweepClaim {
        val k = key(accountId, mailboxId)
        val firstThisSession = sweptThisSession.add(k)
        val now = clock()
        var sweep = false
        var reason = ""
        // "Never swept" is stored as 0 rather than as an absent key, so `compute` has one shape.
        lastSweep.compute(k) { _, last ->
            val since = now - (last ?: 0L)
            sweep = shouldSweepGhosts(firstThisSession, stateAdvanced, vanishedFromMailbox, since, minIntervalMs)
            reason = sweepReason(firstThisSession, stateAdvanced, vanishedFromMailbox, since, minIntervalMs)
            if (sweep) now else (last ?: 0L)
        }
        return SweepClaim(sweep = sweep, reason = reason, firstThisSession = firstThisSession)
    }

    /** The claimed sweep never landed: hand back the once-per-process credit so the next sync
     *  retries. The floor stamp stays consumed, so a retry waits one interval (#107). */
    fun releaseFailed(accountId: String, mailboxId: String, claim: SweepClaim) {
        if (claim.firstThisSession) sweptThisSession.remove(key(accountId, mailboxId))
    }
}

internal data class SweepClaim(
    val sweep: Boolean,
    val reason: String,
    val firstThisSession: Boolean,
)
