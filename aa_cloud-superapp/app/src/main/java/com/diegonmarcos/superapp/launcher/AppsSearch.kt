package com.diegonmarcos.superapp.launcher

import java.text.Normalizer
import java.util.Locale

/**
 * The filter behind the search bar pinned to the bottom of Cloud ▸ Apps
 * ([GroupedTilesFragment]). Pure Kotlin over labelled groups, so the ranking
 * is tested without a screen (AppsSearchTest) and the fragment only draws
 * what this returns.
 *
 * Matching is case- and accent-insensitive ([fold]): "telecom" finds
 * "Télécom" and "AGI" finds "agi". An item ranks by the best of:
 *   [LABEL_PREFIX]   its label starts with the query
 *   [WORD_PREFIX]    a later word of its label does ("se" → "Cloud Search")
 *   [LABEL_CONTAINS] its label contains the query anywhere
 *   [GROUP_MATCH]    its GROUP's name contains the query ("configs" lists the row)
 * so prefixes come first, then contains, then whole groups.
 *
 * Groups with no hit are dropped. The rest keep their hits in rank order
 * (declared order among equals), and are themselves ordered by their best
 * hit, so the top-left tile of the result is [Result.top] — the one the
 * keyboard's Go launches.
 */
object AppsSearch {

    const val LABEL_PREFIX = 0
    const val WORD_PREFIX = 1
    const val LABEL_CONTAINS = 2
    const val GROUP_MATCH = 3

    data class Item<T>(val label: String, val value: T)
    data class Group<T>(val title: String, val items: List<Item<T>>)
    data class Result<T>(val groups: List<Group<T>>) {
        /** The best hit — what Go launches; null when nothing matched. */
        val top: Item<T>? get() = groups.firstOrNull()?.items?.firstOrNull()
        val isEmpty: Boolean get() = groups.isEmpty()
    }

    private val MARKS = Regex("\\p{Mn}+")
    private val WORD_BREAK = Regex("[^\\p{L}\\p{N}]+")

    /** Lower-case, accents stripped, outer blanks trimmed: the form both sides are compared in. */
    fun fold(s: String): String =
        MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(Locale.ROOT).trim()

    /** True when [query] filters anything at all; a blank query is the full grid. */
    fun isActive(query: String): Boolean = fold(query).isNotEmpty()

    /** The rank of one item for an already-[fold]ed query, or null when it does not match. */
    fun rank(foldedQuery: String, label: String, group: String): Int? {
        if (foldedQuery.isEmpty()) return null
        val l = fold(label)
        return when {
            l.startsWith(foldedQuery) -> LABEL_PREFIX
            l.split(WORD_BREAK).any { it.isNotEmpty() && it.startsWith(foldedQuery) } -> WORD_PREFIX
            l.contains(foldedQuery) -> LABEL_CONTAINS
            fold(group).contains(foldedQuery) -> GROUP_MATCH
            else -> null
        }
    }

    fun <T> filter(groups: List<Group<T>>, query: String): Result<T> {
        val q = fold(query)
        if (q.isEmpty()) return Result(emptyList())
        val ranked = groups.mapNotNull { g ->
            val hits = g.items.mapNotNull { item -> rank(q, item.label, g.title)?.let { it to item } }
                .sortedBy { it.first }  // stable: declared order among equal ranks
            if (hits.isEmpty()) null else hits.first().first to Group(g.title, hits.map { it.second })
        }
        return Result(ranked.sortedBy { it.first }.map { it.second })
    }
}
