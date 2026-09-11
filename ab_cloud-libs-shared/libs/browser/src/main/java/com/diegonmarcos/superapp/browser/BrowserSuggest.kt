package com.diegonmarcos.superapp.browser

/**
 * What the address-bar dropdown offers while typing.
 *
 * EVERY SOURCE HERE IS LOCAL: the user's own open/pinned tabs and their
 * own on-device history, plus one row that runs the query through the
 * configured search engine when they pick it.
 *
 * There is deliberately NO remote suggestion feed. A keystroke-by-
 * keystroke stream of what is being typed is the single most revealing
 * thing a browser can emit, and the useful part of autocomplete — "take
 * me back to the thing I already visited" — is answered entirely from
 * local data. Not shipping it is why there is no toggle to get wrong.
 *
 * Pure: ranking is asserted in a JVM unit test.
 */
object BrowserSuggest {

    enum class Source { TAB, HISTORY, SEARCH }

    data class Suggestion(val url: String, val label: String, val source: Source)

    /**
     * Ranked suggestions for [query].
     *
     * Tabs before history because a tab that is already open is the
     * cheapest destination and the likeliest intent. The search row is
     * always last and always present for a non-empty query, so the
     * dropdown never becomes a dead end when nothing matches locally.
     */
    fun suggest(
        query: String,
        tabs: List<BrowserTab>,
        history: List<BrowserVisit>,
        engine: BrowserSearchEngine,
        limit: Int = 8,
    ): List<Suggestion> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val needle = q.lowercase()

        val out = LinkedHashMap<String, Suggestion>()

        for (t in BrowserTabOrder.sort(tabs)) {
            if (t.url.lowercase().contains(needle) || t.title.lowercase().contains(needle)) {
                out.getOrPut(t.url) {
                    Suggestion(t.url, t.title.ifBlank { t.url }, Source.TAB)
                }
            }
        }
        for (v in history) {
            if (out.size >= limit) break
            if (v.url.lowercase().contains(needle) || v.title.lowercase().contains(needle)) {
                out.getOrPut(v.url) {
                    Suggestion(v.url, v.title.ifBlank { v.url }, Source.HISTORY)
                }
            }
        }

        val local = out.values.take(limit - 1)
        return local + Suggestion(
            url = BrowserSearch.searchUrl(q, engine),
            label = "Search ${engine.label} for “$q”",
            source = Source.SEARCH,
        )
    }
}
