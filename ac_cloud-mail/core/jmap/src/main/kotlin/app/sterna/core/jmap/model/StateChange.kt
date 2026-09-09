package app.sterna.core.jmap.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A JMAP StateChange event delivered over EventSource/WebSocket (RFC 8620 §7.1):
 * which data types changed, per account.
 */
@Serializable
data class StateChange(
    @SerialName("@type") val type: String = "StateChange",
    val changed: Map<String, Map<String, String>> = emptyMap(),
) {
    /** True if the given account's Email state changed (new/updated mail). */
    fun emailChanged(accountId: String): Boolean =
        changed[accountId]?.containsKey("Email") == true
}
