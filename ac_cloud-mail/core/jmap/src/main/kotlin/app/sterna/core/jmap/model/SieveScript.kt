package app.sterna.core.jmap.model

import kotlinx.serialization.Serializable

/**
 * A JMAP SieveScript (RFC 9661 "JMAP for Sieve Scripts"): a server-side mail
 */
@Serializable
data class SieveScript(
    val id: String,
    val name: String = "",
    val blobId: String = "",
    val isActive: Boolean = false,
)
