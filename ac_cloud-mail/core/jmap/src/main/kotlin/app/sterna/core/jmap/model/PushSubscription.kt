package app.sterna.core.jmap.model

import kotlinx.serialization.Serializable

/** Client WebPush keys handed to the server so it can encrypt pushes (RFC 8291). */
@Serializable
data class PushKeys(
    val p256dh: String,
    val auth: String,
)

/**
 * A JMAP PushSubscription (RFC 8620 §7.2). Session-level: it belongs to the
 * credential, not to an account, so calls carry no accountId.
 */
@Serializable
data class PushSubscription(
    val id: String? = null,
    val deviceClientId: String,
    val url: String,
    val keys: PushKeys? = null,
    val verificationCode: String? = null,
    /** UTCDate; the server may cap the requested value and returns the one applied. */
    val expires: String? = null,
    /** Data types that trigger a push; null = all types. */
    val types: List<String>? = null,
)
