package com.diegonmarcos.capitalfinterm

import org.json.JSONArray
import org.json.JSONObject

/** The FinTerm tab and entry titles one source feeds (data/sources.json `feeds`); an entry of "*" is every entry of the tab. */
data class Feed(val tab: String, val entries: List<String>)

/** Where a key goes when a connector sends it (data/sources.json `auth.key_in`): query, header, basic, oauth or message, and the parameter's name. */
data class KeyIn(val where: String, val name: String)

/** One declared data source. All fields come from data/sources.json; Kotlin names no address. */
data class Source(
    val id: String,
    val name: String,
    val category: String,
    val coverage: String,
    val endpoint: String,
    val docs: String,
    val userEndpoint: Boolean,
    val authType: String,
    val authNote: String,
    val signup: String,
    val keyIn: KeyIn?,
    val connector: String,
    val feeds: List<Feed>,
    /** Lower is tried first: the own-fleet primary (0), the key-free public sources (10), the keyed ones (20). */
    val priority: Int,
    val primary: Boolean,
    /** The id of the fleet endpoint (ac_cloud-account fleet_endpoints) whose mesh address is baked; the source's address when `endpoint` is empty. */
    val fleetEndpoint: String,
    /** The source's declared API paths under its address (health, info, topics, peek, ...). */
    val paths: Map<String, String>,
    val attribution: String,
) {
    val needsKey: Boolean get() = authType != Catalog.AUTH_NONE
    val hasConnector: Boolean get() = connector.isNotEmpty()

    /** True when this source is declared to feed [entry] of [tab]. */
    fun feeds(tab: String, entry: String): Boolean = feeds.any { it.tab == tab && ("*" in it.entries || entry in it.entries) }
}

/** The source catalog (data/sources.json): parse, group, and the mapping of FinTerm entries to the sources that feed them. */
object Catalog {
    const val AUTH_NONE = "none"
    const val AUTH_FREE = "free_key"
    const val AUTH_PAID = "paid"
    val AUTH_TYPES: Set<String> = setOf(AUTH_NONE, AUTH_FREE, AUTH_PAID)

    /** The sources in declared order; an unparsable catalog is empty (the page then says so). */
    fun parse(json: String): List<Source> {
        val arr = runCatching { JSONObject(json).optJSONArray("sources") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { o ->
            val auth = o.optJSONObject("auth") ?: JSONObject()
            val keyIn = auth.optJSONObject("key_in")?.let { KeyIn(str(it, "where"), str(it, "name")) }
            Source(
                id = str(o, "id"), name = str(o, "name"), category = str(o, "category"), coverage = str(o, "coverage"),
                endpoint = str(o, "endpoint"), docs = str(o, "docs"), userEndpoint = o.optBoolean("user_endpoint", false),
                authType = str(auth, "type"), authNote = str(auth, "note"), signup = str(auth, "signup"), keyIn = keyIn,
                connector = str(o, "connector"), feeds = feedsOf(o.optJSONArray("feeds")),
                priority = o.optInt("priority", 20), primary = o.optBoolean("primary", false), fleetEndpoint = str(o, "fleet_endpoint"),
                paths = pathsOf(o.optJSONObject("paths")), attribution = str(o, "attribution"),
            )
        }
    }

    /** The categories in the order the catalog declares them, then any other a source uses. */
    fun categories(json: String, sources: List<Source>): List<String> {
        val declared = runCatching { JSONObject(json).optJSONArray("categories") }.getOrNull()
        val first = if (declared == null) emptyList() else (0 until declared.length()).map { declared.optString(it) }
        return (first + sources.map { it.category }).distinct().filter { c -> sources.any { it.category == c } }
    }

    fun byId(sources: List<Source>, id: String): Source? = sources.firstOrNull { it.id == id }

    /** Every source declared to feed [entry] of [tab]. */
    fun sourcesFor(sources: List<Source>, tab: String, entry: String): List<Source> = sources.filter { it.feeds(tab, entry) }

    /** The sources feeding [entry] of [tab], best first: lower priority number, then declared order. */
    fun ranked(sources: List<Source>, tab: String, entry: String): List<Source> = sourcesFor(sources, tab, entry).sortedBy { it.priority }

    /** The (tab, entry) pairs of [entries] that no source feeds: the guard wants this empty. */
    fun unmapped(sources: List<Source>, entries: List<Pair<String, String>>): List<Pair<String, String>> =
        entries.filter { (tab, title) -> sourcesFor(sources, tab, title).isEmpty() }

    /** A JSON string field, "" when absent or null (org.json answers the text "null" for a JSON null on Android). */
    fun str(o: JSONObject, key: String): String = if (o.isNull(key)) "" else o.optString(key, "")

    private fun pathsOf(o: JSONObject?): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        if (o != null) o.keys().forEach { m[it] = o.optString(it) }
        return m
    }

    private fun feedsOf(arr: JSONArray?): List<Feed> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { f ->
            val es = f.optJSONArray("entries")
            Feed(str(f, "tab"), if (es == null) emptyList() else (0 until es.length()).map { es.optString(it) })
        }
    }
}
