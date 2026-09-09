package app.sterna.core.data.mail

/** The two bounds of a notification pass. `NewMailNotifier.seed` replaces the folder's baseline
 *  rather than unioning it, so whatever bounds that baseline must be a bound a row can only fall
 *  out of: [notifyCandidateFloor] is a wall-clock instant, which only moves forward. */

/** Age floor slack: never announce mail received more than this before the folder's last pass. */
const val NOTIFY_HORIZON_MS: Long = 24L * 60 * 60 * 1000

/** How far back a pass reads, and the only bound on the baseline; wider than [NOTIFY_HORIZON_MS] on purpose. */
const val NOTIFY_CANDIDATE_WINDOW_MS: Long = 30L * 24 * 60 * 60 * 1000

/** Most rows one pass hydrates for one folder. Memory only: the baseline does not go through it. */
const val NOTIFY_CANDIDATE_MAX: Int = 500

/** The oldest `receivedAt` still announceable; no floor at all when the folder has no recorded pass. */
fun notifyFloor(lastPassMs: Long): Long =
    if (lastPassMs > 0) lastPassMs - NOTIFY_HORIZON_MS else Long.MIN_VALUE

/** The oldest `sortKey` a pass reads at [nowMs], for both of its reads. Monotone in [nowMs]. */
fun notifyCandidateFloor(nowMs: Long): Long = nowMs - NOTIFY_CANDIDATE_WINDOW_MS

/** Whether [receivedAtIso] is still past the floor [floorMs]. An absent or unparseable date
 *  passes, so the candidate read keeps those rows too (`EmailDao.receivedSince` admits 0). */
fun announceableAt(receivedAtIso: String?, floorMs: Long): Boolean {
    if (floorMs == Long.MIN_VALUE) return true
    val received = receivedAtIso ?: return true
    return runCatching { java.time.Instant.parse(received).toEpochMilli() >= floorMs }.getOrDefault(true)
}
