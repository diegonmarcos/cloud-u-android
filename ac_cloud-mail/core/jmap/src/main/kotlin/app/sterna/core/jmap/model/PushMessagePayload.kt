package app.sterna.core.jmap.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A payload the server POSTs to the push endpoint (RFC 8620 §7.2): the one-time
 */
sealed interface PushMessagePayload {

    data class Verification(
        val pushSubscriptionId: String,
        val verificationCode: String,
    ) : PushMessagePayload

    data class Change(val stateChange: StateChange) : PushMessagePayload

    companion object {
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        /** Parse a decrypted push payload; null when it is neither known type. */
        fun parse(text: String): PushMessagePayload? {
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            return when (root["@type"]?.jsonPrimitive?.contentOrNull) {
                "PushVerification" -> {
                    val id = root["pushSubscriptionId"]?.jsonPrimitive?.contentOrNull ?: return null
                    val code = root["verificationCode"]?.jsonPrimitive?.contentOrNull ?: return null
                    Verification(id, code)
                }
                "StateChange" -> runCatching {
                    Change(json.decodeFromJsonElement(StateChange.serializer(), root))
                }.getOrNull()
                else -> null
            }
        }
    }
}
