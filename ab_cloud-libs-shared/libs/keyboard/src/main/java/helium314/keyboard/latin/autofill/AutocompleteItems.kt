// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.autofill

import com.diegonmarcos.superapp.autofill.AutofillProfile
import com.diegonmarcos.superapp.autofill.AutofillSot
import com.diegonmarcos.superapp.autofill.Snippet

/**
 * #1077: the rows of the Autocomplete list (the clipboard panel's second source). One row = one field value the
 * fleet's autofill knows. [value] is null for a row whose value is never available to the keyboard (Cloud
 * Vault's): tapping it opens the vault, which has its own unlock. [sensitive] rows are masked and need
 * [AutocompleteUnlock] before they paste or copy.
 */
data class AutocompleteItem(val group: String, val label: String, val value: String?, val sensitive: Boolean) {
    val inVault get() = value == null
}

object AutocompleteItems {
    const val MASK = "••••••"

    /** Cloud Account's rows (profiles, contacts, addresses, snippets) then one row per Cloud Vault item type. Pure. */
    fun build(profiles: List<AutofillProfile>, snippets: List<Snippet>): List<AutocompleteItem> {
        val out = ArrayList<AutocompleteItem>()
        fun sens(col: String) = col in AutofillSchemas.SENSITIVE_ACCOUNT_COLUMNS
        profiles.sortedByDescending { it.isDefault }.forEach { p ->
            AutofillSot.PROFILE_FIELDS.forEach { c ->
                if (p[c].isNotEmpty()) out += AutocompleteItem(p.title, AutofillSchemas.label(c), p[c], sens(c))
            }
            p.contacts.filter { it.value.isNotBlank() }.forEach { c ->
                out += AutocompleteItem(p.title, AutofillSchemas.label(c.kind) + (if (c.type.isNotBlank()) " (${c.type})" else ""), c.value.trim(), false)
            }
            p.addresses.filterNot { it.isEmpty }.forEach { a ->
                AutofillSot.ADDRESS_FIELDS.forEach { c ->
                    if (a[c].isNotEmpty()) out += AutocompleteItem(p.title + " · " + a.title, AutofillSchemas.label(c), a[c], sens(c))
                }
            }
        }
        snippets.filter { it.text.isNotBlank() }.forEach {
            out += AutocompleteItem("Snippets", it.label.ifBlank { it.text.lineSequence().first().take(24) }, it.text, sens(AutofillSot.S_TEXT))
        }
        AutofillSchemas.vault().forEach { t -> out += AutocompleteItem(AutofillSchemas.VAULT, t.title, null, true) }
        return out
    }

    /** What a row shows: the value, or [MASK] while it is sensitive and locked; a vault row says where it lives. */
    fun shown(item: AutocompleteItem, unlocked: Boolean): String = when {
        item.value == null -> "Open Cloud Vault"
        item.sensitive && !unlocked -> MASK
        else -> item.value
    }

    /** What a tap or a long-press may do with a row right now. */
    enum class Access { USE, NEEDS_UNLOCK, OPEN_VAULT }

    fun access(item: AutocompleteItem, unlocked: Boolean): Access = when {
        item.value == null -> Access.OPEN_VAULT
        item.sensitive && !unlocked -> Access.NEEDS_UNLOCK
        else -> Access.USE
    }
}

/** A short-lived unlock for sensitive rows: granted by a device-credential check, forgotten after [TTL_MS] or when the panel closes. */
object AutocompleteUnlock {
    const val TTL_MS = 60_000L
    @Volatile private var until = 0L

    fun isUnlocked(now: Long): Boolean = now < until
    fun grant(now: Long) { until = now + TTL_MS }
    fun lock() { until = 0L }
}
