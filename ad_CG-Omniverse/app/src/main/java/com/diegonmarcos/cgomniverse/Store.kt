package com.diegonmarcos.cgomniverse

import org.json.JSONObject

/** One world of data/store.json (generated from data/fwa.md by data/gen-store.py): the Store lists it, Galaxy loads it. */
data class World(
    val id: String,
    val name: String,
    val url: String,
    val type: String,
    val dimension: String,
    val framework: String,
    val stack: List<String>,
    val vr: Boolean,
    val description: String,
)

/** A filter group of the Store: its chips (All aside) in order, and the chips a world counts for in this group. */
enum class FilterGroup(val chips: List<String>) {
    TYPE(listOf(Store.GAMES, Store.WEBSITES, Store.OTHERS)),
    DIMENSION(listOf(Store.D3, Store.D2)),
    DEVICE(listOf(Store.FLAT, Store.VR_GLASS));
}

/**
 * The Store's catalogue and its filters. Each group is a multi-select chip row whose chips are complementary: a world passes a
 * group when it counts for at least one selected chip, and passes the filter when it passes every group. "All" is not a chip of
 * its own: it reads selected exactly when every chip of the group is, and tapping it selects them all (or clears them when they
 * already are). Everything is selected by default. Pure, so the JVM suite and its mutants cover it.
 */
object Store {
    const val GAMES = "Games"
    const val WEBSITES = "Websites"
    const val OTHERS = "Others"
    const val D3 = "3D"
    const val D2 = "2D"
    const val FLAT = "Flat Screen"
    const val VR_GLASS = "VR Glass"

    fun parse(json: String): List<World> {
        val arr = runCatching { JSONObject(json).optJSONArray("entries") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
            val id = o.optString("id"); val url = o.optString("url")
            if (id.isEmpty() || url.isEmpty()) return@mapNotNull null
            val st = o.optJSONArray("stack")
            World(
                id = id, name = o.optString("name"), url = url, type = o.optString("type"), dimension = o.optString("dimension"),
                framework = o.optString("framework"),
                stack = if (st == null) emptyList() else (0 until st.length()).map { st.optString(it) },
                vr = o.optBoolean("vr", false), description = o.optString("description"),
            )
        }
    }

    /** The chips of [group] that [w] counts for: 2D/3D and 3D/Video are both 2D and 3D; VR Yes is VR Glass and Flat Screen, VR No Flat Screen only. */
    fun tags(w: World, group: FilterGroup): Set<String> = when (group) {
        FilterGroup.TYPE -> when (w.type) {
            "Game" -> setOf(GAMES)
            "Website" -> setOf(WEBSITES)
            "Others" -> setOf(OTHERS)
            else -> emptySet()
        }
        FilterGroup.DIMENSION -> when (w.dimension) {
            "3D" -> setOf(D3)
            "2D" -> setOf(D2)
            "2D/3D", "3D/Video" -> setOf(D2, D3)
            else -> emptySet()
        }
        FilterGroup.DEVICE -> if (w.vr) setOf(FLAT, VR_GLASS) else setOf(FLAT)
    }

    /** The selection everything starts with: every chip of every group. */
    fun allSelected(): Map<FilterGroup, Set<String>> = FilterGroup.entries.associateWith { it.chips.toSet() }

    fun isAll(sel: Map<FilterGroup, Set<String>>, group: FilterGroup): Boolean = sel[group].orEmpty().containsAll(group.chips)

    /** Tapping one chip toggles it alone; the other chips of the group keep their state. */
    fun toggle(sel: Map<FilterGroup, Set<String>>, group: FilterGroup, chip: String): Map<FilterGroup, Set<String>> {
        if (chip !in group.chips) return sel
        val cur = sel[group].orEmpty()
        return sel + (group to (if (chip in cur) cur - chip else cur + chip))
    }

    /** Tapping All selects every chip of the group, or clears the group when every chip already is. */
    fun toggleAll(sel: Map<FilterGroup, Set<String>>, group: FilterGroup): Map<FilterGroup, Set<String>> =
        sel + (group to (if (isAll(sel, group)) emptySet() else group.chips.toSet()))

    fun matches(w: World, sel: Map<FilterGroup, Set<String>>): Boolean =
        FilterGroup.entries.all { g -> tags(w, g).any { it in sel[g].orEmpty() } }

    fun filter(worlds: List<World>, sel: Map<FilterGroup, Set<String>>): List<World> = worlds.filter { matches(it, sel) }
}
