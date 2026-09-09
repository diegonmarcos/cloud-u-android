package app.sterna.core.jmap.model

import kotlinx.serialization.Serializable

/**
 * The JMAP VacationResponse singleton (RFC 8621 §8): a server-side auto-reply
 */
@Serializable
data class VacationResponse(
    val id: String = "singleton",
    val isEnabled: Boolean = false,
    val fromDate: String? = null,
    val toDate: String? = null,
    val subject: String? = null,
    val textBody: String? = null,
    val htmlBody: String? = null,
)
