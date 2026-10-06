package com.diegonmarcos.cloudme

import android.content.Context
import android.util.Log
import android.util.Base64
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.NavSection
import org.json.JSONArray
import org.json.JSONObject

/**
 * The whole navigation graph of Cloud Me: libs:bottomnav's [NavDecl] (#868), read from
 * [BuildConfig.UI_BOTTOM_NAV] + [BuildConfig.UI_SECTIONS_B64] + [BuildConfig.UI_DEFAULT_SECTION],
 * which app/build.gradle bakes from build.json::ui.
 *
 * There is deliberately no other list of destinations in this app: the bottom bar, the drawer,
 * the toolbar gear and every tab strip are all built from [all]. A section moves between the bar
 * and the drawer by adding or removing its id in `ui.bottom_nav`, and a new tab is a new `pages`
 * entry plus the matching page file — no Kotlin edit, no menu resource, nothing to keep in step.
 *
 * What NavDecl does not carry is Cloud Me's two section extras, `toolbar` and `target`; they are
 * read from the same blob here. Parsing is fail-soft throughout.
 */
/** A tab. [NavPage.pages] is non-empty only for a container tab: Buro > Fin, or Projects > Health
 *  > Workout > Gym three strips deep. Depth is a decision taken in build.json. */
typealias Page = NavPage

/** A [NavSection] plus the two things only Cloud Me reads off it. */
data class Section(
    val nav: NavSection,
    /** Drawn as the toolbar gear instead of in the bar or drawer. */
    val toolbar: Boolean,
    /** Non-blank ⇒ this bar/drawer item is a launch target, not a page host: the string goes to
     *  [MainActivity.onTarget] and nothing in this app is shown. Wallet is the one today. */
    val target: String,
) {
    val id: String get() = nav.id
    val label: String get() = nav.label
    val icon: String get() = nav.icon
    val pages: List<Page> get() = nav.pages

    /** The tabs leading to [id], outermost first; empty when this section declares no such id.
     *  The last entry is the page, the entries before it are the strips drawn above it, and their
     *  ids are the folders the page's content file lives under. */
    fun path(id: String?): List<Page> = nav.path(id)

    /** Resolves a page id against the tab strip AND every strip below it. */
    fun page(id: String?): Page? = nav.page(id)
}

object Sections {

    private val decl: NavDecl by lazy {
        NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
    }
    private val entries: List<Section> by lazy { load() }
    private val index: Map<String, Section> by lazy { entries.associateBy { it.id } }

    fun all(): List<Section> = entries

    fun byId(id: String?): Section? = if (id == null) null else index[id]

    /** The bottom bar, in `ui.bottom_nav` order (the island holds at most five). */
    fun bottom(): List<Section> = decl.bottomSections().mapNotNull { index[it.id] }

    /** Everything reachable from the hamburger: the sections the bar has no room for. */
    fun drawer(): List<Section> {
        val inBar = bottom().map { it.id }.toSet()
        return entries.filter { !it.toolbar && it.id !in inBar }
    }

    fun toolbarSection(): Section? = entries.firstOrNull { it.toolbar }

    /** Where the app opens: `ui.default_section`, else the first bar section. */
    fun default(): Section? =
        decl.default()?.let { index[it.id] }?.takeIf { !it.toolbar }
            ?: bottom().firstOrNull()
            ?: entries.firstOrNull()

    /**
     * One page's content list, read from assets when the page opens.
     *
     * NOT baked into BuildConfig: the stacks grow with the data — a real
     * profile is tens of kilobytes on its own — and javac caps a String
     * constant at 64KB. The navigation shape is bounded and stays in
     * BuildConfig; the content is a file, read once per page open.
     *
     * A page lives one folder deeper per container tab above it, so the file
     * path IS the tab path: projects/health/workout/gym.json is Projects >
     * Health > Workout > Gym. Missing or malformed yields an empty list and a
     * page that says so, never a crash — the build already refuses a page
     * with no file, so reaching the fallback means the asset was lost after
     * the build, not that JSON went untested.
     */
    fun stack(ctx: Context, sectionId: String, pageId: String): JSONArray {
        val section = byId(sectionId) ?: return JSONArray()
        val chain = section.path(pageId).map { it.id }.ifEmpty { listOf(pageId) }
        val path = (listOf(sectionId) + chain).joinToString("/") + ".json"
        return runCatching {
            JSONArray(ctx.assets.open(path).bufferedReader().use { it.readText() })
        }.getOrElse {
            // Empty-and-quiet is how a wrong path stayed invisible through a
            // whole release: every page rendered its "Nothing here yet" state
            // and nothing said why. Still fail soft, but leave a trace.
            Log.w("Sections", "no page asset at $path", it)
            JSONArray()
        }
    }

    private fun load(): List<Section> = runCatching {
        val arr = JSONArray(String(Base64.decode(BuildConfig.UI_SECTIONS_B64, Base64.DEFAULT)))
        val extras = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.associateBy { it.optString("id") }
        decl.sections.map {
            Section(it, extras[it.id]?.optBoolean("toolbar", false) ?: false, extras[it.id]?.optString("target").orEmpty())
        }
    }.getOrDefault(emptyList())
}

/**
 * `extapp:<id>` → the package to launch, from build.json::ui.external_apps.
 *
 * Cloud Me links out far more than it stores, so this table decides whether a
 * tile does anything. An id missing here, or an app that is not installed,
 * makes the tile a no-op rather than a crash — which is also the honest
 * behaviour when the constellation member simply is not on this phone.
 */
object ExternalApps {

    private val packages: Map<String, String> by lazy {
        runCatching {
            val arr = JSONArray(String(Base64.decode(BuildConfig.UI_EXTERNAL_APPS_B64, Base64.DEFAULT)))
            val out = mutableMapOf<String, String>()
            for (i in 0 until arr.length()) {
                val o: JSONObject = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val pkg = o.optString("package")
                if (id.isNotBlank() && pkg.isNotBlank()) out[id] = pkg
            }
            out
        }.getOrDefault(emptyMap())
    }

    /** Target grammar is `extapp:<id>` or `extapp:<id>/<hint>`; the hint after
     *  the slash is a destination inside the other app that no launch intent
     *  can currently express, so it is parsed off and ignored rather than
     *  turned into a package lookup that would always miss. */
    fun packageFor(target: String): String? =
        packages[target.removePrefix("extapp:").substringBefore('/')]

    fun launch(ctx: Context, target: String): Boolean {
        val pkg = packageFor(target) ?: return false
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        ctx.startActivity(intent)
        return true
    }
}
