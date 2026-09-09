package app.sterna.core.data.mail

import app.sterna.core.jmap.WindowWalk

/** The three numbers one folder refresh runs on: [pageSize] is the window capped by
 *  `maxObjectsInGet`, [windowTarget] and [retentionFloor] the window whole, never the cap (#110). */
internal data class FolderSyncSizing(
    val windowTarget: Int,
    val pageSize: Int,
    val retentionFloor: Int,
)

/** A triple rather than a number because `SyncWindow.limit` is both the size of the request and the
 *  retention floor handed to the prune (#110), and only the first may be capped: capping both makes
 *  the prune delete mail the user asked to keep. [pageSize] is floored at 1. */
internal fun folderSyncSizing(windowLimit: Int, serverCapacity: Int): FolderSyncSizing =
    FolderSyncSizing(
        windowTarget = windowLimit,
        pageSize = requestPageSize(windowLimit, serverCapacity),
        retentionFloor = windowLimit,
    )

/** [accountWindow], and [requestedByCaller] only when there is none. The caller does not get to
 *  size this branch: it ends in `EmailDao.replaceMailbox`, which deletes every cached row it is not
 *  given, and two callers reach it with a hardcoded 50 (#110). */
internal fun fullQueryWindowTarget(accountWindow: Int?, requestedByCaller: Int): Int =
    accountWindow ?: requestedByCaller

/** The three numbers a full re-query runs on, all derived from [fullQueryWindowTarget]. */
internal fun fullQuerySizing(accountWindow: Int?, requestedByCaller: Int, serverCapacity: Int): FolderSyncSizing =
    folderSyncSizing(fullQueryWindowTarget(accountWindow, requestedByCaller), serverCapacity)

/** The cache ids a finished window walk may be reconciled against, and null where it may not:
 *  `EmailDao.reconcileMailbox` deletes every cached row outside the set it is given, so "I cannot
 *  say" needs a value that is not the empty set. A walk resumed by position is refused first: it
 *  resumes one row behind its accumulated count, hiding any id that left beside the anchor. */
internal fun reconcilableWindowIds(walk: WindowWalk): Set<String>? = when {
    walk.resumedByPosition -> null
    walk.ids.isNotEmpty() -> walk.ids.toHashSet()
    walk.queryCount == 0 -> emptySet()
    else -> null
}

/** Which clause of [reconcilableWindowIds] decided, as a log token, in the same order of tests:
 *  `skip/resumed` (paged by absolute position after an `anchorNotFound`), `reconciled`, `emptied`,
 *  `skip/muted`. A `skip` prefix means nothing was deleted from that folder on this pass. */
internal fun fullQueryReconcileReason(walk: WindowWalk): String = when {
    walk.resumedByPosition -> "skip/resumed"
    walk.ids.isNotEmpty() -> "reconciled"
    walk.queryCount == 0 -> "emptied"
    else -> "skip/muted"
}

/** [wanted] capped by [serverCapacity], never below 1. Shared with [folderSyncSizing] so one
 *  expression in the app turns a want plus a server limit into a request size. */
internal fun requestPageSize(wanted: Int, serverCapacity: Int): Int =
    wanted.coerceAtMost(serverCapacity).coerceAtLeast(1)
