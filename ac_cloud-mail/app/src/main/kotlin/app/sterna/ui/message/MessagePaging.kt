package app.sterna.ui.message

import app.sterna.core.imap.CryptoKind

/** Pure helpers for paging between list entries in the reading view (unit-tested). */
object MessagePaging {
    /** The identity of one reading-view entry — (owning account, email id) — as a single string. JMAP
     *  ids are handed out PER ACCOUNT, so an id alone let Compose treat two different messages as one
     *  page and show the wrong account's mail (#92). The separator is NUL, so the mapping is injective. */
    fun entryKey(emailId: String, accountId: String?): String = "${accountId.orEmpty()}\u0000$emailId"

    /**
     * Whether the reading view must (re)load: [settled] first, and everything else after it.
     */
    fun needsLoad(
        settled: Boolean,
        loadedId: String?,
        loadedAccountId: String?,
        emailId: String,
        accountId: String?,
        failed: Boolean,
    ): Boolean = settled && (failed || loadedId != emailId || loadedAccountId != accountId)

    /**
     * Whether a body already in the LOCAL cache may be put on a page the finger has not reached — the
     */
    fun warmable(
        cryptoKind: CryptoKind?,
        inlineParts: Int,
        cachedInlineImages: Int,
        senderAllowedRemoteImages: Boolean,
    ): Boolean = cryptoKind == null &&
        (inlineParts == 0 || cachedInlineImages > 0) &&
        !senderAllowedRemoteImages

    /**
     * The entry the reader is SETTLED on: [previous] while the pager is [scrolling], [atRest]
     */
    fun <T> settledEntry(previous: T?, atRest: T?, scrolling: Boolean): T? =
        if (scrolling) previous else atRest

    /** The page the pager should open on: the position of [anchorId] within the ordered entries (robust
     *  to the list having shifted since the row was tapped), else [fallbackIndex]. Always a valid page
     *  index, or 0 for an empty list. */
    fun resolveInitialPage(orderedIds: List<String?>, anchorId: String, fallbackIndex: Int): Int {
        if (orderedIds.isEmpty()) return 0
        val found = orderedIds.indexOfFirst { it == anchorId }
        val index = if (found >= 0) found else fallbackIndex
        return index.coerceIn(0, orderedIds.size - 1)
    }

    /** Whether the reader can step back / forward from [page] in a context of [pageCount] messages —
     *  whether the position line's chevrons are live. The pager does not wrap and does not run past its
     *  context, so both are false at the matching end and on a single message. */
    fun hasPrevious(page: Int, pageCount: Int): Boolean = page > 0 && pageCount > 1

    fun hasNext(page: Int, pageCount: Int): Boolean = page < pageCount - 1 && pageCount > 1

    /**
     * Merge the live paged entries into the reading session's sticky entry list.
     */
    fun <T : Any> mergeEntries(stable: List<T>, live: List<T>, idOf: (T) -> String): List<T> {
        if (stable.isEmpty()) return live
        val result = stable.toMutableList()
        val known = result.mapTo(HashSet()) { idOf(it) }
        // pos = insertion cursor in result, advanced past each live entry as it is matched.
        var pos = 0
        for (entry in live) {
            if (idOf(entry) in known) {
                val offset = result.subList(pos, result.size).indexOfFirst { idOf(it) == idOf(entry) }
                if (offset >= 0) pos += offset + 1
            } else {
                result.add(pos, entry)
                known += idOf(entry)
                pos++
            }
        }
        return result
    }
}
