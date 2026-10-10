package com.diegonmarcos.superapp.search

/**
 * THE search engine: matching, the scope filter, the grouping into one section per scope, the
 * caps, the chip rule and the `:`-command line. It is the engine the Home sheet's search always
 * had (it lived inside the old SearchSheet fragment), lifted out unchanged so every place that
 * searches — the Home star, the Home swipe sheet and Cloud ▸ Apps — runs this one, and so it is
 * tested without a screen. No UI, no Android: an app draws [Section]s however it draws.
 *
 * ## Matching
 * A hit matches when its label or its crumb contains the query, ignoring case; an empty query
 * matches everything. Hits keep the order their source built them in. Live hits (the
 * [SearchKinds.LIVE] scopes) were matched by their own source for this very query (Cloud
 * Browser matches every word against title and address), so they are filtered by scope only.
 *
 * ## Sections
 * One per selected scope, in the DECLARED scope order (build.json::ui.search_scopes), so the
 * groups always read in the same sequence whatever the counts; a scope that is off, or has no
 * hit, draws no section. [Section.total] is every match, [Section.hits] the capped rows: [CAP]
 * in all, split evenly between the selected scopes but never below [MIN_PER_SCOPE] each.
 *
 * ## Search vs. command
 * A query whose VERY FIRST character is `:` is a command line; anything else is a search. The
 * check is on the raw text, deliberately un-trimmed, so `" Bars in Berlin:Mitte"` is a search
 * while `":update-all"` runs the update-all command (colon-rule.test.sh holds the rule).
 */
object SearchEngine {

    const val CAP = 60
    const val MIN_PER_SCOPE = 15
    const val COMMAND_CAP = 60

    /** Scope id used when the app declares no `ui.search_scopes` at all. */
    const val ALL = "all"

    data class Section(val scope: SearchScope, val total: Int, val hits: List<SearchHit>)

    /** The scopes to offer: the declared ones, or one unscoped list when there are none. */
    fun scopesOrAll(declared: List<SearchScope>): List<SearchScope> =
        declared.ifEmpty { listOf(SearchScope(ALL, "All", "")) }

    fun matches(hit: SearchHit, query: String): Boolean {
        val q = query.trim().lowercase()
        return q.isEmpty() || hit.label.lowercase().contains(q) || hit.crumb.lowercase().contains(q)
    }

    fun sections(
        scopes: List<SearchScope>,
        hits: List<SearchHit>,
        selected: Set<String>,
        query: String,
        live: List<SearchHit> = emptyList(),
    ): List<Section> {
        val on = scopes.filter { it.id in selected }
        if (on.isEmpty()) return emptyList()
        val perScope = if (on.size == 1) CAP else (CAP / on.size).coerceAtLeast(MIN_PER_SCOPE)
        val matched = hits.filter { it.source in selected && matches(it, query) } + live.filter { it.source in selected }
        return on.mapNotNull { scope ->
            val group = matched.filter { it.source == scope.id }
            if (group.isEmpty()) null else Section(scope, group.size, group.take(perScope))
        }
    }

    /** The first row of the first section: what the keyboard's Go opens. */
    fun top(sections: List<Section>): SearchHit? = sections.firstOrNull()?.hits?.firstOrNull()

    /** A chip tap. The last chip never turns off: an empty selection shows nothing and looks like
     *  a broken search rather than a filter. */
    fun toggle(selected: Set<String>, id: String): Set<String> = when {
        id !in selected -> selected + id
        selected.size > 1 -> selected - id
        else -> selected
    }

    // ─────────────────────────── the colon rule ───────────────────────────

    /** Command mode iff the FIRST character is a colon (and the app has commands). Raw text on
     *  purpose: a leading space means the user is typing prose, not a command. */
    fun isCommandMode(query: String, commands: List<SearchCommand>): Boolean =
        commands.isNotEmpty() && query.startsWith(":")

    /** What was typed after the colon, normalised for matching. */
    fun commandQuery(query: String): String = query.removePrefix(":").trim().lowercase()

    /** The one command whose alias is exactly what was typed, for Go. */
    fun exactCommand(query: String, commands: List<SearchCommand>): SearchCommand? =
        commandQuery(query).let { q -> commands.singleOrNull { it.alias.equals(q, ignoreCase = true) } }

    /** The commands the line lists: all of them for a bare colon, else alias or label contains it. */
    fun commandMatches(query: String, commands: List<SearchCommand>): List<SearchCommand> {
        val q = commandQuery(query)
        val m = if (q.isEmpty()) commands else commands.filter {
            it.alias.contains(q, ignoreCase = true) || it.label.contains(q, ignoreCase = true)
        }
        return m.take(COMMAND_CAP)
    }
}
