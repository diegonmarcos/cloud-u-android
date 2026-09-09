package app.sterna.push

import android.content.Context
import java.util.UUID

/** Lifecycle of an account's UnifiedPush transport. */
enum class UpStatus {
    /** Never registered (or torn down). */
    NONE,

    /** UnifiedPush.register sent; waiting for the distributor's endpoint. */
    REGISTERING,

    /** PushSubscription created; waiting for the server's PushVerification. */
    VERIFYING,

    /**
     * A relay address is published for this account (IMAP): the endpoint exists and is shown to
     */
    PUBLISHED,

    /** Verified — StateChange events flow through the endpoint. */
    ACTIVE,

    /** Registration or verification failed; the direct connection covers the account. */
    FAILED,
}

/** One account's UnifiedPush transport state. */
data class UpAccountState(
    /** Stable per-(install, account) id sent as the subscription's deviceClientId. */
    val deviceClientId: String,
    val endpoint: String? = null,
    val subscriptionId: String? = null,
    val status: UpStatus = UpStatus.NONE,
    /** When the subscription lapses server-side (renewed by the periodic worker). */
    val expiresAtMillis: Long = 0,
    /** When [status] last changed — drives the stale-pending watchdog. */
    val statusSinceMillis: Long = 0,
    /** The user asked for a relay address on this (IMAP) account; no push without it. */
    val relayRequested: Boolean = false,
    /** When a payload last arrived through the relay endpoint (0 = never). */
    val lastDeliveryMillis: Long = 0,
)

/**
 * Device-local UnifiedPush state, one record per account id, in a dedicated prefs file
 */
class UnifiedPushStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("unifiedpush_state", Context.MODE_PRIVATE)

    fun load(accountId: String): UpAccountState? {
        val deviceClientId = prefs.getString("$accountId.device", null) ?: return null
        return UpAccountState(
            deviceClientId = deviceClientId,
            endpoint = prefs.getString("$accountId.endpoint", null),
            subscriptionId = prefs.getString("$accountId.sub", null),
            status = UpStatus.entries.firstOrNull { it.name == prefs.getString("$accountId.status", null) }
                ?: UpStatus.NONE,
            expiresAtMillis = prefs.getLong("$accountId.expires", 0),
            statusSinceMillis = prefs.getLong("$accountId.since", 0),
            relayRequested = prefs.getBoolean("$accountId.relay", false),
            lastDeliveryMillis = prefs.getLong("$accountId.delivered", 0),
        )
    }

    fun getOrCreate(accountId: String): UpAccountState =
        load(accountId) ?: UpAccountState(deviceClientId = UUID.randomUUID().toString())
            .also { save(accountId, it) }

    fun save(accountId: String, state: UpAccountState) {
        prefs.edit()
            .putString("$accountId.device", state.deviceClientId)
            .putString("$accountId.endpoint", state.endpoint)
            .putString("$accountId.sub", state.subscriptionId)
            .putString("$accountId.status", state.status.name)
            .putLong("$accountId.expires", state.expiresAtMillis)
            .putLong("$accountId.since", state.statusSinceMillis)
            .putBoolean("$accountId.relay", state.relayRequested)
            .putLong("$accountId.delivered", state.lastDeliveryMillis)
            .apply()
    }

    /** Drop an account's record (sign-out / teardown). */
    fun clear(accountId: String) {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith("$accountId.") }.forEach { edit.remove(it) }
        edit.apply()
    }
}
