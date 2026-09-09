package app.sterna.push

import android.content.Context
import android.util.Log
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AccountStore
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.jmap.model.PushKeys
import app.sterna.core.jmap.model.PushMessagePayload
import app.sterna.core.jmap.model.PushSubscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.keys.DefaultKeyManager
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import java.time.Instant

/**
 * The UnifiedPush transport state machine (#17), one instance per app. Instance ids ARE local
 */
class UnifiedPushManager(
    private val context: Context,
    private val accountStore: AccountStore,
    private val repo: MailRepository,
    private val store: UnifiedPushStateStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Invoked whenever an account's transport state changes so the owner can re-evaluate
     *  connections. A callback rather than a reference, to avoid a circular dependency. */
    var onTransportStateChanged: (() -> Unit)? = null

    private val _needsDistributorChoice = MutableStateFlow(false)

    /** True while >1 distributor is installed and none chosen — the UI shows the picker. */
    val needsDistributorChoice: StateFlow<Boolean> = _needsDistributorChoice.asStateFlow()

    fun distributorInstalled(): Boolean = UnifiedPush.getDistributors(context).isNotEmpty()

    /** Make sure a distributor is chosen: one installed is saved silently, several with none saved
     *  raises the picker — the only case with UI. False while no usable choice exists. */
    private fun ensureDistributor(): Boolean {
        if (UnifiedPush.getAckDistributor(context) != null) return true
        if (UnifiedPush.getSavedDistributor(context) != null) return true
        val distributors = UnifiedPush.getDistributors(context)
        return when {
            distributors.isEmpty() -> false
            distributors.size == 1 -> {
                UnifiedPush.saveDistributor(context, distributors.single())
                true
            }
            else -> {
                _needsDistributorChoice.value = true
                false
            }
        }
    }

    fun distributorChosen(packageName: String) {
        UnifiedPush.saveDistributor(context, packageName)
        _needsDistributorChoice.value = false
        onTransportStateChanged?.invoke()
    }

    /** Dialog dismissed without a choice: stay on direct connections (re-asked next apply). */
    fun dismissDistributorChoice() {
        _needsDistributorChoice.value = false
    }

    // A linked sub-account rides its login's one PushSubscription (#31), so its status is the
    // login's.
    fun isActive(accountId: String): Boolean =
        store.load(accountStore.account(accountId)?.loginKey() ?: accountId)?.status == UpStatus.ACTIVE

    /** Reality check before transport decisions: the saved distributor was uninstalled while
     *  accounts rode it, so mark them FAILED and let their direct connections resume. Called by the
     *  controller and the worker themselves, hence no [onTransportStateChanged] here. */
    fun reconcileDistributorPresence() {
        val saved = UnifiedPush.getAckDistributor(context) ?: UnifiedPush.getSavedDistributor(context)
        if (saved != null && saved in UnifiedPush.getDistributors(context)) return
        // The connector lookups above stay OUTSIDE the monitor; only read-decide-write is locked.
        val credentials = accountStore.allCredentials()
        synchronized(this) {
            credentials.forEach { account ->
                store.load(account.id)
                    ?.takeIf { it.status != UpStatus.NONE && it.status != UpStatus.FAILED }
                    ?.let {
                        store.save(
                            account.id,
                            it.copy(status = UpStatus.FAILED, statusSinceMillis = System.currentTimeMillis()),
                        )
                    }
            }
        }
    }

    /** Registration/verification in flight and not stale — the EventSource stays up meanwhile. */
    fun isPending(accountId: String): Boolean {
        val state = store.load(accountId) ?: return false
        return (state.status == UpStatus.REGISTERING || state.status == UpStatus.VERIFYING) &&
            System.currentTimeMillis() - state.statusSinceMillis < RelayPush.PENDING_GRACE_MS
    }

    /** Saved distributor's package name while this account rides UnifiedPush (status line). */
    fun distributorLabel(): String? = UnifiedPush.getAckDistributor(context)
        ?: UnifiedPush.getSavedDistributor(context)

    fun distributors(): List<String> = UnifiedPush.getDistributors(context)

    /** Drive the account towards ACTIVE: register when NONE/FAILED, re-register when a pending
     *  state went stale (a lazy watchdog, no timers). The decision is [RelayPush.shouldRegister];
     *  everything left here is side effects. */
    @Synchronized
    fun ensureRegistered(credentials: AccountCredentials) {
        // Only the login holds a PushSubscription; its sub-accounts (#31) are fanned to on delivery.
        if (accountStore.account(credentials.id)?.isLinked == true) return
        // Read, don't create: an IMAP account nobody asked a relay for must leave no trace here,
        // and must not reach ensureDistributor(), whose picker is this subsystem's only UI.
        val known = store.load(credentials.id)
        if (!RelayPush.mayUsePush(credentials.protocol, known?.relayRequested ?: false)) return
        // ensureDistributor() runs BEFORE the registration window: it also enrols a single
        // installed distributor and raises the picker, and behind shouldRegister an account just
        // marked FAILED stopped reaching it for the whole cooldown.
        if (!ensureDistributor()) return
        if (!RelayPush.shouldRegister(
                protocol = credentials.protocol,
                relayRequested = known?.relayRequested ?: false,
                status = known?.status ?: UpStatus.NONE,
                statusSinceMillis = known?.statusSinceMillis ?: 0L,
                now = System.currentTimeMillis(),
            )
        ) {
            return
        }
        val state = store.getOrCreate(credentials.id)
        store.save(credentials.id, state.copy(status = UpStatus.REGISTERING, statusSinceMillis = System.currentTimeMillis()))
        val relay = credentials.protocol != MailProtocol.JMAP
        scope.launch {
            // VAPID (RFC 9749) when the server advertises it. A relay account must not ask:
            // pushVapidKey is a JMAP call, and its host speaks IMAP.
            val vapid = if (relay) null else runCatching { repo.pushVapidKey(credentials) }.getOrNull()
            UnifiedPush.register(context, credentials.id, null, vapid)
        }
    }

    /**
     * The user asked for a relay address on this account. [RelayPush.onRelayRequested] puts the
     */
    fun requestRelayAddress(credentials: AccountCredentials) {
        synchronized(this) {
            val state = store.getOrCreate(credentials.id)
            store.save(credentials.id, RelayPush.onRelayRequested(state, System.currentTimeMillis()))
        }
        ensureRegistered(credentials)
    }

    /**
     * The user withdrew the relay address: forget the endpoint, then unregister the instance.
     */
    fun dropRelayAddress(credentials: AccountCredentials) {
        synchronized(this) {
            val state = store.load(credentials.id) ?: return
            if (state.subscriptionId != null) {
                Log.w(TAG, "dropRelayAddress on an account holding a subscription — ignored: ${credentials.id}")
                return
            }
            store.save(
                credentials.id,
                state.copy(
                    endpoint = null,
                    subscriptionId = null,
                    status = UpStatus.NONE,
                    statusSinceMillis = System.currentTimeMillis(),
                    relayRequested = false,
                    lastDeliveryMillis = 0,
                ),
            )
        }
        UnifiedPush.unregister(context, credentials.id)
        onTransportStateChanged?.invoke()
    }

    fun relayRequested(accountId: String): Boolean = store.load(accountId)?.relayRequested == true

    /** The published relay address, or null unless there is a live one to show —
     *  [RelayPush.publishedAddress] holds the rule. */
    fun relayEndpoint(accountId: String): String? = RelayPush.publishedAddress(store.load(accountId))

    fun relayLastDeliveryMillis(accountId: String): Long = store.load(accountId)?.lastDeliveryMillis ?: 0

    /**
     * Is this account served by an ARMED relay — one the user asked for, holding no PushSubscription
     */
    fun relayArmed(accountId: String): Boolean {
        val state = store.load(accountId) ?: return false
        return state.relayRequested && state.subscriptionId == null && state.status == UpStatus.ACTIVE
    }

    fun onNewEndpoint(accountId: String, endpoint: PushEndpoint) {
        val credentials = credentialsFor(accountId) ?: return unregisterOrphan(accountId)
        scope.launch {
            // The connector redelivers the endpoint on every register, so the no-change case has to
            // be recognised; a relay account stops at its own state. Read, decide and write in
            // ONE block, under ensureRegistered's monitor: read outside the lock and a withdrawal
            // landing in between is overwritten, the removed address coming back published.
            val (prev, outcome) = synchronized(this@UnifiedPushManager) {
                val current = store.getOrCreate(accountId)
                val decided = RelayPush.endpointOutcome(
                    credentials.protocol, current, endpoint.url, System.currentTimeMillis(),
                )
                if (decided is EndpointOutcome.Relay) store.save(accountId, decided.state)
                current to decided
            }
            when (outcome) {
                EndpointOutcome.Ignore -> return@launch
                is EndpointOutcome.Relay -> {
                    Log.i(TAG, "Relay address published for $accountId")
                    onTransportStateChanged?.invoke()
                    return@launch
                }
                EndpointOutcome.Subscribe -> Unit
            }
            if (prev.subscriptionId != null) {
                // Rotation or a lapsed verification: the old subscription is dead — drop it.
                runCatching { repo.destroyPushSubscription(credentials, prev.subscriptionId) }
            }
            val keys = endpoint.pubKeySet
            if (keys == null) {
                // Without WebPush keys the server can't encrypt; stay on the direct connection.
                Log.w(TAG, "UnifiedPush endpoint without WebPush keys for $accountId")
                markFailed(accountId)
                return@launch
            }
            runCatching {
                val created = repo.createPushSubscription(
                    credentials,
                    PushSubscription(
                        deviceClientId = prev.deviceClientId,
                        url = endpoint.url,
                        // RFC 8291 wants base64url; the connector's encoding varies by version,
                        // and Stalwart rejects the standard alphabet outright.
                        keys = PushKeys(p256dh = base64Url(keys.pubKey), auth = base64Url(keys.auth)),
                        expires = utc(System.currentTimeMillis() + EXPIRES_MS),
                        types = listOf("Email"),
                    ),
                )
                store.save(
                    accountId,
                    prev.copy(
                        endpoint = endpoint.url,
                        subscriptionId = created.id,
                        status = UpStatus.VERIFYING,
                        statusSinceMillis = System.currentTimeMillis(),
                        expiresAtMillis = parseUtc(created.expires)
                            ?: (System.currentTimeMillis() + EXPIRES_MS),
                    ),
                )
                Log.i(TAG, "PushSubscription created for $accountId, awaiting verification")
            }.onFailure {
                Log.w(TAG, "PushSubscription create failed for $accountId", it)
                markFailed(accountId)
            }
        }
    }

    fun onMessage(accountId: String, message: PushMessage) {
        when (val payload = decodePayload(accountId, message)) {
            is PushMessagePayload.Verification -> {
                // A relay address is a topic ANYONE may post to. Without this guard a stranger
                // posts {"@type":"PushVerification"} and it is carried into verifyPushSubscription
                // with the account's credentials against an IMAP host: the failure calls markFailed,
                // so that stranger switches the relay off for fifteen minutes at will.
                //
                // The question is the PROTOCOL, not the stored subscriptionId, which is written
                // only once createPushSubscription has returned — a guard on it would depend on
                // which of two messages wins a race.
                val credentials = credentialsFor(accountId) ?: return unregisterOrphan(accountId)
                if (credentials.protocol != MailProtocol.JMAP) {
                    Log.w(TAG, "Push verification on a relay account — ignored: $accountId")
                    return
                }
                scope.launch {
                    // Promptly: servers time the round-trip out (Stalwart: ~1 min).
                    runCatching {
                        repo.verifyPushSubscription(credentials, payload.pushSubscriptionId, payload.verificationCode)
                    }.onSuccess {
                        store.save(
                            accountId,
                            store.getOrCreate(accountId).copy(status = UpStatus.ACTIVE, statusSinceMillis = System.currentTimeMillis()),
                        )
                        Log.i(TAG, "UnifiedPush ACTIVE for $accountId")
                        onTransportStateChanged?.invoke()
                    }.onFailure {
                        Log.w(TAG, "PushSubscription verify failed for $accountId", it)
                        markFailed(accountId)
                    }
                }
            }
            // One PushSubscription per login; fan the wake out to the login and every sub-account
            // sharing it (#31). For linked sub-accounts this is a catch-up ride, their guaranteed
            // delivery being the periodic poll.
            is PushMessagePayload.Change -> {
                recordDelivery(accountId)
                enqueueForLogin(accountId)
            }
            // Unknown/undecryptable payload: treat as a bare wake signal — still fetch.
            null -> {
                // Log.i, not d: release builds strip d/v, and this is the evidence when push
                // misbehaves on a distributor I cannot test.
                Log.i(TAG, "Unparsed push payload (${message.content.size}B, decrypted=${message.decrypted})")
                recordDelivery(accountId)
                enqueueForLogin(accountId)
            }
        }
    }

    /** Mail really came through this account's endpoint — the only thing that arms a relay
     *  ([RelayPush.onDelivery] holds the rule). Called from the two arms that carry mail, never
     *  from Verification. */
    private fun recordDelivery(accountId: String) {
        // Read, decide and write under ensureRegistered's monitor. This is the only write that
        // arms the transport, and it runs on the connector's thread while a rotation writes from
        // Dispatchers.IO: outside the lock, a payload on the OLD address persists over the
        // rotation. The callback below stays outside — it re-enters the manager.
        val update = synchronized(this) {
            val prev = store.load(accountId) ?: return
            val decided = RelayPush.onDelivery(prev, System.currentTimeMillis()) ?: return
            store.save(accountId, decided.state)
            decided
        }
        // Only the PUBLISHED → ACTIVE crossing: this callback rebuilds every connection.
        if (update.armed) {
            Log.i(TAG, "UnifiedPush relay armed by a delivery for $accountId")
            onTransportStateChanged?.invoke()
        }
    }

    /** Wake the login [loginId] and every sub-account riding its subscription (issue #31). */
    private fun enqueueForLogin(loginId: String) {
        (listOf(loginId) + accountStore.linkedAccounts(loginId).map { it.id }).distinct()
            .forEach { PushFetchWorker.enqueue(context, it) }
    }

    /** Extract the JMAP payload from a push delivery. Straight parse first, the connector having
     *  already decrypted or the server pushed plaintext; Stalwart then needs a second step, since it
     *  base64url-encodes the whole aes128gcm blob instead of POSTing raw octets (RFC 8030). */
    private fun decodePayload(accountId: String, message: PushMessage): PushMessagePayload? {
        val text = message.content.toString(Charsets.UTF_8)
        PushMessagePayload.parse(text)?.let { return it }
        if (message.decrypted) return null
        return runCatching {
            val binary = android.util.Base64.decode(
                text.trim(),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP,
            )
            DefaultKeyManager(context).decrypt(accountId, binary)
                ?.toString(Charsets.UTF_8)
                ?.let { PushMessagePayload.parse(it) }
        }.getOrNull()
    }

    fun onRegistrationFailed(accountId: String, reason: FailedReason) {
        Log.w(TAG, "UnifiedPush registration failed for $accountId: $reason")
        markFailed(accountId)
    }

    fun onUnregistered(accountId: String) {
        Log.i(TAG, "UnifiedPush unregistered for $accountId")
        // FAILED, not NONE: NONE re-registers instantly through the transport callback and
        // ping-pongs against a distributor that keeps answering with unregister.
        synchronized(this) {
            store.load(accountId)?.let {
                store.save(accountId, it.copy(status = UpStatus.FAILED, statusSinceMillis = System.currentTimeMillis()))
            }
        }
        onTransportStateChanged?.invoke()
    }

    /** Renew the subscription when it expires within two worker cycles. A failed renewal falls back
     *  to a full re-registration. */
    suspend fun renewIfNeeded(credentials: AccountCredentials) {
        val state = store.load(credentials.id) ?: return
        if (state.status != UpStatus.ACTIVE || state.subscriptionId == null) return
        if (state.expiresAtMillis - System.currentTimeMillis() > RENEW_MARGIN_MS) return
        runCatching {
            val applied = repo.renewPushSubscription(
                credentials, state.subscriptionId, utc(System.currentTimeMillis() + EXPIRES_MS),
            )
            store.save(
                credentials.id,
                state.copy(expiresAtMillis = parseUtc(applied) ?: (System.currentTimeMillis() + EXPIRES_MS)),
            )
        }.onFailure {
            Log.w(TAG, "PushSubscription renew failed for ${credentials.id} — re-registering", it)
            store.save(credentials.id, state.copy(status = UpStatus.NONE))
            ensureRegistered(credentials)
        }
    }

    /** Sign-out: unregister the instance and best-effort destroy the subscription. */
    fun teardown(credentials: AccountCredentials) {
        val state = store.load(credentials.id)
        store.clear(credentials.id)
        UnifiedPush.unregister(context, credentials.id)
        val subscriptionId = state?.subscriptionId ?: return
        scope.launch { runCatching { repo.destroyPushSubscription(credentials, subscriptionId) } }
    }

    private fun markFailed(accountId: String) {
        // Under the monitor, like every state write not inside a coroutine's own
        // read-decide-write: this one is called from failed coroutines and from the connector.
        synchronized(this) {
            store.load(accountId)?.let {
                store.save(accountId, it.copy(status = UpStatus.FAILED, statusSinceMillis = System.currentTimeMillis()))
            }
        }
        onTransportStateChanged?.invoke()
    }

    private fun unregisterOrphan(accountId: String) {
        Log.w(TAG, "UnifiedPush event for unknown account $accountId — unregistering")
        store.clear(accountId)
        UnifiedPush.unregister(context, accountId)
    }

    private fun credentialsFor(accountId: String): AccountCredentials? =
        accountStore.allCredentials().firstOrNull { it.id == accountId }

    /** Re-encode any base64 variant as base64url WITH padding: Stalwart's decoder requires canonical
     *  padding and rejects the RFC 7515-style unpadded form. */
    private fun base64Url(value: String): String {
        val bytes = try {
            android.util.Base64.decode(value, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            android.util.Base64.decode(value, android.util.Base64.NO_WRAP)
        }
        return android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP,
        )
    }

    private fun utc(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    private fun parseUtc(utc: String?): Long? =
        utc?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    companion object {
        private const val TAG = "UnifiedPush"

        /** Requested subscription lifetime; the server may cap it (we store what it applied). */
        private const val EXPIRES_MS = 7L * 24 * 60 * 60 * 1000

        /** Renew when expiring within two periodic-worker cycles. */
        private const val RENEW_MARGIN_MS = 2 * 30 * 60 * 1000L

    }
}
