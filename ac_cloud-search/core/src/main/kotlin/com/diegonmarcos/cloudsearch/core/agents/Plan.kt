package com.diegonmarcos.cloudsearch.core.agents

import com.diegonmarcos.cloudsearch.core.SearchConfig
import com.diegonmarcos.cloudsearch.core.Templates

/**
 * What an agent is set to look for, from its definition and the owner's filters: the goal in words and, per
 * source, the search the person opens in Cloud Browser. Pure text; nothing here reads or sends anything.
 */
object Plan {
    /** The agent's goal with the filters filled in; a filter left blank takes the goal's own fallback. */
    fun goal(agent: AgentsConfig.Agent, filters: Map<String, String>): String =
        Template.render(agent.goal, filters.filterValues { it.isNotBlank() }).text.replace(Regex("[ \\t]+"), " ").trim()

    /** The words a site search is asked for: the keywords filter, else the agent's own default. */
    fun query(filters: Map<String, String>, fallback: String): String = filters["keywords"]?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

    /**
     * The site search [source] opens for [filters]: its search.sources link with {q} the query and {city} the
     * location filter (else [defaultCity]), or null when the source names no link the declaration has.
     */
    fun searchUrl(source: AgentsConfig.Source, links: Map<String, SearchConfig.Source>, filters: Map<String, String>, fallbackQuery: String, defaultCity: String): String? {
        val s = links[source.search]?.takeIf { it.kind == SearchConfig.KIND_LINK && it.enabled && it.url.isNotBlank() } ?: return null
        val city = filters["location"]?.trim()?.takeIf { it.isNotEmpty() } ?: defaultCity
        return s.url.replace("{q}", Templates.enc(query(filters, fallbackQuery))).replace("{city}", Templates.enc(city))
            .replace("{radius}", filters["radius_km"]?.trim()?.takeIf { it.isNotEmpty() } ?: "")
    }
}
