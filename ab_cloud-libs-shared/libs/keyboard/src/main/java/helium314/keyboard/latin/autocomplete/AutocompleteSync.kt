// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.autocomplete

import android.content.Context
import android.net.Uri
import com.diegonmarcos.superapp.autofill.AutocompleteContract

/**
 * #1103 Cloud Account holds the owner's private data and hands it to this keyboard's own store over the fleet's
 * `accountdata` provider (CONSTELLATION_DATA, and a per-package grant Account's Secrets section keeps). This is a local
 * IPC call: the keyboard never reaches for the network for these rows and never asks any other app.
 */
object AutocompleteSync {
    sealed interface Outcome {
        data class Synced(val rows: Int) : Outcome
        data object NoAccount : Outcome
        data class Refused(val why: String) : Outcome
    }

    /** Takes Account's answer into [store]. A refused or malformed answer leaves the store as it was. Pure, so a JVM test drives it. */
    fun apply(store: AutocompleteStore, ok: Boolean, json: String?, error: String?, now: Long): Outcome {
        if (!ok) return Outcome.Refused(error.orEmpty().ifBlank { "refused" })
        val rows = AutocompleteContract.parse(json)
        store.replaceAll(rows, now)
        return Outcome.Synced(rows.size)
    }

    /** One call to Account's provider; blocking, so never on the main thread. */
    fun pull(context: Context, store: AutocompleteStore, now: Long = System.currentTimeMillis()): Outcome {
        val r = runCatching {
            context.contentResolver.call(Uri.parse("content://${AutocompleteContract.AUTHORITY}"), AutocompleteContract.METHOD, null, null)
        }.getOrNull() ?: return Outcome.NoAccount
        return apply(store, r.getBoolean(AutocompleteContract.KEY_OK), r.getString(AutocompleteContract.KEY_JSON), r.getString("error"), now)
    }
}
