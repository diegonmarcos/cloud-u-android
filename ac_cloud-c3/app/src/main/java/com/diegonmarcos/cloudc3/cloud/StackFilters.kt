package com.diegonmarcos.cloudc3.cloud

import android.content.Context

/**
 * Persisted per-page selections and read state — MOVED from the SuperApp's launcher
 * package with the stack pages (#648), body unchanged. The c3 pages declare no visible
 * filter row, but the NTFY centre's swipe-to-read set, the visit watermark the "N new"
 * chips count from, and the per-box Archive keys all live here, exactly as they did.
 *
 * commit() rather than apply(): a selection still sitting in apply()'s async write queue
 * when the process dies is a selection that silently reverts.
 *
 * "Read" and "new" are different questions and both are answered. Read is explicit: the
 * user swiped that row. New is the `seen_at` watermark below — arrived since the previous
 * visit — and it is what a group's "N new" chip counts, because a chip that only fell when
 * you swiped every row would say "12 new" forever on a page you read by scrolling.
 */
object StackFilters {

    private const val PREF = "stack_filters"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** The chosen option id for [filter] on [page], or its declared default. */
    fun selected(ctx: Context, page: String, filter: Stacks.StackFilter): String {
        val stored = prefs(ctx).getString("$page/${filter.id}", null) ?: return filter.default
        // A stored id that build.json no longer declares would strand the row
        // on an option the user can no longer see, and therefore cannot undo.
        return if (filter.options.any { it.id == stored }) stored else filter.default
    }

    fun select(ctx: Context, page: String, filterId: String, optionId: String) {
        prefs(ctx).edit().putString("$page/$filterId", optionId).commit()
    }

    /** When [page] was last opened. Everything with a newer `ts` is unread. */
    fun lastSeen(ctx: Context, page: String): Long =
        prefs(ctx).getLong("$page/seen_at", 0L)

    fun markSeen(ctx: Context, page: String, at: Long) {
        prefs(ctx).edit().putLong("$page/seen_at", at).commit()
    }

    // ── per-entry read state ─────────────────────────────────────────────
    private fun readsKey(page: String) = "$page/read_keys"

    /** The entry ids marked read on [page]. Always a COPY: the set handed
     *  back by getStringSet is owned by SharedPreferences and mutating it in
     *  place is documented to have undefined effect on the stored value. */
    fun readKeys(ctx: Context, page: String): MutableSet<String> =
        HashSet(prefs(ctx).getStringSet(readsKey(page), emptySet()) ?: emptySet())

    /** Mark one entry read or unread. Returns the new full set so the caller
     *  does not have to re-read what it just wrote. */
    fun markRead(ctx: Context, page: String, id: String, read: Boolean): MutableSet<String> {
        val next = readKeys(ctx, page)
        if (read) next.add(id) else next.remove(id)
        prefs(ctx).edit().putStringSet(readsKey(page), next).commit()
        return next
    }

    /** Forget read ids under [prefix] that are no longer among [present].
     *
     *  Bounded by construction: every id carries a namespace prefix
     *  ("ntfy:<topic>:") and is only ever pruned by a caller that has just
     *  enumerated that ENTIRE namespace. Pruning against a partial view is
     *  what would make read rows silently reappear, so a failed ntfy poll
     *  prunes nothing rather than pruning its topic to empty. */
    fun pruneRead(ctx: Context, page: String, prefix: String, present: Set<String>) {
        val stored = readKeys(ctx, page)
        val keep = stored.filterNotTo(HashSet()) { it.startsWith(prefix) && it !in present }
        if (keep.size != stored.size) {
            prefs(ctx).edit().putStringSet(readsKey(page), keep).commit()
        }
    }
}
