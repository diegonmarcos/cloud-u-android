package com.diegonmarcos.cloudme

import android.content.Context
import android.util.Log
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * The whole navigation graph of Cloud Me, decoded once from
 * [BuildConfig.UI_SECTIONS_B64] — which app/build.gradle bakes from
 * build.json::ui.sections at build time.
 *
 * There is deliberately no other list of destinations in this app: the bottom
 * bar, the drawer, the toolbar gear and every tab strip are all built from
 * [all]. A section moves between the bar and the drawer by flipping one
 * boolean in JSON, and a new tab is a new `pages` entry plus the matching
 * `stack_<id>` — no Kotlin edit, no menu resource, nothing to keep in step.
 *
 * Parsing is fail-soft throughout. A malformed blob yields an empty list and
 * an app that opens on a blank page, never one that crashes on launch.
 */
/** A tab. [pages] is non-empty only for a container tab — one that holds a
 *  strip of its own instead of content, which is what Buro > Fin is: Acct,
 *  Budget and Portfolio are three views of one question and do not each
 *  deserve a top-level tab.
 *
 *  A container may hold containers. Projects > Health > Workout > Gym is
 *  three strips deep, because the plan belongs under the training it plans
 *  and the training belongs under the body it trains. Depth is a decision
 *  taken in build.json; nothing here or in the shell caps it. */
data class Page(
    val id: String,
    val label: String,
    val icon: String,
    val pages: List<Page> = emptyList(),
) {
    /** The tab that actually renders when this one is selected — walked all
     *  the way down, since the first child may itself be a container. */
    fun leaf(): Page = pages.firstOrNull()?.leaf() ?: this
}

/** The chain of tabs from [pages] down to [id], or empty when no branch
 *  carries it. Depth-first: ids are unique per section (test-ui-pages.sh
 *  fails when they are not), so the first hit is the only hit. */
private fun pathTo(pages: List<Page>, id: String?): List<Page> {
    for (p in pages) {
        if (p.id == id) return listOf(p)
        val below = pathTo(p.pages, id)
        if (below.isNotEmpty()) return listOf(p) + below
    }
    return emptyList()
}

data class Section(
    val id: String,
    val label: String,
    val icon: String,
    val bottomNav: Boolean,
    val toolbar: Boolean,
    /** The section the app opens on, and the one Back walks home to. Exactly
     *  one section should set it; without it the app lands on the first bar
     *  item, which is an accident of `order` rather than a decision. */
    val isDefault: Boolean,
    val order: Int,
    /** Non-blank ⇒ this bar/drawer item is a launch target, not a page host:
     *  the string goes to [MainActivity.onTarget] and nothing in this app is
     *  shown. Wallet is the one today — the card deck lives in Cloud Wallet,
     *  and a bar slot pointing at it beats a second copy of it here. */
    val target: String,
    val pages: List<Page>,
) {
    /** The tabs leading to [id], outermost first — Health, Workout, Gym for
     *  `page:projects/gym`. Empty when this section declares no such id.
     *
     *  It is the ONE resolution the shell needs at any depth: the last entry
     *  is the page, the entries before it are the strips drawn above it, and
     *  their ids are the folders the page's content file lives under. */
    fun path(id: String?): List<Page> = pathTo(pages, id)

    /** Resolves a page id against the tab strip AND every strip below it, so
     *  a `page:buro/acct` target lands on a sub-page as readily as on a tab. */
    fun page(id: String?): Page? = path(id).lastOrNull() ?: pages.firstOrNull()
}

object Sections {

    private val entries: List<Section> by lazy { load() }
    private val index: Map<String, Section> by lazy { entries.associateBy { it.id } }

    fun all(): List<Section> = entries

    fun byId(id: String?): Section? = if (id == null) null else index[id]

    /** The bottom bar, in declared `order`. Capped at five because Material's
     *  BottomNavigationView silently drops the sixth item — a cap that fails
     *  loudly in JSON review beats one that fails invisibly on the phone. */
    fun bottom(): List<Section> =
        entries.filter { it.bottomNav }.sortedBy { it.order }.take(5)

    /** Everything reachable from the hamburger: the sections the bar has no
     *  room for, plus any bottom-bar overflow beyond five. */
    fun drawer(): List<Section> {
        val inBar = bottom().map { it.id }.toSet()
        return entries.filter { !it.toolbar && it.id !in inBar }
    }

    fun toolbarSection(): Section? = entries.firstOrNull { it.toolbar }

    /** Where the app opens. A section that declares `default` wins; otherwise
     *  the first item in the bar, which is where it used to always land. */
    fun default(): Section? =
        entries.firstOrNull { it.isDefault && !it.toolbar }
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

    /** `pages` all the way down. The shell draws one strip per level it is
     *  handed, so a depth limit here would be a cap on what build.json is
     *  allowed to express — and the last one silently dropped the third
     *  level rather than refusing it. */
    private fun parsePages(arr: JSONArray?): List<Page> {
        val out = mutableListOf<Page>()
        for (j in 0 until (arr?.length() ?: 0)) {
            val p = arr!!.optJSONObject(j) ?: continue
            val pid = p.optString("id")
            if (pid.isBlank()) continue
            out.add(Page(
                id = pid,
                label = p.optString("label", pid),
                icon = p.optString("icon"),
                pages = parsePages(p.optJSONArray("pages")),
            ))
        }
        return out
    }

    private fun load(): List<Section> = runCatching {
        val arr = JSONArray(String(Base64.decode(BuildConfig.UI_SECTIONS_B64, Base64.DEFAULT)))
        val out = mutableListOf<Section>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue

            val pages = parsePages(o.optJSONArray("pages"))

            out.add(
                Section(
                    id = id,
                    label = o.optString("label", id),
                    icon = o.optString("icon"),
                    bottomNav = o.optBoolean("bottom_nav", false),
                    toolbar = o.optBoolean("toolbar", false),
                    isDefault = o.optBoolean("default", false),
                    order = o.optInt("order", Int.MAX_VALUE),
                    target = o.optString("target"),
                    pages = pages,
                )
            )
        }
        out
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
