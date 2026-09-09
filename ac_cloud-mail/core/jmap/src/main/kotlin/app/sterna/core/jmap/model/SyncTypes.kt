package app.sterna.core.jmap.model

/** A blob uploaded via the JMAP upload endpoint, ready to attach to an Email/set. */
data class UploadedBlob(
    val blobId: String,
    val type: String,
    val size: Long,
)

/** A page of a mailbox query plus the JMAP state strings needed for incremental sync. */
data class EmailPage(
    val emails: List<Email>,
    val queryState: String?,
    val emailState: String?,
    /** Total messages matching the query (when the server calculated it), for paging end-detection. */
    val total: Int? = null,
        /**
         * How many ids the `Email/query` half returned — which can EXCEED `emails.size` when the
         */
    val queryCount: Int,
)

/** Ids-only page of an `Email/query` (no `Email/get`) — for resolving bulk-action targets. */
data class EmailIdPage(
    val ids: List<String>,
    /** Total messages matching the query (when the server calculated it), for paging end-detection. */
    val total: Int? = null,
)

    /** One page of the search-index crawl. [queryCount] is how many ids `Email/query` returned at this
     *  position and can exceed `emails.size` (`maxObjectsInGet`, or messages moved between the query
     *  and the get); terminating on it keeps the crawl from abandoning the oldest mail. */
data class CrawlPage(
    val emails: List<Email>,
    val queryCount: Int,
)

    /**
     * One page of a search: the messages fetched, and [matchedIds] — how many ids `Email/query` matched
     */
data class SearchPage(
    val emails: List<Email>,
    val matchedIds: Int?,
)

    /** Result of Email/queryChanges. [calculated] is false when the server returned
     *  cannotCalculateChanges/tooManyChanges and the caller must fall back to a full query. */
data class EmailQueryChangesResult(
    val newQueryState: String?,
    val removed: List<String>,
    val added: List<String>,
    val calculated: Boolean,
)

    /** Per-id outcome of an `Email/set`. A response can succeed at the method level while every
     *  individual change is rejected (RFC 8620 §5.3), so callers must check membership in [done]
     *  instead of assuming success. */
data class EmailSetResult(
    val newState: String?,
    /** Ids the server actually updated or destroyed. */
    val done: Set<String>,
    /** Rejected ids, mapped to the server's SetError type. */
    val failed: Map<String, String>,
)

/** Result of Email/changes (property-level created/updated/destroyed). */
data class EmailChangesResult(
    val newState: String?,
    val created: List<String>,
    val updated: List<String>,
    val destroyed: List<String>,
    val hasMoreChanges: Boolean,
    val calculated: Boolean,
)
