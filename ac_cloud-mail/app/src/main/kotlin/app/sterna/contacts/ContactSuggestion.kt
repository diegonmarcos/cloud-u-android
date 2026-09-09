package app.sterna.contacts

import app.sterna.core.data.db.ContactRow

/**
 * One row of the recipient autocomplete menu: an address, the name to show above it, and — only
 */
data class ContactSuggestion(
    val email: String,
    val name: String?,
    /** Contact thumbnail URI, or null when the row has no photo (then a monogram is drawn). */
    val photoUri: String? = null,
)

/**
 * Merges the three suggestion sources into the list the menu shows.
 */
fun mergeSuggestions(
    local: List<ContactRow>,
    device: List<ContactSuggestion>,
    limit: Int,
): List<ContactSuggestion> {
    val byEmail = HashMap<String, ContactSuggestion>(device.size)
    for (d in device) byEmail.putIfAbsent(d.email.lowercase(), d)
    val merged = ArrayList<ContactSuggestion>(local.size + device.size)
    for (row in local) {
        val match = byEmail[row.email.lowercase()]
        merged += ContactSuggestion(
            email = row.email,
            name = row.name ?: match?.name,
            photoUri = match?.photoUri,
        )
    }
    merged += device
    return merged.distinctBy { it.email.lowercase() }.take(limit)
}
