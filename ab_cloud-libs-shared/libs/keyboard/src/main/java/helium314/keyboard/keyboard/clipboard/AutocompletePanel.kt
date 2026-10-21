// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.clipboard

/**
 * #1077: the Autocomplete list is the clipboard panel's second source, so it opens through the one panel
 * path (view-state invariants, CLOSE_HISTORY, the bottom row). The toolbar key asks for it here and the
 * panel reads the request when it opens.
 */
object AutocompletePanel {
    @Volatile private var requested = false
    fun request() { requested = true }
    fun consume(): Boolean = requested.also { requested = false }
}
