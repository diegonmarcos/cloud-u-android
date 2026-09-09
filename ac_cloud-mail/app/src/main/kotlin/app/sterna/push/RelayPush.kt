package app.sterna.push

import app.sterna.core.data.account.MailProtocol

/** The two UnifiedPush decisions, as pure functions: whether an account may register now, and what
 *  an arriving endpoint means. [UnifiedPushManager] keeps every side effect, which is the only
 *  reason this subsystem can be tested at all. */
object RelayPush {

    /** How long REGISTERING/VERIFYING counts as in-flight before the watchdog retries. */
    const val PENDING_GRACE_MS = 2 * 60 * 1000L

    /** Cooldown before a FAILED account may re-register (worker cycles retry for us). */
    const val FAILED_RETRY_MS = 15 * 60 * 1000L

    /**
     * May this account send a UnifiedPush.register right now? Three windows: never registered, a
     */
    fun shouldRegister(
        protocol: MailProtocol,
        relayRequested: Boolean,
        status: UpStatus,
        statusSinceMillis: Long,
        now: Long,
    ): Boolean {
        if (!mayUsePush(protocol, relayRequested)) return false
        val stalePending = (status == UpStatus.REGISTERING || status == UpStatus.VERIFYING) &&
            now - statusSinceMillis >= PENDING_GRACE_MS
        val failedRetryDue = status == UpStatus.FAILED && now - statusSinceMillis >= FAILED_RETRY_MS
        return status == UpStatus.NONE || failedRetryDue || stalePending
    }

    /**
     * May this account touch the UnifiedPush subsystem AT ALL? JMAP always may; anything else only
     */
    fun mayUsePush(protocol: MailProtocol, relayRequested: Boolean): Boolean =
        protocol == MailProtocol.JMAP || relayRequested

    /**
     * The user tapped "Get an address": what to persist before registering. The status goes back
     */
    fun onRelayRequested(prev: UpAccountState, now: Long): UpAccountState =
        prev.copy(relayRequested = true, status = UpStatus.NONE, statusSinceMillis = now)

    /** The address this account may SHOW, or null. Not simply the stored endpoint: `markFailed`
     *  and `onUnregistered` keep that field, so returning it whatever the status puts a dead address
     *  on screen as a live one — one the user pastes into a relay that posts to it for ever. */
    fun publishedAddress(state: UpAccountState?): String? {
        if (state == null || !state.relayRequested) return null
        if (state.status != UpStatus.PUBLISHED && state.status != UpStatus.ACTIVE) return null
        return state.endpoint
    }

    /** What the distributor's endpoint delivery means. The connector redelivers the endpoint on
     *  EVERY register, so the no-change case must be recognised or an armed account is knocked back
     *  on every cycle. */
    fun endpointOutcome(
        protocol: MailProtocol,
        prev: UpAccountState,
        url: String,
        now: Long,
    ): EndpointOutcome {
        if (protocol != MailProtocol.JMAP) {
            // Nobody asked for this address any more: the endpoint arrives on its own schedule, so
            // a withdrawal can lose the race to a delivery already in flight.
            if (!prev.relayRequested) return EndpointOutcome.Ignore
            // Same address, already published or armed: nothing to write, or every register would
            // drag an ACTIVE relay back down to PUBLISHED.
            if (prev.endpoint == url && (prev.status == UpStatus.PUBLISHED || prev.status == UpStatus.ACTIVE)) {
                return EndpointOutcome.Ignore
            }
            // Rotation: the address pasted into the relay just died, so the wake it earned dies
            // with it — carrying lastDeliveryMillis over would show a delivery that belonged to an
            // address nobody posts to.
            return EndpointOutcome.Relay(
                prev.copy(
                    endpoint = url,
                    subscriptionId = null,
                    status = UpStatus.PUBLISHED,
                    statusSinceMillis = now,
                    lastDeliveryMillis = 0,
                ),
            )
        }
        val pendingFresh = prev.status == UpStatus.VERIFYING && now - prev.statusSinceMillis < PENDING_GRACE_MS
        if (prev.endpoint == url && prev.subscriptionId != null &&
            (pendingFresh || prev.status == UpStatus.ACTIVE)
        ) {
            return EndpointOutcome.Ignore
        }
        return EndpointOutcome.Subscribe
    }

    /**
     * A payload really arrived through this account's endpoint. Returns what to persist, or null when
     */
    fun onDelivery(prev: UpAccountState, now: Long): DeliveryUpdate? {
        if (!prev.relayRequested || prev.subscriptionId != null) return null
        return when (prev.status) {
            // First delivery: the address is proven live. The status moved, so its timestamp moves.
            UpStatus.PUBLISHED -> DeliveryUpdate(
                prev.copy(status = UpStatus.ACTIVE, statusSinceMillis = now, lastDeliveryMillis = now),
                armed = true,
            )
            // Already armed: stamp it and nothing else, armed = false keeping the caller from
            // rebuilding every connection once per message.
            UpStatus.ACTIVE ->
                if (prev.lastDeliveryMillis == now) null
                else DeliveryUpdate(prev.copy(lastDeliveryMillis = now), armed = false)
            // No address published (or one we gave up on): nothing to arm.
            else -> null
        }
    }
}

/** What [RelayPush.onDelivery] tells the manager to write. [armed] is true on the one delivery that
 *  took the account from PUBLISHED to ACTIVE — the only one that is a transport change — carried in
 *  the return value so the caller cannot re-derive it and get it wrong. */
data class DeliveryUpdate(val state: UpAccountState, val armed: Boolean)

sealed interface EndpointOutcome {

    /** Same endpoint, account already covered by it — do nothing at all. */
    data object Ignore : EndpointOutcome

    /** Relay account: persist [state] and stop. No server call follows this. */
    data class Relay(val state: UpAccountState) : EndpointOutcome

    /** JMAP account: run the PushSubscription path (destroy, create, verify). */
    data object Subscribe : EndpointOutcome
}
