// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.autocomplete

import com.diegonmarcos.superapp.autofill.AutocompleteContract
import com.diegonmarcos.superapp.autofill.AutocompleteContract.Row

/**
 * #1103 The pure half of the Autocomplete engine: which pages exist, what a row shows and what a tap may do with it. The
 * pages are declared ONCE ([AutocompletePage]); the view, the Configs page and the tests all read this list.
 */
enum class AutocompletePage(val id: String, val title: String, val masked: Boolean) {
    IDS("ids", "IDs", false),
    ADDRESSES("addresses", "Addresses", false),
    URLS("urls", "URLs", false),
    SECRETS("secrets", "Secrets", true);

    companion object {
        fun of(id: String): AutocompletePage? = entries.firstOrNull { it.id == id }
    }
}

object AutocompleteEngine {
    const val MASK = "••••••"

    /** What a row shows: the value, or [MASK] while it sits on a masked page and the device has not been unlocked. */
    fun shown(row: Row, unlocked: Boolean): String =
        if (AutocompletePage.of(row.page)?.masked == true && !unlocked) MASK else row.value

    enum class Access { USE, NEEDS_UNLOCK }

    /** What a tap or a long-press may do with a row right now. */
    fun access(row: Row, unlocked: Boolean): Access =
        if (AutocompletePage.of(row.page)?.masked == true && !unlocked) Access.NEEDS_UNLOCK else Access.USE

    fun isSensitive(row: Row): Boolean = AutocompletePage.of(row.page)?.masked == true

    /** The rows of one page, in stored order. */
    fun rows(all: List<Row>, page: AutocompletePage): List<Row> = all.filter { it.page == page.id }

    /** The page ids this engine and the wire contract agree on: a page added to one and not the other is a bug a test catches. */
    fun declaredPages(): List<String> = AutocompletePage.entries.map { it.id }.also { check(it == AutocompleteContract.PAGES) }
}

/** A short-lived unlock for the masked page: granted by a device-credential check, forgotten after [TTL_MS] or when the view closes. */
object AutocompleteUnlock {
    const val TTL_MS = 60_000L
    @Volatile private var until = 0L

    fun isUnlocked(now: Long): Boolean = now < until
    fun grant(now: Long) { until = now + TTL_MS }
    fun lock() { until = 0L }
}
