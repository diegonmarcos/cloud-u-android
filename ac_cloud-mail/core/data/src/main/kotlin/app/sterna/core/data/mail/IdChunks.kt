package app.sterna.core.data.mail

/** Read [ids] through [read] in chunks of at most [chunk], in order. One `IN (...)` list must stay
 *  under SQLite's bound-variable limit (999 below Android 12), and the refusal is thrown outside
 *  the `runCatching` the bulk paths wrap their server call in: the action crashes, not fails. */
internal suspend fun <T> byIdsChunked(
    ids: List<String>,
    chunk: Int = MAX_CHANGES,
    read: suspend (List<String>) -> List<T>,
): List<T> {
    if (ids.isEmpty()) return emptyList()
    if (ids.size <= chunk) return read(ids)
    return ids.chunked(chunk).flatMap { read(it) }
}
