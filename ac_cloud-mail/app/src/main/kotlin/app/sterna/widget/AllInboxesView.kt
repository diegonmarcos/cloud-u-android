package app.sterna.widget

import app.sterna.core.data.account.StoredAccount

/**
 * Does the app HAVE a cross-account "All inboxes" list at all? One bound, in one place, because the
 */
internal object AllInboxesView {

    fun existsFor(accounts: List<StoredAccount>): Boolean = accounts.size > 1
}
