package com.diegonmarcos.superapp.bottomnav

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * THE navigation declaration of the fleet (#868): `build.json::ui` as an app reads it at runtime.
 *
 *   ui.bottom_nav      [<section id>, ...]   at most [MAX_BOTTOM] ids, in display order. The island.
 *   ui.sections        [{id,label,icon,pages:[{id,label,icon,action?,pages?:[...]}]}]
 *   ui.default_section <section id>          one of ui.bottom_nav
 *
 * A section's `pages` are its TOP tab strip ([PageTabs]); a page's own `pages` are a SECOND strip
 * below it, to any depth (Cloud Me's Projects > Health > Workout > Gym is three deep). The app
 * bakes `UI_BOTTOM_NAV` (the JSON array text), `UI_SECTIONS_B64` (base64 of the sections array)
 * and `UI_DEFAULT_SECTION` into BuildConfig; [fromBuildConfig] turns them into this.
 *
 * Parsing is FAIL-SOFT: a malformed blob yields an empty declaration and an app that opens on a
 * blank page, never one that crashes on launch. The nav-shape guard (cloud-android-nav-shape-guard)
 * is what makes a malformed declaration a build failure instead.
 */
public const val MAX_BOTTOM: Int = 5

/** A tab. [pages] is non-empty only for a container tab, which holds a strip of its own. */
public data class NavPage(
    val id: String,
    val label: String,
    val icon: String = "",
    /** Non-blank: a LAUNCH tab (it leaves the page for [action]) rather than a destination. */
    val action: String = "",
    val pages: List<NavPage> = emptyList(),
) {
    /** The tab that actually renders when this one is selected, walked all the way down. */
    public fun leaf(): NavPage = pages.firstOrNull()?.leaf() ?: this
}

public data class NavSection(
    val id: String,
    val label: String,
    val icon: String = "",
    val pages: List<NavPage> = emptyList(),
) {
    /** The chain of tabs from the top strip down to [id], outermost first; empty when absent. */
    public fun path(id: String?): List<NavPage> = pathTo(pages, id)

    /** [id] resolved against the top strip AND every strip below it; the first page when [id]
     *  is not declared, null when the section has no pages. */
    public fun page(id: String?): NavPage? = path(id).lastOrNull() ?: pages.firstOrNull()

    /** Every declared page, depth-first, containers included. */
    public fun allPages(): List<NavPage> = flatten(pages)
}

public class NavDecl(
    /** `ui.bottom_nav`, in order, capped at [MAX_BOTTOM]. */
    public val bottomNav: List<String>,
    public val sections: List<NavSection>,
    /** `ui.default_section`; blank resolves to the first bottom-nav id. */
    public val defaultSection: String = "",
) {
    private val index: Map<String, NavSection> = sections.associateBy { it.id }

    public fun section(id: String?): NavSection? = if (id == null) null else index[id]

    /** The island's sections, in bottom_nav order; an id with no section is skipped. */
    public fun bottomSections(): List<NavSection> = bottomNav.mapNotNull { index[it] }

    /** Where the app opens: the declared default, else the first bar section, else the first section. */
    public fun default(): NavSection? =
        index[defaultSection] ?: bottomSections().firstOrNull() ?: sections.firstOrNull()

    /** The island's items for a View host ([BottomNavIslandView]): [icon] maps a declared icon
     *  name to a drawable resource, so the lib carries no app's icon vocabulary. */
    public fun viewItems(icon: (String) -> Int): List<BottomNavViewItem> =
        bottomSections().map { BottomNavViewItem(it.id, it.label, icon(it.icon)) }

    public companion object {
        public val EMPTY: NavDecl = NavDecl(emptyList(), emptyList())

        /**
         * From the three BuildConfig fields. [uiBottomNav] is the JSON array text of ui.bottom_nav
         * (a bare comma list is accepted too); [uiSectionsB64] is base64 of ui.sections.
         */
        @JvmStatic
        @JvmOverloads
        public fun fromBuildConfig(
            uiSectionsB64: String,
            uiBottomNav: String = "",
            uiDefaultSection: String = "",
        ): NavDecl = runCatching {
            val json = String(Base64.decode(uiSectionsB64, Base64.DEFAULT), Charsets.UTF_8)
            parse(JSONArray(json), uiBottomNav, uiDefaultSection)
        }.getOrDefault(EMPTY)

        /** From already-decoded JSON. [bottomNav] is JSON array text or a comma list. */
        @JvmStatic
        public fun parse(sections: JSONArray, bottomNav: String = "", defaultSection: String = ""): NavDecl {
            val parsed = (0 until sections.length()).mapNotNull { sections.optJSONObject(it)?.let(::section) }
            val bar = bottomIds(bottomNav).ifEmpty { parsed.map { it.id } }.take(MAX_BOTTOM)
            return NavDecl(bar, parsed, defaultSection)
        }

        private fun bottomIds(text: String): List<String> {
            val t = text.trim()
            if (t.isEmpty()) return emptyList()
            if (t.startsWith("[")) return runCatching {
                val a = JSONArray(t)
                (0 until a.length()).mapNotNull {
                    when (val v = a.opt(it)) {
                        is String -> v
                        is JSONObject -> v.optString("id").ifBlank { null }
                        else -> null
                    }
                }
            }.getOrDefault(emptyList())
            return t.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }

        private fun section(o: JSONObject): NavSection? {
            val id = o.optString("id").ifBlank { return null }
            return NavSection(id, o.optString("label", id), o.optString("icon"), pages(o.optJSONArray("pages")))
        }

        private fun pages(a: JSONArray?): List<NavPage> =
            if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").ifBlank { return@mapNotNull null }
                NavPage(id, o.optString("label", id), o.optString("icon"), o.optString("action"), pages(o.optJSONArray("pages")))
            }
    }
}

/** Depth-first: ids are unique per section, so the first hit is the only hit. */
private fun pathTo(pages: List<NavPage>, id: String?): List<NavPage> {
    for (p in pages) {
        if (p.id == id) return listOf(p)
        val below = pathTo(p.pages, id)
        if (below.isNotEmpty()) return listOf(p) + below
    }
    return emptyList()
}

private fun flatten(pages: List<NavPage>): List<NavPage> = pages.flatMap { listOf(it) + flatten(it.pages) }

/** The island's items for a Compose host ([BottomNavIsland]): [icon] maps a declared icon name to
 *  a painter, so the lib carries no app's icon vocabulary. */
@androidx.compose.runtime.Composable
public fun NavDecl.islandEntries(
    icon: @androidx.compose.runtime.Composable (String) -> androidx.compose.ui.graphics.painter.Painter,
): List<BottomNavEntry> = bottomSections().map { BottomNavEntry(it.id, it.label, icon(it.icon)) }
