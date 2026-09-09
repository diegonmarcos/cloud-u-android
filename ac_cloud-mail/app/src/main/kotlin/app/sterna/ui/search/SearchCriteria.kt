package app.sterna.ui.search

import app.sterna.core.jmap.model.SearchQuery

/**
 * The advanced-search form: the criteria being edited, and whether the criteria panel is open.
 */
data class SearchForm(
    val query: SearchQuery = SearchQuery(),
    val expanded: Boolean = true,
) {
    /**
     * The panel after the search button (or the keyboard's Search key) was pressed: folded, so
     */
    fun afterSearch(): SearchForm = copy(expanded = query.isEmpty())

    /** The user asking for the panel back (the toolbar toggle, or a tap on the summary). */
    fun toggled(): SearchForm = copy(expanded = !expanded)
}

/** One criterion of a query, for the one-line summary shown while the panel is folded. */
enum class SearchCriterion { FROM, RECIPIENT, SUBJECT, ATTACHMENT, FLAGGED, AFTER, BEFORE }

/**
 * A criterion and its value: [text] for the typed ones, [millis] for the date bounds (formatted
 * by the screen, which alone knows the locale).
 */
data class SearchFilter(
    val criterion: SearchCriterion,
    val text: String = "",
    val millis: Long? = null,
)

/**
 * The active criteria of [query] BESIDES the free text, in the order the panel lists them.
 */
fun searchSummary(query: SearchQuery): List<SearchFilter> = buildList {
    query.from.trim().takeIf { it.isNotEmpty() }?.let { add(SearchFilter(SearchCriterion.FROM, text = it)) }
    query.recipient.trim().takeIf { it.isNotEmpty() }?.let { add(SearchFilter(SearchCriterion.RECIPIENT, text = it)) }
    query.subject.trim().takeIf { it.isNotEmpty() }?.let { add(SearchFilter(SearchCriterion.SUBJECT, text = it)) }
    if (query.hasAttachment) add(SearchFilter(SearchCriterion.ATTACHMENT))
    if (query.flagged) add(SearchFilter(SearchCriterion.FLAGGED))
    query.afterMillis?.let { add(SearchFilter(SearchCriterion.AFTER, millis = it)) }
    query.beforeMillis?.let { add(SearchFilter(SearchCriterion.BEFORE, millis = it)) }
}
