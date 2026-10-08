package com.diegonmarcos.cloudsearch.core.agents

/**
 * The listing links in an alert mail, found and deduplicated. The rules are data (build.json::search.agents.agents[].links):
 * which hosts a link may point at, which path counts as a listing (it ends in the listing's id), which paths are never one.
 */
data class LinkRules(val hosts: List<String>, val idRegex: Regex, val excludePaths: List<String>)

data class ListingLink(val id: String, val url: String)

object Links {
    private val HREF = Regex("""href\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
    private val BARE = Regex("""https?://[^\s<>"'\\]+""", RegexOption.IGNORE_CASE)
    private val ENTITY = Regex("&(amp|#38|#x26);", RegexOption.IGNORE_CASE)

    /** Listing links in [text] (a plain part) and [html] (an html part), first sight wins, one per listing id. */
    fun extract(text: String, html: String, rules: LinkRules): List<ListingLink> {
        val raw = ArrayList<String>()
        HREF.findAll(html).forEach { raw += (it.groups[1]?.value ?: it.groups[2]?.value).orEmpty() }
        BARE.findAll(html).forEach { raw += it.value }
        BARE.findAll(text).forEach { raw += it.value }
        val seen = LinkedHashMap<String, ListingLink>()
        for (candidate in raw) {
            val link = canonical(candidate, rules) ?: continue
            seen.putIfAbsent(link.id, link)
        }
        return seen.values.toList()
    }

    private val SHAPE = Regex("""^(https?)://([^/?#]*)([^?#]*)""", RegexOption.IGNORE_CASE)

    /** One candidate as a listing link, or null when its host, path or shape is not a listing's. */
    fun canonical(candidate: String, rules: LinkRules): ListingLink? {
        val cleaned = ENTITY.replace(candidate.trim(), "&").trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '>')
        val m = SHAPE.find(cleaned) ?: return null
        val authority = m.groupValues[2]
        // A link with credentials in it is never a listing's.
        if (authority.contains('@')) return null
        val host = authority.substringBefore(':').lowercase()
        if (host.isEmpty() || rules.hosts.none { host == it || host.endsWith(".$it") }) return null
        val path = m.groupValues[3]
        if (rules.excludePaths.any { path.startsWith(it) || path.startsWith("/en$it") || path.startsWith("/de$it") }) return null
        val id = rules.idRegex.find(path)?.groupValues?.getOrNull(1) ?: return null
        // The query and fragment carry tracking and nothing the page needs: the listing is its path.
        return ListingLink(id, "https://$host$path")
    }
}
