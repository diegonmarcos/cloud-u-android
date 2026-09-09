package app.sterna.core.jmap.model

import kotlinx.serialization.Serializable

/**
 * A JMAP Quota object (RFC 9425 "JMAP for Quotas"): how much of a server-side
 */
@Serializable
data class Quota(
    val id: String,
    val resourceType: String = "octets",
    val used: Long = 0,
    val limit: Long? = null,
    val scope: String = "account",
    val name: String = "",
    val types: List<String> = emptyList(),
    val warnLimit: Long? = null,
    val softLimit: Long? = null,
    val description: String? = null,
)
