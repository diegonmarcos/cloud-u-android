package app.sterna.core.data.filter

import kotlinx.serialization.Serializable

/** Which message field a rule tests. */
@Serializable
enum class RuleField { FROM, TO, CC, SUBJECT }

/** How the field is compared to the value. */
@Serializable
enum class RuleMatch { CONTAINS, IS }

/**
 * One server-side filter rule, as edited in the form UI. Compiled to Sieve by
 */
@Serializable
data class FilterRule(
    val name: String = "",
    val enabled: Boolean = true,
    val field: RuleField = RuleField.FROM,
    val match: RuleMatch = RuleMatch.CONTAINS,
    val value: String = "",
    /** Target mailbox name to file into, or null for no move. */
    val moveTo: String? = null,
    val markRead: Boolean = false,
    val flag: Boolean = false,
) {
    /**
     * True for an untouched rule: no name, no match value and no action. Such a
     */
    val isEmpty: Boolean
        get() = name.isBlank() && value.isBlank() && moveTo == null && !markRead && !flag
}
