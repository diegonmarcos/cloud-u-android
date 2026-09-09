package app.sterna.ui.inbox

import app.sterna.core.data.account.AccountStore
import app.sterna.core.jmap.model.Mailbox
import app.sterna.widget.AllInboxesView

/*
 * The drawer's memory of the view you were last in: what to store, what to reopen on, and the header
 * it may claim before anything syncs. The stored value carries the account id next to the
 */

/** The string to store for [sel] under [accountId], or `null` for "fall back on the Inbox next
 *  time" — `Sel.Folder(null)` included, a never-synced account that would reopen on no folder. */
internal fun encodeSelection(accountId: String?, sel: Sel): String? {
    if (accountId == null) return null
    return when (sel) {
        is Sel.Folder -> sel.id?.let { "$accountId\nf\n$it" }
        Sel.Unified -> "$accountId\nu\n"
        Sel.Unread -> "$accountId\nn\n"
    }
}

/**
 * The view to reopen on, or `null` for the Inbox. A folder deleted, renamed or unsubscribed is
 */
internal fun restoreSelection(
    stored: String?,
    currentAccountId: String?,
    unifiedViewExists: Boolean,
): Sel? {
    if (stored.isNullOrEmpty() || currentAccountId == null) return null
    val parts = stored.split("\n")
    if (parts.size != 3) return null
    // The central guard: without it a deleted account, a switch between two launches or
    // preferences carried over from another install open a neighbour account's row.
    if (parts[0] != currentAccountId) return null
    val payload = parts[2]
    return when (parts[1]) {
        "f" -> if (payload.isEmpty()) null else Sel.Folder(payload)
        "u" -> if (unifiedViewExists) Sel.Unified else null
        "n" -> Sel.Unread
        else -> null
    }
}

/** [restoreSelection]'s three arguments, fetched from the store — the one place that fetches them,
 *  `InboxViewModel` and `RootViewModel` needing the same answer (`SelectionMemoryWiringLintTest`). */
internal fun restoredSelection(store: AccountStore): Sel? = restoreSelection(
    store.storedView(),
    store.currentId(),
    AllInboxesView.existsFor(store.accounts()),
)

/** What the top bar shows: account line, mailbox name, badge count. */
internal data class Meta(val accountName: String, val mailboxName: String, val unread: Int)

/** The internal label the unified inbox's [Meta] carries. Never shown as it stands: `InboxScreen`
 *  swaps it for the translated string (`UnifiedTitleWiringLintTest`), so a copy of this literal
 *  would be English in nine locales. */
internal const val UNIFIED_LABEL = "All inboxes"

/** The header to start on for the view [sel] just restored, from what the store knows without a
 * round trip. A non-Inbox [Sel.Folder] gets no name rather than a false one, and no count: the
 *  name arrives from the cached folder list a moment later ([restoredFolderMeta]). */
internal fun restoredMeta(
    sel: Sel,
    inboxMailboxId: String?,
    accountLabel: String,
    inboxName: String,
    inboxUnread: Int,
    totalUnread: Int,
): Meta = when (sel) {
    Sel.Unified -> Meta(UNIFIED_LABEL, UNIFIED_LABEL, totalUnread)
    Sel.Unread -> Meta(accountLabel, inboxName, inboxUnread)
    is Sel.Folder ->
        if (sel.id == inboxMailboxId) Meta(accountLabel, inboxName, inboxUnread)
        else Meta(accountLabel, "", 0)
}

/**
 * Two fields, not one nullable: "no list yet" and "decided, nothing to write" are the same
 */
internal data class RestoredMetaStep(val settled: Boolean, val meta: Meta?)

/**
 * Fill in the name and count [restoredMeta] left blank, from the cached folder list. An empty
 */
internal fun restoredFolderMeta(
    restoredId: String?,
    selection: Sel,
    folders: List<Mailbox>,
    accountLabel: String,
): RestoredMetaStep {
    if (restoredId == null) return RestoredMetaStep(settled = false, meta = null)
    if (folders.isEmpty()) return RestoredMetaStep(settled = false, meta = null)
    if (selection != Sel.Folder(restoredId)) return RestoredMetaStep(settled = true, meta = null)
    val folder = folders.firstOrNull { it.id == restoredId }
        ?: return RestoredMetaStep(settled = true, meta = null)
    return RestoredMetaStep(settled = true, meta = Meta(accountLabel, folder.name, folder.unreadEmails))
}

/** The stored view to keep once [removedIds] have been signed out, or `null` to forget it —
 *  `PRIVACY.md` promises everything goes with the account, and this holds an account id and, on
 * IMAP, a folder path the reader named herself. The key is global, so it goes only when its
 *  first field names a removed account. It does not replace [restoreSelection]'s account guard. */
internal fun prunedView(stored: String?, removedIds: Set<String>): String? {
    if (stored.isNullOrEmpty()) return null
    return if (stored.substringBefore("\n") in removedIds) null else stored
}
