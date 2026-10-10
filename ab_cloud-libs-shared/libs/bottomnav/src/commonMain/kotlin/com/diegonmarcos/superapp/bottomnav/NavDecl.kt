package com.diegonmarcos.superapp.bottomnav

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * THE navigation declaration of the fleet (#868): `build.json::ui` as an app reads it at runtime.
 *
 *   ui.bottom_nav      [<section id>, ...]   [MIN_BOTTOM]..[MAX_BOTTOM] ids, in display order. The island.
 *   ui.sections        [{id,label,icon,background?,pages:[{id,label,icon,action?,background?,pages?:[...]}]}]
 *   ui.default_section <section id>          one of ui.bottom_nav
 *   ui.style           "fleet" (default) | "search-html"   see [NavStyle]; only the apps nav-shape.json::variants
 *                                            names may declare anything but the default (rule N8)
 *
 * A section's `pages` are its TOP tab strip ([PageTabs]); a page's own `pages` are a SECOND strip
 * below it, to any depth (Cloud Me's Projects > Health > Workout > Gym is three deep). The app
 * bakes `UI_BOTTOM_NAV` (the JSON array text), `UI_SECTIONS_B64` (base64 of the sections array)
 * and `UI_DEFAULT_SECTION` into BuildConfig; [fromBuildConfig] turns them into this.
 *
 * A section's or page's `background` ([PageBackground]: "dark" default, "light", or "theme" = the
 * app's own light/dark mode) is what its tab strip is drawn over, which picks the strip's lib-owned
 * [TabSurface]. Unset on a page = its parent's.
 *
 * Parsing is FAIL-SOFT: a malformed blob yields an empty declaration and an app that opens on a
 * blank page, never one that crashes on launch. The nav-shape guard (cloud-android-nav-shape-guard)
 * is what makes a malformed declaration a build failure instead; [NavDecl.problems] states the same
 * rules at runtime.
 */

/**
 * The most items the island holds (the owner lifted the old cap of 5, 2026-10). The island keeps
 * equal cells while they fit and above [EQUAL_CELLS_BOTTOM] items scrolls in 56dp cells
 * (bottom_nav_min_cell_width) inside its 80% pill, so a count is a discoverability limit, not a
 * layout one: on a 360dp phone the pill shows about five cells, and at 8 the bar is already
 * 448dp of cells, three of them off-screen at any time. A 9th item would put ~45% of the bar out of
 * sight, which is a menu, not a bar. Cloud Code (7, its own Cordova nav) is the widest declaration.
 */
public const val MAX_BOTTOM: Int = 8

/** The fewest items the island holds: one item is no choice, so it is no navigation. */
public const val MIN_BOTTOM: Int = 2

/** Up to this many items the island's cells only have to clear the 48dp touch floor; above it a
 *  cell that would fall under bottom_nav_min_cell_width (56dp) makes the island scroll instead. */
public const val EQUAL_CELLS_BOTTOM: Int = 5

/**
 * What a tab strip is drawn over, as `build.json::ui` declares it (`sections[].background`,
 * `pages[].background`). [Theme] = the page follows the app's own light/dark mode, which only the
 * app knows; [surface] turns it into the strip's [TabSurface].
 */
public enum class PageBackground(public val declared: String) {
    Dark("dark"),
    Light("light"),
    Theme("theme");

    public fun surface(darkTheme: Boolean): TabSurface = when (this) {
        Dark -> TabSurface.Dark
        Light -> TabSurface.Light
        Theme -> if (darkTheme) TabSurface.Dark else TabSurface.Light
    }

    public companion object {
        /** A declared `background`; blank is null (inherit), anything unknown is null too (the guard rejects it). */
        public fun of(text: String?): PageBackground? =
            if (text.isNullOrBlank()) null else entries.firstOrNull { it.declared == text.trim() }
    }
}

/**
 * Which look the island is drawn in (`build.json::ui.style`). [Fleet] is SuperApp's island and every
 * app's; [SearchHtml] is the ONE declared exception (the owner asked, 2026-10): Cloud Search's bar from
 * his HTML mockup, a variant of this library ([SearchHtmlIsland]) and not an app's own nav. The nav-shape
 * guard (rule N8) lets only the apps nav-shape.json::variants names declare it.
 */
public enum class NavStyle(public val declared: String) {
    Fleet("fleet"),
    SearchHtml("search-html");

    public companion object {
        /** A declared `ui.style`; blank is [Fleet], anything unknown is null (the guard rejects it). */
        public fun of(text: String?): NavStyle? =
            if (text.isNullOrBlank()) Fleet else entries.firstOrNull { it.declared == text.trim() }
    }
}

/** A tab. [pages] is non-empty only for a container tab, which holds a strip of its own. */
public data class NavPage(
    val id: String,
    val label: String,
    val icon: String = "",
    /** Non-blank: a LAUNCH tab (it leaves the page for [action]) rather than a destination. */
    val action: String = "",
    val pages: List<NavPage> = emptyList(),
    /** What this page's own sub-strip is drawn over; null = its parent's. */
    val background: PageBackground? = null,
) {
    /** The tab that actually renders when this one is selected, walked all the way down. */
    public fun leaf(): NavPage = pages.firstOrNull()?.leaf() ?: this
}

public data class NavSection(
    val id: String,
    val label: String,
    val icon: String = "",
    val pages: List<NavPage> = emptyList(),
    /** What this section's tab strip is drawn over ([PageBackground.Dark] unless declared). */
    val background: PageBackground = PageBackground.Dark,
) {
    /**
     * The strip [TabSurface] for this section's top strip ([pageId] null) or for the sub-strip of the
     * container page [pageId]: the nearest declared background on the path, else the section's.
     * [darkTheme] is the app's own light/dark decision, read only for a "theme" background.
     */
    public fun stripSurface(darkTheme: Boolean, pageId: String? = null): TabSurface =
        (path(pageId).lastOrNull { it.background != null }?.background ?: background).surface(darkTheme)

    /** The chain of tabs from the top strip down to [id], outermost first; empty when absent. */
    public fun path(id: String?): List<NavPage> = pathTo(pages, id)

    /** [id] resolved against the top strip AND every strip below it; the first page when [id]
     *  is not declared, null when the section has no pages. */
    public fun page(id: String?): NavPage? = path(id).lastOrNull() ?: pages.firstOrNull()

    /** Every declared page, depth-first, containers included. */
    public fun allPages(): List<NavPage> = flatten(pages)
}

public class NavDecl(
    /** `ui.bottom_nav`, in order, as declared ([bottomSections] draws at most [MAX_BOTTOM]). */
    public val bottomNav: List<String>,
    public val sections: List<NavSection>,
    /** `ui.default_section`; blank resolves to the first bottom-nav id. */
    public val defaultSection: String = "",
    /** `ui.style`: which look the island is drawn in. Fleet for every app but the declared exception. */
    public val style: NavStyle = NavStyle.Fleet,
) {
    private val index: Map<String, NavSection> = sections.associateBy { it.id }

    public fun section(id: String?): NavSection? = if (id == null) null else index[id]

    /** The island's sections, in bottom_nav order, at most [MAX_BOTTOM]; an id with no section is skipped. */
    public fun bottomSections(): List<NavSection> = bottomNav.take(MAX_BOTTOM).mapNotNull { index[it] }

    /** The strip surface for section [sectionId] (see [NavSection.stripSurface]); Dark when it is not declared. */
    public fun stripSurface(sectionId: String?, darkTheme: Boolean, pageId: String? = null): TabSurface =
        section(sectionId)?.stripSurface(darkTheme, pageId) ?: TabSurface.Dark

    /**
     * What is wrong with this declaration, as the nav-shape guard's N1 says it (empty = valid):
     * [MIN_BOTTOM]..[MAX_BOTTOM] bar ids, each a section, and a default that is one of them. Parsing
     * never throws, so this is how a test (or a debug screen) asks whether a declaration would pass.
     */
    public fun problems(): List<String> = buildList {
        if (bottomNav.size < MIN_BOTTOM) add("ui.bottom_nav has ${bottomNav.size} ids, the island needs at least $MIN_BOTTOM")
        if (bottomNav.size > MAX_BOTTOM) add("ui.bottom_nav has ${bottomNav.size} ids, the island holds at most $MAX_BOTTOM")
        bottomNav.filter { it !in index }.forEach { add("ui.bottom_nav id '$it' is not a ui.sections id") }
        if (defaultSection.isNotBlank() && defaultSection !in bottomNav) add("ui.default_section '$defaultSection' is not one of ui.bottom_nav")
    }

    /** Where the app opens: the declared default, else the first bar section, else the first section. */
    public fun default(): NavSection? =
        index[defaultSection] ?: bottomSections().firstOrNull() ?: sections.firstOrNull()

    /** The island's items for a View host (Android's [BottomNavIslandView]): [icon] maps a declared
     *  icon name to a drawable resource, so the lib carries no app's icon vocabulary. */
    public fun viewItems(icon: (String) -> Int): List<BottomNavViewItem> =
        bottomSections().map { BottomNavViewItem(it.id, it.label, icon(it.icon)) }

    public companion object {
        public val EMPTY: NavDecl = NavDecl(emptyList(), emptyList())

        /**
         * From the three BuildConfig fields. [uiBottomNav] is the JSON array text of ui.bottom_nav
         * (a bare comma list is accepted too); [uiSectionsB64] is base64 of ui.sections.
         */
        @OptIn(ExperimentalEncodingApi::class)
        public fun fromBuildConfig(
            uiSectionsB64: String,
            uiBottomNav: String = "",
            uiDefaultSection: String = "",
            uiStyle: String = "",
        ): NavDecl = runCatching {
            // Android's decoder skipped whitespace and tolerated missing padding; so does this.
            val b64 = Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
            val json = b64.decode(uiSectionsB64.filterNot { it.isWhitespace() }).decodeToString()
            parse(json, uiBottomNav, uiDefaultSection, uiStyle)
        }.getOrDefault(EMPTY)

        /**
         * From the sections array. [sections] is its JSON text, or an object whose `toString()` is
         * that text: Android callers keep passing the `org.json.JSONArray` they already hold, and
         * this common code never names the type. [bottomNav] is JSON array text or a comma list.
         * Throws on anything that is not a JSON array; [fromBuildConfig] turns that into [EMPTY].
         */
        public fun parse(sections: Any, bottomNav: String = "", defaultSection: String = "", style: String = ""): NavDecl {
            val array = MiniJson.parse(sections.toString()) as? List<*>
                ?: throw IllegalArgumentException("ui.sections is not a JSON array")
            val parsed = array.mapNotNull { (it as? Map<*, *>)?.let(::section) }
            // Kept as declared, so [problems] can see a bar that is too long; the island draws MAX_BOTTOM.
            val bar = bottomIds(bottomNav).ifEmpty { parsed.map { it.id }.take(MAX_BOTTOM) }
            return NavDecl(bar, parsed, defaultSection, NavStyle.of(style) ?: NavStyle.Fleet)
        }

        private fun bottomIds(text: String): List<String> {
            val t = text.trim()
            if (t.isEmpty()) return emptyList()
            if (t.startsWith("[")) return runCatching {
                (MiniJson.parse(t) as List<*>).mapNotNull {
                    when (it) {
                        is String -> it
                        is Map<*, *> -> (it["id"] as? String)?.ifBlank { null }
                        else -> null
                    }
                }
            }.getOrDefault(emptyList())
            return t.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }

        private fun Map<*, *>.text(key: String, fallback: String = ""): String = (this[key] as? String) ?: fallback

        private fun section(o: Map<*, *>): NavSection? {
            val id = o.text("id").ifBlank { return null }
            return NavSection(id, o.text("label", id), o.text("icon"), pages(o["pages"]),
                PageBackground.of(o.text("background")) ?: PageBackground.Dark)
        }

        private fun pages(a: Any?): List<NavPage> =
            (a as? List<*>)?.mapNotNull { e ->
                val o = e as? Map<*, *> ?: return@mapNotNull null
                val id = o.text("id").ifBlank { return@mapNotNull null }
                NavPage(id, o.text("label", id), o.text("icon"), o.text("action"), pages(o["pages"]), PageBackground.of(o.text("background")))
            } ?: emptyList()
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

/** One item as a View-based host declares it: a stable id, its label, and a drawable resource id. */
public data class BottomNavViewItem(val id: String, val label: String, val icon: Int)
