package app.sterna.core.imap

    /**
     * How many sequence positions one request of a folder walk asks for. IMAP has nothing to negotiate
     */
const val IMAP_FOLDER_PAGE = 200

    /**
     * What one paginated walk of a folder brings back — UIDS and a verdict, never messages: the walk
     */
data class ImapFolderWalk(
    val uids: List<Long>,
    val moved: Boolean,
        /**
         * Whether the `SELECT` this walk started from STATED that the folder holds nothing — an
         */
    val folderStatedEmpty: Boolean = false,
)

    /** The lowest sequence number of the newest [limit] messages of a folder holding [exists] of them.
     *  Never below 1, so a window larger than the folder is the whole folder rather than a range the
     *  server rejects. */
fun folderWindowLowest(exists: Int, limit: Int): Int = (exists - limit + 1).coerceAtLeast(1)

    /**
     * The sequence range of the NEXT request of a newest-first folder walk over [lowest]..[highest], or
     */
fun nextFolderPage(lowest: Int, highest: Int, previous: IntRange?, pageSize: Int): IntRange? {
    if (pageSize <= 0 || highest < 1 || lowest > highest) return null
    if (previous == null) return (highest - pageSize + 1).coerceAtLeast(lowest)..highest
    if (previous.first <= lowest) return null
    val top = previous.first - 1
    return (top - pageSize + 1).coerceAtLeast(lowest)..top
}

    /**
     * Whether these untagged response lines say the folder is no longer the one the walk started on,
     */
fun folderMoved(untagged: List<List<Any?>>, startedWith: Int): Boolean = untagged.any { line ->
    val second = line.getOrNull(1)
    when {
        line.getOrNull(2) == "EXPUNGE" -> true
        second == "VANISHED" -> true
        line.getOrNull(2) == "EXISTS" -> (second as? String)?.toIntOrNull() != startedWith
        else -> false
    }
}
