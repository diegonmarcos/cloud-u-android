package com.diegonmarcos.cloudsearch.core.agents

/**
 * The owner's own message templates. `{{name}}` is replaced by the variable of that name, `{{name|fallback}}`
 * by the variable or, when it is missing or blank, the fallback (which may be empty). One pass: a value that
 * itself contains `{{x}}` is inserted as written and never expanded, so text read from a listing page cannot
 * pull another variable (or anything else) into a draft.
 */
object Template {
    private val SLOT = Regex("""\{\{\s*([a-z][a-z0-9_]*)\s*(?:\|([^{}]*))?\}\}""")

    /** [text] with the slots filled; [missing] names every slot that had no value and no fallback (left visible as `{{name}}`). */
    data class Rendered(val text: String, val missing: List<String>, val used: List<String>)

    fun render(template: String, vars: Map<String, String>): Rendered {
        val missing = LinkedHashSet<String>()
        val used = LinkedHashSet<String>()
        val text = SLOT.replace(template) { m ->
            val name = m.groupValues[1]
            val value = vars[name]?.takeIf { it.isNotBlank() }
            val fallback = if (m.groups[2] != null) m.groupValues[2] else null
            when {
                value != null -> { used += name; value }
                fallback != null -> fallback
                else -> { missing += name; m.value }
            }
        }
        return Rendered(text, missing.toList(), used.toList())
    }

    /** Every variable a template names, in order of first use. */
    fun variables(template: String): List<String> = SLOT.findAll(template).map { it.groupValues[1] }.distinct().toList()
}
