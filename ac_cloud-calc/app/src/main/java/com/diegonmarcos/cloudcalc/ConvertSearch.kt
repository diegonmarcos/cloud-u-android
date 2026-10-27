package com.diegonmarcos.cloudcalc

import java.text.Normalizer

/**
 * The Convert page's search box: one index over every convertible thing - each converter and its categories, the
 * units of the app's own tables (Network / Data), the foods and chapters of Nutrition, and the search terms
 * (aliases) build.json declares per mode. A hit names the converter to open, its category and, when known, the unit.
 * Pure, so the JVM suite holds the ranking.
 */
object ConvertSearch {
    /** [text] is what is matched; [title] / [detail] what a result row shows; [modeId] / [category] / [unit] where it jumps. */
    data class Entry(val text: String, val title: String, val detail: String, val modeId: String, val category: String, val unit: String = "")
    data class Hit(val entry: Entry, val score: Int)

    /** Kinds that are converters (a mode of another kind is never a search target). */
    val KINDS = setOf("converter", "data_converter", "nutrition")

    fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase().trim()

    fun index(modes: List<Declarations.Mode>, food: Nutrition.Data? = null): List<Entry> {
        val out = mutableListOf<Entry>()
        modes.filter { it.kind in KINDS && it.tab == "convert" }.forEach { m ->
            val first = m.defaults["category"] ?: m.categories.firstOrNull().orEmpty()
            out += Entry(m.label, m.label, "Convert", m.id, first)
            m.categories.forEach { c -> out += Entry(c, c, m.label, m.id, c) }
            m.unitSets.forEach { set ->
                set.units.forEach { u -> out += Entry(u.name + " " + u.label, u.label, m.label + " · " + set.category, m.id, set.category, u.name) }
            }
            m.search.forEach { raw ->
                val p = raw.split('|')
                val cat = p.getOrNull(2)?.takeIf { it.isNotBlank() } ?: first
                out += Entry(p[0], p[0], m.label + (if (cat != m.label) " · $cat" else ""), m.id, cat, p.getOrNull(1).orEmpty())
            }
            if (m.kind == "nutrition" && food != null) {
                val chapter = m.categories.firstOrNull().orEmpty()
                food.foods.forEach { f ->
                    out += Entry((listOf(f.name) + f.aliases).joinToString(" "), f.name, m.label + " · " + chapter, m.id, chapter, f.id)
                }
            }
        }
        return out
    }

    /** Entries matching [query], best first: whole-text, text prefix, word prefix, substring, then all words present. */
    fun search(entries: List<Entry>, query: String, limit: Int = 8): List<Hit> {
        val q = fold(query)
        if (q.isEmpty()) return emptyList()
        val words = q.split(Regex("\\s+"))
        return entries.mapNotNull { e ->
            val t = fold(e.text)
            val w = t.split(Regex("[^\\p{L}\\p{N}°]+")).filter { it.isNotEmpty() }
            val score = when {
                t == q || fold(e.title) == q || w.contains(q) -> 100
                t.startsWith(q) -> 80
                w.any { it.startsWith(q) } -> 60
                t.contains(q) -> 40
                words.size > 1 && words.all { t.contains(it) } -> 30
                else -> 0
            }
            if (score > 0) Hit(e, score) else null
        }.sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.entry.title.length }.thenBy { it.entry.title })
            .distinctBy { Triple(it.entry.modeId, it.entry.category, it.entry.unit + "|" + it.entry.title) }
            .take(limit)
    }
}
