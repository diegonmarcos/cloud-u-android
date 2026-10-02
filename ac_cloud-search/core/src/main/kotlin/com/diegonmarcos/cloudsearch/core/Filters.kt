package com.diegonmarcos.cloudsearch.core

import java.util.Locale

/**
 * What the user narrowed a listing to: the two basic chips (Recent, Verified), the vertical's
 * declared chips ([chips] = their ids), and the Extensive Filters sheet (sort, price range).
 */
data class Filters(
    val recent: Boolean = false,
    val verified: Boolean = false,
    val chips: Set<String> = emptySet(),
    val sort: Sort = Sort.RELEVANCE,
    val min: Double? = null,
    val max: Double? = null,
) {
    enum class Sort(val id: String) { RELEVANCE("relevance"), NEWEST("newest"), PRICE_ASC("price_asc"), PRICE_DESC("price_desc");
        companion object { fun of(id: String?): Sort = entries.firstOrNull { it.id == id } ?: RELEVANCE }
    }

    /** [list] in source order (relevance) narrowed and sorted. A price bound drops listings that have no price. */
    fun apply(list: List<Listing>, vertical: SearchConfig.Vertical, recentDays: Int, now: Long): List<Listing> {
        val cutoff = now - recentDays * DAY_MS
        val active = vertical.chips.filter { it.id in chips }
        val kept = list.filter { l ->
            (!recent || (l.date != null && l.date >= cutoff)) &&
                (!verified || l.verified) &&
                (min == null || (l.price != null && l.price >= min)) &&
                (max == null || (l.price != null && l.price <= max)) &&
                active.all { matches(it, l) }
        }
        return when (sort) {
            Sort.RELEVANCE -> kept
            Sort.NEWEST -> kept.sortedByDescending { it.date ?: Long.MIN_VALUE }
            Sort.PRICE_ASC -> kept.sortedWith(compareBy<Listing, Double?>(nullsLast()) { it.price })
            Sort.PRICE_DESC -> kept.sortedWith(compareBy<Listing, Double?>(nullsLast(reverseOrder())) { it.price })
        }
    }

    companion object {
        const val DAY_MS = 86_400_000L

        /** A declared chip: `flag` names a boolean of the listing, `tag` a tag it must carry (case-insensitive). */
        fun matches(chip: SearchConfig.ChipSpec, l: Listing): Boolean = when {
            chip.flag == "remote" -> l.remote == true
            chip.flag == "verified" -> l.verified
            chip.flag == "priced" -> l.price != null
            chip.tag.isNotBlank() -> l.tags.any { it.equals(chip.tag, ignoreCase = true) }
            else -> false
        }

        /** The flags a chip may name; the shell tester holds every declared chip flag to this list. */
        val FLAGS = listOf("remote", "verified", "priced")

        /** Local text filter for sources whose API cannot search: every word of [q] in the card's text. */
        fun textMatch(l: Listing, q: String): Boolean {
            val hay = (l.title + " " + l.subtitle + " " + l.tags.joinToString(" ") + " " + (l.location ?: "")).lowercase(Locale.ROOT)
            return q.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }.all { it in hay }
        }

        /** Local city filter: the listing's place is the city (or one of its aliases), or the job is remote. */
        fun cityMatch(l: Listing, city: SearchConfig.City): Boolean {
            if (l.remote == true) return true
            val where = (l.location ?: return false).lowercase(Locale.ROOT)
            return (listOf(city.label) + city.aliases).any { where.contains(it.lowercase(Locale.ROOT)) }
        }
    }
}
