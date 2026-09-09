package app.sterna.core.data.db

import app.sterna.core.data.pgp.PgpMode
import app.sterna.core.jmap.JmapException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/**
 * Pure outbox decisions, kept out of the worker/UI so they can be unit-tested on the JVM:
 * when an item is due to send, what happens after a failed attempt, and how the badge counts.
 */
object OutboxLogic {
    /** Auto-retry attempts before an item is parked as FAILED for manual handling. */
    const val MAX_ATTEMPTS = 5

    /**
     * The `lastError` a row carries when its edit was INTERRUPTED. A sentinel, translated at
     */
    const val EDIT_INTERRUPTED = "edit-interrupted"

    /**
     * The `lastError` a row carries when its delivery could not be ARMED (`armOrNote`): the row is
     */
    const val NOT_ARMED = "not-armed"

    /**
     * The historic opening of what 1.5.2/1.5.3 wrote into `outbox.lastError` when arming failed.
     */
    const val NOT_ARMED_LEGACY_PREFIX = "Queued, but delivery could not be armed:"

    /**
     * A HELD item is due once its undo window has passed; QUEUED is always due. A scheduled send
     */
    fun isReadyToSend(state: OutboxState, notBeforeMillis: Long, now: Long): Boolean = when (state) {
        OutboxState.HELD -> now >= notBeforeMillis
        OutboxState.QUEUED -> true
        // Already begun (see above), parked for manual handling (#70), saved as a draft on the
        // phone (#95) or open in the composer. KEPT_AS_DRAFT would be a data loss, not a nuisance.
        OutboxState.SENDING, OutboxState.FAILED, OutboxState.KEPT_AS_DRAFT, OutboxState.EDITING,
        OutboxState.INTERRUPTED,
        -> false
    }

    /**
     * The state the send worker must PARK the row it has just read in, or null when the delivery
     */
    fun parkOnPickup(state: OutboxState): OutboxState? = when (state) {
        OutboxState.SENDING -> OutboxState.INTERRUPTED
        // Nothing begun, nothing to park. FAILED, KEPT_AS_DRAFT, EDITING and INTERRUPTED are
        // already parked, and re-parking would overwrite what they say.
        OutboxState.HELD, OutboxState.QUEUED, OutboxState.FAILED,
        OutboxState.KEPT_AS_DRAFT, OutboxState.EDITING, OutboxState.INTERRUPTED,
        -> null
    }

    /** After a failed attempt: retry while under the cap, otherwise give up (park as FAILED). */
    fun shouldRetry(attemptCount: Int): Boolean = attemptCount < MAX_ATTEMPTS

    /**
     * The state an outbox row goes to after a failed delivery attempt. A [JmapException] carrying
     */
    fun stateAfterFailure(attemptCount: Int, failure: Throwable): OutboxState = when {
        failure is JmapException && failure.permanent -> OutboxState.FAILED
        shouldRetry(attemptCount) -> OutboxState.QUEUED
        else -> OutboxState.FAILED
    }

    /**
     * How many attempts the row is written back with — on a PERMANENT refusal the CAP, not
     */
    fun attemptsAfterFailure(previousAttempts: Int, failure: Throwable): Int =
        if (failure is JmapException && failure.permanent) maxOf(MAX_ATTEMPTS, previousAttempts + 1)
        else previousAttempts + 1

    /**
     * The state a reopened item returns to once its edit ends. An item whose auto-retry was
     */
    fun stateAfterEdit(attemptCount: Int): OutboxState =
        if (attemptCount >= MAX_ATTEMPTS) OutboxState.FAILED else OutboxState.QUEUED

    /**
     * Whether an outbox item can be reopened in the composer. Three things must hold.
     */
    fun canEdit(pgpMode: String?, state: OutboxState, carriesUnreplayablePrebuiltEntity: Boolean): Boolean =
        !carriesUnreplayablePrebuiltEntity && !pgpMode.equals("ENCRYPT", ignoreCase = true) &&
            when (state) {
                OutboxState.QUEUED, OutboxState.HELD, OutboxState.FAILED -> true
                OutboxState.SENDING, OutboxState.EDITING, OutboxState.INTERRUPTED, OutboxState.KEPT_AS_DRAFT -> false
            }

    /**
     * What goes in [OutboxEntity.pgpMode] for a message being queued — a string, deliberately not
     */
    fun outboxPgpModeName(mode: PgpMode?): String? = when (mode) {
        null, PgpMode.OFF -> null
        PgpMode.ENCRYPT, PgpMode.ENCRYPT_UNSIGNED -> "ENCRYPT"
        PgpMode.SIGN -> "SIGN"
    }

    /**
     * Whether a row's pre-built entity is one the composer could not produce again — the single
     */
    fun isUnreplayablePrebuiltEntity(pgpMode: String?, entityPath: String?): Boolean =
        entityPath != null && pgpMode == null

    /**
     * How long a message on its way stays off the badge past the instant it was allowed to go
     */
    const val BADGE_GRACE_MILLIS = 30_000L

    /**
     * Items shown on the badge. FAILED counts at once; anything still on its way counts only
     */
    fun activeCount(items: List<OutboxBadgeItem>, now: Long): Int = items.count { countsAt(it, now) }

    /** Whether one row belongs on the badge at [now]; see [activeCount] for the reasoning. */
    private fun countsAt(item: OutboxBadgeItem, now: Long): Boolean = when (item.state) {
        // An item open in the composer (#70) is being handled right now, not waiting to send.
        OutboxState.EDITING -> false
        // All three count at once: none is on its way anywhere. KEPT_AS_DRAFT is not an alarm — it
        // is the only thing on screen saying where that message went (#95), which is why it counts
        // here while staying off the failure banner.
        OutboxState.FAILED, OutboxState.KEPT_AS_DRAFT, OutboxState.INTERRUPTED -> true
        OutboxState.HELD, OutboxState.QUEUED, OutboxState.SENDING ->
            now >= item.notBeforeMillis + BADGE_GRACE_MILLIS
    }

    /**
     * Whether a row is one the Outbox screen lists — everything except a row open in the composer.
     * The single predicate behind both `MailRepository.outboxFlow` and [queuedCount].
     */
    fun isWaitingInOutbox(state: OutboxState): Boolean = state != OutboxState.EDITING

    /**
     * Messages sitting in the Outbox right now, with NO grace at all — deliberately not
     */
    fun queuedCount(items: List<OutboxBadgeItem>): Int = items.count { isWaitingInOutbox(it.state) }

    /**
     * When the badge must next be recomputed on its own: the earliest counting threshold still
     * ahead. FAILED already counts and EDITING never does, so neither has a wake-up to schedule.
     */
    fun nextBadgeChange(items: List<OutboxBadgeItem>, now: Long): Long? = items
        .filter { countsOnlyAfterTheGrace(it.state) }
        .map { it.notBeforeMillis + BADGE_GRACE_MILLIS }
        .filter { it > now }
        .minOrNull()

    /**
     * Whether a row's place on the badge still depends on the clock — the only rows with a wake-up
     * to schedule. Exhaustive rather than a list of `!=` so a new state has to answer out loud.
     */
    private fun countsOnlyAfterTheGrace(state: OutboxState): Boolean = when (state) {
        OutboxState.HELD, OutboxState.QUEUED, OutboxState.SENDING -> true
        // These four are already settled: FAILED, KEPT_AS_DRAFT and INTERRUPTED count from the
        // first emission, EDITING never counts. See [countsAt].
        OutboxState.FAILED, OutboxState.KEPT_AS_DRAFT, OutboxState.EDITING,
        OutboxState.INTERRUPTED,
        -> false
    }

    /**
     * The badge count over time. Room re-emits on every outbox change, and each emission schedules
     * exactly one wake-up ([nextBadgeChange]) — no polling, nothing to reset.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun badgeCount(
        items: Flow<List<OutboxBadgeItem>>,
        now: () -> Long = System::currentTimeMillis,
    ): Flow<Int> = items
        .flatMapLatest { rows ->
            flow {
                while (true) {
                    val instant = now()
                    emit(activeCount(rows, instant))
                    val next = nextBadgeChange(rows, instant) ?: break
                    delay(next - instant)
                }
            }
        }
        .distinctUntilChanged()

    /**
     * Whether one row is what the Inbox's "Some messages didn't send" banner is about — a genuine
     */
    fun needsFailureBanner(state: OutboxState): Boolean = when (state) {
        OutboxState.FAILED -> true
        OutboxState.HELD, OutboxState.QUEUED, OutboxState.SENDING,
        OutboxState.KEPT_AS_DRAFT, OutboxState.EDITING, OutboxState.INTERRUPTED,
        -> false
    }

    /** Items needing the failure banner. */
    fun failedCount(states: List<OutboxState>): Int = states.count { needsFailureBanner(it) }
}
