package app.sterna.core.jmap

    /**
     * What the previous request of a windowed folder walk asked for, and what it brought back.
     */
data class WalkedPage(
    /** The `limit` that request carried. */
    val requested: Int,
    val queryCount: Int,
    val added: Int,
)

    /**
     * What a windowed folder walk brings back — IDS and cursors, never messages: the walk hands each
     */
data class WindowWalk(
    val ids: List<String>,
    val queryState: String?,
    val emailState: String?,
    val queryCount: Int,
    val resumedByPosition: Boolean,
)

    /**
     * How big the NEXT `Email/query` of a windowed folder walk must be, or null when the walk is over.
     */
fun nextWindowPageLimit(
    fetched: Int,
    target: Int,
    pageSize: Int,
    last: WalkedPage?,
): Int? {
    if (target <= 0 || pageSize <= 0) return null
    if (last == null) return minOf(target, pageSize)
    if (fetched >= target) return null
    if (last.queryCount < last.requested) return null
    if (last.added <= 0) return null
    return minOf(target - fetched, pageSize)
}
