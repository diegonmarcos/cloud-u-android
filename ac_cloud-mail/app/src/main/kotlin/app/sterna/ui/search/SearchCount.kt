package app.sterna.ui.search

/**
 * What the header above a result list is allowed to claim.
 */
enum class SearchCount { EXACT, AT_LEAST }

/**
 * The one place both search surfaces decide how to phrase the count, so they cannot drift apart. A
 */
fun searchCount(complete: Boolean, loading: Boolean): SearchCount =
    if (complete && !loading) SearchCount.EXACT else SearchCount.AT_LEAST

/**
 * An answer is complete only when EVERY leg ran to the end — the inbox's bar has two, the local
 */
fun searchComplete(local: Boolean, server: Boolean): Boolean = local && server

/** Which of the four mutually exclusive things a search surface puts on screen. */
enum class SearchDisplay {
    RESULTS,
    SPINNER,
    /** Nothing found, but the search stopped short — so nothing has been PROVEN absent. */
    INCOMPLETE_EMPTY,
    /** Nothing found by a search that ran to the end: the only case that may say "no results". */
    EMPTY,
}

/**
 * Results win over the spinner: once there are rows they stay, replacing them when the second pass
 */
fun searchDisplay(resultCount: Int, loading: Boolean, complete: Boolean): SearchDisplay = when {
    resultCount > 0 -> SearchDisplay.RESULTS
    loading -> SearchDisplay.SPINNER
    !complete -> SearchDisplay.INCOMPLETE_EMPTY
    else -> SearchDisplay.EMPTY
}
