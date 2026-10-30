package com.diegonmarcos.cloudme

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/** What Achieves > Geo shows above its lists. Every figure is derived from the places, nothing is typed twice. */
data class GeoSummary(
    val cities: Int, val countries: Int, val continents: Int,
    val worldCountries: Int, val worldContinents: Int, val pctWorld: String,
    val first: String?, val last: String?, val wishlist: Int,
    /** Continent to (country to cities), each level sorted by name. */
    val byContinent: Map<String, Map<String, List<String>>>,
    /** The declared outside count (NomadMania), or null when the block declares none. */
    val declared: String?,
)

/**
 * The pure half of Achieves > Geo (#1350). The ledger block's `geo` object names the visited and wishlist collections
 * and the size of the world; visited places are the declared builtin rows plus the owner's records. A place is counted
 * once per (city, country), case and spaces ignored; every record of it still adds its dates. First visit is the earliest date or last-visit date, last visit
 * the latest; a place with no dates counts for the totals but not for the visit range.
 */
object GeoStats {
    fun compute(g: JSONObject, defs: List<LedgerDef>, d: LedgerData): GeoSummary {
        val visitedId = g.optString("visited"); val wishId = g.optString("wishlist")
        val visited = defs.filter { it.collection == visitedId }.flatMap { it.builtin } + d.rows(visitedId)
        val decl = g.optJSONObject("declared")?.let { o ->
            "${o.optString("label", "Declared")}: ${o.optInt("cities")} cities, ${o.optInt("countries")} countries, ${o.optInt("continents")} continents"
        }
        return summarize(visited, d.rows(wishId).size, g.optInt("world_countries", 195), g.optInt("world_continents", 7), decl)
    }

    private fun k(s: String?) = s.orEmpty().trim().lowercase()

    fun summarize(visited: List<Rec>, wishlist: Int, worldCountries: Int, worldContinents: Int, declared: String? = null): GeoSummary {
        val named = visited.filter { k(it["title"]).isNotEmpty() }
        val places = named.distinctBy { k(it["title"]) to k(it["country"]) }
        val countries = places.map { k(it["country"]) }.filter { it.isNotEmpty() }.toSet()
        val continents = places.map { k(it["continent"]) }.filter { it.isNotEmpty() }.toSet()
        val dates = named.flatMap { listOf(it["date"], it["last"]) }.filter { LedgerModel.isDate(it) }.map { it!!.trim() }.sorted()
        val pct = if (worldCountries <= 0) "-" else
            BigDecimal(countries.size * 100).divide(BigDecimal(worldCountries), 1, RoundingMode.HALF_UP).toPlainString() + "%"
        val grouped = places.groupBy { it["continent"]?.trim().orEmpty().ifEmpty { "Unknown continent" } }.toSortedMap()
            .mapValues { (_, ps) -> ps.groupBy { it["country"]?.trim().orEmpty().ifEmpty { "Unknown country" } }.toSortedMap()
                .mapValues { (_, cs) -> cs.map { it["title"]!!.trim() }.sorted() } }
        return GeoSummary(places.size, countries.size, continents.size, worldCountries, worldContinents, pct,
            dates.firstOrNull(), dates.lastOrNull(), wishlist, grouped, declared)
    }
}
