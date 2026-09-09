package app.sterna.core.data.mail

/** Which cached folder ids a full folder-list sync drops, in batches no statement can choke on.
 *  Computed as a complement against the whole [keepIds] before chunking: SQLite binds at most 999
 *  variables below Android 12 (#29, #142), and chunking a `NOT IN` would delete the whole drawer.
 *  [cachedIds] must already be scoped to one account. */
internal fun mailboxEvictions(
    cachedIds: List<String>,
    keepIds: Set<String>,
    chunk: Int = MAX_CHANGES,
): List<List<String>> =
    cachedIds.filter { it !in keepIds }.chunked(chunk)
