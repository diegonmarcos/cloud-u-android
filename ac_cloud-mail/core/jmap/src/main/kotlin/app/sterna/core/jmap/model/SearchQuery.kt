package app.sterna.core.jmap.model

    /**
     * A structured mail search. Non-blank/non-null fields are combined with AND into a JMAP
     */
data class SearchQuery(
    val text: String = "",
    val from: String = "",
    val recipient: String = "",
    val subject: String = "",
    val hasAttachment: Boolean = false,
    val flagged: Boolean = false,
    val afterMillis: Long? = null,
    val beforeMillis: Long? = null,
) {
    // [flagged] counts: a search whose ONLY criterion is "flagged" is a legitimate search (show me
    // my starred mail), and treating it as empty would leave the Search button doing nothing.
    fun isEmpty(): Boolean =
        text.isBlank() && from.isBlank() && recipient.isBlank() && subject.isBlank() &&
            !hasAttachment && !flagged && afterMillis == null && beforeMillis == null
}
