package com.diegonmarcos.cloudme

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * #1298 Lnktree > Proj: the account's repository registry as a tree, areas -> projects -> [subcategories ->]
 * families -> repos. Nothing here is typed by hand: the outline is libs:repos' repos.outline.json (derived from
 * git-repos-master repos-github.json by libs/repos/sync.sh --refresh-outline), shipped as the asset of the same name,
 * and which chip is which area is build.json::ui.repo_tree. Pure model; RepoTreeScreen draws it.
 */
data class RepoLeaf(
    val code: String,
    /** The GitHub repository name. */
    val name: String,
    val url: String,
    val private: Boolean,
    val description: String,
)

/** One node of the outline. A container has [children]; a repo leaf has [repo] and no children. */
data class RepoNode(
    val code: String,
    val kind: String,
    val label: String,
    val icon: String = "",
    val description: String = "",
    val children: List<RepoNode> = emptyList(),
    val repo: RepoLeaf? = null,
    /** The registry's area flag `show_in` (consumer surfaces that list this area); empty off an area. */
    val showIn: Set<String> = emptySet(),
) {
    val isContainer: Boolean get() = children.isNotEmpty()

    /** Every container code below and including this node, depth-first. */
    fun containerCodes(): List<String> =
        if (!isContainer) emptyList() else listOf(code) + children.flatMap { it.containerCodes() }

    /** Every repo leaf below this node. */
    fun leaves(): List<RepoLeaf> = repo?.let { listOf(it) } ?: children.flatMap { it.leaves() }
}

object RepoOutline {
    const val SCHEMA = "cloud-repos-outline/v1"

    /** The areas of an outline text; a wrong schema or malformed text is an empty list, never a crash. */
    fun parse(text: String): List<RepoNode> = runCatching {
        val o = JSONObject(text)
        if (o.optString("_schema") != SCHEMA) return emptyList()
        o.optJSONArray("areas").objects().map(::node)
    }.getOrDefault(emptyList())

    private fun node(o: JSONObject): RepoNode {
        val kids = o.optJSONArray("children").objects().map(::node)
        val repos = o.optJSONArray("repos").objects().map { r ->
            val leaf = RepoLeaf(r.optString("code"), r.optString("name"), r.optString("url"), r.optBoolean("private"), r.optString("description"))
            RepoNode(leaf.code, "repo", leaf.name, description = leaf.description, repo = leaf)
        }
        return RepoNode(
            o.optString("code"), o.optString("kind"), o.optString("label").ifBlank { o.optString("name") },
            o.optString("icon"), o.optString("description"), kids + repos,
            showIn = o.optJSONArray("show_in")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet(),
        )
    }
}

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/** build.json::ui.repo_tree, plus the fleet apps (constellation repo_url -> package) app/build.gradle adds. */
data class RepoTreeConfig(
    val group: String,
    val outlineAsset: String,
    /** Area page id -> registry area code, in declared (registry) order. */
    val areas: List<Pair<String, String>>,
    val fleetApps: Map<String, String>,
) {
    fun areaCodeFor(pageId: String): String? = areas.firstOrNull { it.first == pageId }?.second

    /** The areas the full tree shows: the outline's own, as the registry flags them (`show_in` has [SURFACE]); no label is overridden here. */
    fun shown(outline: List<RepoNode>): List<RepoNode> = outline.filter { SURFACE in it.showIn }

    /** One area's tree (its projects), or empty when the outline lacks it. */
    fun area(outline: List<RepoNode>, code: String): RepoNode? = shown(outline).firstOrNull { it.code == code }

    /** What tapping a leaf does: the fleet app built from that repo, else its GitHub page. */
    fun target(leaf: RepoLeaf): RepoTarget =
        fleetApps[leaf.url.trimEnd('/')]?.let { RepoTarget.App(it, leaf.url) } ?: RepoTarget.Url(leaf.url)

    companion object {
        /** The registry `show_in` value of the areas Lnktree > Proj lists. */
        const val SURFACE = "lnktree"

        fun parse(o: JSONObject): RepoTreeConfig = RepoTreeConfig(
            group = o.optString("group"),
            outlineAsset = o.optString("outline_asset", "repos.outline.json"),
            areas = o.optJSONArray("areas").objects().map { it.optString("page") to it.optString("code") },
            fleetApps = o.optJSONArray("fleet_apps").objects()
                .associate { it.optString("url").trimEnd('/') to it.optString("package") },
        )

        /** The baked declaration (BuildConfig.UI_REPO_TREE_B64); fail-soft to "no Proj tree". */
        val baked: RepoTreeConfig by lazy {
            runCatching { parse(JSONObject(String(Base64.decode(BuildConfig.UI_REPO_TREE_B64, Base64.DEFAULT)))) }
                .getOrDefault(RepoTreeConfig("", "repos.outline.json", emptyList(), emptyMap()))
        }
    }
}

sealed class RepoTarget {
    data class App(val pkg: String, val fallbackUrl: String) : RepoTarget()
    data class Url(val url: String) : RepoTarget()
}

/** A visible row of the tree: the node and how deep it sits under the shown roots. */
data class RepoRow(val node: RepoNode, val depth: Int, val open: Boolean)

/**
 * The expand/collapse state of the tree: the set of OPEN container codes. Codes are the registry's (1, 1.2, 1.2.1 ...),
 * unique across the outline, so one set serves every area page and the full tree alike, and it survives a restart
 * through [RepoTreeStore].
 */
class RepoTreeModel(initial: Set<String> = emptySet()) {
    var expanded: Set<String> = initial
        private set

    fun isOpen(code: String): Boolean = code in expanded
    fun toggle(code: String) { expanded = if (code in expanded) expanded - code else expanded + code }
    fun expandAll(roots: List<RepoNode>) { expanded = expanded + roots.flatMap { it.containerCodes() } }
    fun collapseAll(roots: List<RepoNode>) { expanded = expanded - roots.flatMap { it.containerCodes() }.toSet() }

    /** The rows on screen: every root, and the children of each open container, depth-first. */
    fun rows(roots: List<RepoNode>): List<RepoRow> = buildList {
        fun walk(n: RepoNode, depth: Int) {
            val open = n.isContainer && isOpen(n.code)
            add(RepoRow(n, depth, open))
            if (open) n.children.forEach { walk(it, depth + 1) }
        }
        roots.forEach { walk(it, 0) }
    }
}

/** The persisted expansion state: one string set in the SharedPreferences store `repo_tree` (declared in fleet-config.json). */
class RepoTreeStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("repo_tree", Context.MODE_PRIVATE)
    fun load(): Set<String> = prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()
    fun save(expanded: Set<String>) { prefs.edit().putStringSet(KEY, expanded.toSet()).apply() }
    companion object { const val KEY = "expanded" }
}
