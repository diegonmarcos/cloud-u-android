package com.diegonmarcos.superapp.appstore

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.util.Base64
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.devtools.FleetPeers
import com.diegonmarcos.superapp.devtools.FleetToken
import com.diegonmarcos.superapp.updater.Fleet
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * #733 APPS MESH — the ONE page behind two entry points: Store ▸ Apps Mesh
 * (StoreCloudFragment.renderMesh) and Configs ▸ Watchdog ▸ Mesh ▸ Apps Mesh
 * ([AppsMeshFragment], page id `apps-mesh`). Both call [page]; neither draws a
 * copy, so the two cannot drift.
 *
 * What it adds over #728's mesh drawing ([StoreMesh.render], still the node
 * and link renderer):
 *   - each member is tappable for its actions (API · Start · Stop · Open ·
 *     Details), declared in assets/appstore-controls.json::apps_mesh.member_actions
 *   - the MISSING MEMBERSHIP report ([gaps]): every installed fleet app that is
 *     not a full mesh member, each gap with its fix
 *   - Export / Copy all of the whole page as text ([report])
 *   - #792 each member's full address and its own /api/docs, unfoldable per
 *     member, and Export endpoints: the whole fleet's [catalogue] as JSON —
 *     the same body the SuperApp serves at /api/fleet/endpoints
 *   - #793 the Store's OWN controls: every tool and every member's row of
 *     buttons is [StoreBar.button], the filter chips [StoreBar.chip];
 *     filters ([FILTERS]) with counts; drawn at once from the roster and the
 *     last cached probe ([readCache]), then filled member by member as
 *     [StoreMesh.probeEach] returns; endpoints fetched only when a row's Docs
 *     opens; All endpoints, one searchable view of every member's routes
 *   - #809 three kinds of control, declared as data (`apps_mesh.controls`,
 *     each with a `type` and the `scope` page it sits on) and drawn in three
 *     groups: PAGE buttons ([StoreBar.page]: App API Endpoints, Missing
 *     membership, a member's Details), ACTION buttons ([StoreBar.button]:
 *     Re-probe, Wake all; Export / Copy on the sub-page they act on), FILTER
 *     chips ([StoreBar.chip])
 *
 * Every word on it is the asset's; this file names no member, package or caption.
 */
object AppsMesh {

    const val TAG_GAP = "apps-mesh-gap:"
    /** A non-member control: "$TAG_TOOL<scope>:<id>". */
    const val TAG_TOOL = "apps-mesh-tool:"
    /** #809 a group of one kind of control: "$TAG_GROUP<scope>:<type>". */
    const val TAG_GROUP = "apps-mesh-group:"
    /** #809 the sub-page being shown: "$TAG_SUB<page id>". */
    const val TAG_SUB = "apps-mesh-sub:"
    /** #809 the Details sub-page's body: "$TAG_DETAILS<member id>". */
    const val TAG_DETAILS = "apps-mesh-details:"
    /** #793 a member's row button: "$TAG_ACTION<action id>:<member id>". */
    const val TAG_ACTION = "apps-mesh-action:"
    const val TAG_FILTER = "apps-mesh-filter:"
    const val TAG_DOCS = "apps-mesh-docs:"
    const val TAG_AGE = "apps-mesh-age"

    /** #793 the filter ids [matches] implements; anything else declared is dropped. */
    val FILTERS = listOf("all", "reachable", "engine", "running", "stopped")

    /** #809 the three kinds of control, and so the three groups a page draws:
     *  `page` navigates, `action` does, `filter` narrows. Nothing else is drawn. */
    val TYPES = listOf("page", "action", "filter")

    /** #809 per scope (the page a control sits on), the control ids this file
     *  implements; anything else declared is dropped, so a misspelt id cannot
     *  ship as a control that does nothing. */
    val HANDLED: Map<String, List<String>> = mapOf(
        "root" to listOf("endpoints", "gaps", "reprobe", "wake") + FILTERS,
        "member" to listOf("api", "start", "stop", "open", "details", "store"),
        "endpoints" to listOf("json", "markdown", "copy"),
        "gaps" to listOf("export", "copy"),
        "details" to listOf("copy"),
        "sub" to listOf("back"),
    )

    class Action(val id: String, val label: String, val color: Int? = null,
                 val type: String = "action", val scope: String = "member", val icon: String = "")

    class Decl(val controls: List<Action>,
               val gapWords: Map<String, Pair<String, String>>, val exposure: String = "",
               private val groups: Map<String, String> = emptyMap(), private val words: Map<String, String> = emptyMap()) {
        /** The controls of [scope], optionally only those of [type], in declared order. */
        fun of(scope: String, type: String? = null) = controls.filter { it.scope == scope && (type == null || it.type == type) }
        /** Each member card's controls. */
        val actions: List<Action> get() = of("member")
        /** The root page's filter chips. */
        val filters: List<Action> get() = of("root", "filter")
        fun label(scope: String, id: String) = controls.firstOrNull { it.scope == scope && it.id == id }?.label ?: id
        /** The caption over a group of [type]. */
        fun group(type: String) = groups[type]?.takeIf { it.isNotEmpty() } ?: type
        /** One of the page's remaining sentences (`words`). */
        fun word(id: String) = words[id]?.takeIf { it.isNotEmpty() } ?: id
    }

    /** #809 the typed control list as data, for the debug API
     *  (/api/fleet/controls): every control that survived [decl]'s filter,
     *  in declared order, with its scope, type and caption — so a device check
     *  reads what the page draws without a screenshot. */
    fun controlsJson(decl: Decl): JSONObject {
        val arr = org.json.JSONArray()
        for (c in decl.controls) arr.put(JSONObject().put("scope", c.scope).put("id", c.id).put("type", c.type)
            .put("label", c.label).put("icon", c.icon)
            .put("color", c.color?.let { "0x%08X".format(it) } ?: JSONObject.NULL))
        val scopes = JSONObject()
        for ((s, ids) in HANDLED) scopes.put(s, org.json.JSONArray(ids))
        return JSONObject().put("ok", true).put("types", org.json.JSONArray(TYPES))
            .put("groups", JSONObject(TYPES.associateWith { decl.group(it) }))
            .put("handled", scopes).put("controls", arr).put("count", arr.length())
    }

    fun decl(controls: JSONObject): Decl {
        val m = controls.optJSONObject("apps_mesh") ?: JSONObject()
        val arr = m.optJSONArray("controls")
        fun strings(o: JSONObject?) = (o ?: JSONObject()).let { t ->
            t.keys().asSequence().filterNot { it.startsWith("_") }.associateWith { t.optString(it) } }
        val g = m.optJSONObject("gaps") ?: JSONObject()
        return Decl(
            (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it) }.map {
                Action(it.optString("id"), it.optString("label", it.optString("id")), argb(it.optString("color")),
                    it.optString("type"), it.optString("scope"), it.optString("icon"))
            }.filter { it.type in TYPES && it.id in HANDLED[it.scope].orEmpty() }
                // a filter is a root chip and only a root chip; a filter id is never a button
                .filter { (it.type == "filter") == (it.scope == "root" && it.id in FILTERS) },
            g.keys().asSequence().filterNot { it.startsWith("_") }.associateWith { k ->
                g.getJSONObject(k).let { it.optString("label", k) to it.optString("fix") } },
            m.optString("exposure"),
            strings(m.optJSONObject("groups")),
            strings(m.optJSONObject("words")))
    }

    private fun argb(v: String): Int? = v.takeIf { it.startsWith("0x") }?.removePrefix("0x")?.toLongOrNull(16)?.toInt()

    fun load(ctx: Context): Decl = runCatching {
        decl(JSONObject(ctx.assets.open(StoreControls.ASSET).use { it.readBytes().decodeToString() }))
    }.getOrElse { decl(JSONObject()) }

    /** What a tap on a member offers: the declared actions, minus `store` where
     *  the host has no Store row to open. */
    fun actionsFor(decl: Decl, hasStore: Boolean): List<Action> =
        decl.actions.filter { it.id != "store" || hasStore }

    // ── missing membership ───────────────────────────────────────────────────

    enum class GapKind { NO_PROVIDER, NO_DEBUG_API, NO_PERMISSION, PEER_BLIND, ENGINE_BROKEN }

    class Gap(val app: Fleet.App, val kind: GapKind, val label: String, val fix: String)

    /** The repo dir a fleet row builds from — the last segment of its repo_url. */
    fun repoOf(app: Fleet.App): String = app.repoUrl.trimEnd('/').substringAfterLast('/').ifEmpty { app.id }

    /**
     * Every way an INSTALLED fleet app falls short of full membership, judged
     * from the probe alone:
     *   NO_PROVIDER    no `${pkg}.fleet` provider — not a member at all (no
     *                  debug API, never in /api/fleet/peers); the root cause, so
     *                  the two symptoms are not listed again for it
     *   NO_DEBUG_API   a member whose server did not answer even after a wake
     *                  and [StoreMesh.WAKE_TIMEOUT_MS] of re-sweeps (#762: one
     *                  that answers in time is [StoreMesh.Live.woken], healthy)
     *   NO_PERMISSION  CONSTELLATION_DATA not held
     *   PEER_BLIND     its own /api/fleet/peers misses members the host can see
     *   ENGINE_BROKEN  an engine it binds is missing or below its contract
     * Libs are engines, not apps; their failures surface as ENGINE_BROKEN on the
     * app that binds them.
     */
    fun gaps(decl: Decl, fleet: List<Fleet.App>, links: List<StoreMesh.Link>, live: StoreMesh.Live): List<Gap> {
        val byId = fleet.associateBy { it.id }
        val label = { id: String -> byId[id]?.label ?: id }
        val out = ArrayList<Gap>()
        for (app in fleet) {
            if (app.kind != "app" || app.id !in live.installed) continue
            fun add(kind: GapKind, vars: Map<String, String> = emptyMap()) {
                val (words, fix) = decl.gapWords[kind.name] ?: (kind.name to "")
                val all = vars + ("repo" to repoOf(app))
                fun fill(t: String) = all.entries.fold(t) { acc, (k, v) -> acc.replace("{$k}", v) }
                out.add(Gap(app, kind, fill(words), fill(fix)))
            }
            val member = app.id in live.peers
            if (!member) add(GapKind.NO_PROVIDER)
            // #793 a member the probe only looked at and did not wake is not
            // running, not broken: Wake / Wake all decide whether it can serve.
            else if (app.id !in live.reachable && app.id !in live.asleep) add(GapKind.NO_DEBUG_API)
            if (app.id !in live.granted) add(GapKind.NO_PERMISSION)
            live.peerViews[app.id]?.let { seen ->
                val missing = live.peers - seen
                if (missing.isNotEmpty()) add(GapKind.PEER_BLIND, mapOf(
                    "seen" to "${(seen intersect live.peers).size}", "total" to "${live.peers.size}",
                    "missing" to missing.sorted().joinToString(", ") { label(it) }))
            }
            for (l in links.filter { it.from == app.id }) {
                val fix = StoreMesh.fix(l, live, label(l.engine)) ?: continue
                add(GapKind.ENGINE_BROKEN, mapOf("fix" to fix))
            }
        }
        return out
    }

    // ── export ───────────────────────────────────────────────────────────────

    /** The whole page as text: the summary, the missing-membership report and
     *  one line per member. What Export shares and Copy all copies. */
    fun report(decl: Decl, fleet: List<Fleet.App>, links: List<StoreMesh.Link>, live: StoreMesh.Live): String =
        buildString {
            val gaps = gaps(decl, fleet, links, live)
            val byId = fleet.associateBy { it.id }
            appendLine("Apps Mesh · ${fleet.size} members · ${live.installed.size} installed · " +
                "${live.reachable.size} reachable (${live.woken.size} woke ok) · ${live.peers.size} mesh members")
            appendLine()
            appendLine("Missing membership (${gaps.size})")
            if (gaps.isEmpty()) appendLine("  none")
            for (g in gaps) {
                appendLine("  ✕ ${g.app.label} [${g.kind.name}] ${g.label}")
                appendLine("      fix: ${g.fix}")
            }
            appendLine()
            appendLine("Members")
            for (app in fleet) {
                val v = live.installed[app.id]
                append("  ${app.label} (${app.id}, ${app.kind}) ")
                if (v == null) { appendLine("not installed"); continue }
                append("v${v.ifEmpty { "?" }}")
                append(live.reachable[app.id]?.let { " · ${StoreMesh.address(app, it)}" }
                    ?: if (app.id in live.asleep) " · not running (not woken)" else " · no debug API")
                if (app.id in live.woken) append(" · was stopped, woke ok")
                append(if (app.id in live.peers) " · member" else " · NOT a member")
                append(if (app.id in live.granted) " · CONSTELLATION_DATA" else " · no CONSTELLATION_DATA")
                live.peerViews[app.id]?.let { append(" · sees ${it.size}/${live.peers.size}") }
                appendLine()
                for (l in links.filter { it.from == app.id })
                    appendLine("      → ${l.name}: ${byId[l.engine]?.label ?: l.engine} ${StoreMesh.state(l, live).name}")
                live.shares[app.id]?.let { appendLine("      ⇄ serves ${it.joinToString(" · ")}") }
            }
        }

    // ── #792 endpoints catalogue ─────────────────────────────────────────────

    /** This phone's VPN-interface addresses — its mesh address under the
     *  SuperApp's tunnel. Shown so the page can say the debug API is NOT there. */
    fun meshAddresses(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && (it.name.startsWith("tun") || it.name.startsWith("wg")) }
            .flatMap { it.inetAddresses.toList() }
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress?.substringBefore('%') }
    }.getOrDefault(emptyList())

    /** The declared exposure sentence with {mesh} filled. */
    fun exposure(decl: Decl, mesh: List<String>): String =
        decl.exposure.replace("{mesh}", mesh.joinToString(", ").ifEmpty { "no mesh tunnel up" })

    /** /api/docs → every endpoint as {group, path, params, description}; the
     *  universal ones carry group "". Unparseable docs → nothing. */
    fun endpointList(docs: String): List<JSONObject> = runCatching {
        val o = JSONObject(docs)
        fun list(group: String, arr: org.json.JSONArray?) = (0 until (arr?.length() ?: 0)).map {
            val e = arr!!.getJSONObject(it)
            JSONObject().put("group", group).put("path", e.optString("path"))
                .put("params", e.optString("params")).put("description", e.optString("description"))
        }
        val groups = o.optJSONArray("groups")
        list("", o.optJSONArray("endpoints")) + (0 until (groups?.length() ?: 0)).flatMap {
            val g = groups!!.getJSONObject(it)
            list(g.optString("group"), g.optJSONArray("endpoints"))
        }
    }.getOrDefault(emptyList())

    fun endpointCount(docs: String): Int = endpointList(docs).size

    /**
     * The whole fleet's endpoints as one JSON document: every fleet row, its
     * assigned and actual loopback address, membership, what it shares, and
     * each reachable member's own catalogue. What Export endpoints shares and
     * what /api/fleet/endpoints answers. Names no token: the docs bodies say
     * "Bearer <fleet token>", never the token.
     *
     * #793 [filter] keeps only the members the page's chip of that id would
     * show ([matches]); `counts` is every chip's count, so the device answer
     * can be checked against the page.
     */
    fun catalogue(
        fleet: List<Fleet.App>, live: StoreMesh.Live, exposure: String, mesh: List<String>,
        links: List<StoreMesh.Link> = emptyList(), filter: String = "all",
    ): JSONObject {
        val members = org.json.JSONArray()
        for (app in fleet.filter { matches(filter, it, links, live) }) {
            val v = live.installed[app.id]
            val port = live.reachable[app.id]
            val m = JSONObject().put("id", app.id).put("label", app.label).put("kind", app.kind)
                .put("package", app.pkg).put("installed", v != null)
                .put("assigned_port", com.diegonmarcos.superapp.devtools.AppDebugServer.portOf(app.pkg) ?: JSONObject.NULL)
                .put("engine_links", org.json.JSONArray(links.filter { it.from == app.id || it.engine == app.id }.map {
                    JSONObject().put("from", it.from).put("binding", it.name).put("engine", it.engine)
                        .put("state", StoreMesh.state(it, live).name) }))
            if (v != null) {
                m.put("version", v).put("member", app.id in live.peers).put("woken", app.id in live.woken)
                    .put("running", matches("running", app, links, live))
                    .put("reachable", matches("reachable", app, links, live))
                    .put("port", port ?: JSONObject.NULL)
                    .put("base", port?.let { "http://127.0.0.1:$it" } ?: JSONObject.NULL)
                    .put("shares", org.json.JSONArray(live.shares[app.id].orEmpty()))
                    .put("endpoints", org.json.JSONArray(live.docs[app.id]?.let { endpointList(it) }.orEmpty()))
            }
            members.put(m)
        }
        return JSONObject()
            .put("range", org.json.JSONArray(listOf(
                com.diegonmarcos.superapp.devtools.AppDebugServer.PORT_FIRST,
                com.diegonmarcos.superapp.devtools.AppDebugServer.PORT_LAST)))
            .put("bind", "127.0.0.1").put("auth", "Bearer <fleet token> on every route but /api/system/ping")
            .put("mesh_addresses", org.json.JSONArray(mesh)).put("mesh_reachable", false)
            .put("exposure", exposure)
            .put("summary", "${fleet.size} members · ${live.installed.size} installed · " +
                "${live.reachable.size} reachable · ${live.docs.values.sumOf { endpointCount(it) }} endpoints")
            .put("filter", filter)
            .put("counts", JSONObject(counts(fleet, links, live)))
            .put("members", members)
    }

    /** #793 the same catalogue as Markdown: one section per member, each route a line. */
    fun markdown(fleet: List<Fleet.App>, live: StoreMesh.Live): String = buildString {
        appendLine("# Fleet endpoints")
        appendLine()
        appendLine("${fleet.size} members · ${live.reachable.size} reachable · loopback only, Bearer <fleet token>")
        for (app in fleet) {
            val port = live.reachable[app.id] ?: continue
            appendLine()
            appendLine("## ${app.label} (${app.id}) — http://127.0.0.1:$port")
            val eps = live.docs[app.id]?.let { endpointList(it) }
            if (eps == null) { appendLine("_endpoints not fetched_"); continue }
            for (e in eps) {
                append("- `${e.optString("path")}`")
                e.optString("params").takeIf { it.isNotEmpty() }?.let { append(" ($it)") }
                e.optString("group").takeIf { it.isNotEmpty() }?.let { append(" [$it]") }
                e.optString("description").takeIf { it.isNotEmpty() }?.let { append(" — $it") }
                appendLine()
            }
        }
    }

    // ── #793 filters ─────────────────────────────────────────────────────────

    /**
     * Whether the chip [filter] shows [app], judged on whatever [live] knows:
     *   reachable  its debug API answered with the fleet token (its own
     *              /api/fleet/peers read back)
     *   engine     it binds or serves a declared engine link (static: the
     *              manifest says so before any probe)
     *   running    its process is alive now — its debug server answered
     *   stopped    an installed mesh member whose server did not answer
     *              (stopped, or never started since boot)
     * A member with no debug API at all (not a mesh member) can be neither
     * running nor stopped as far as anything here can tell; it is in "all"
     * and in the missing-membership section.
     */
    fun matches(filter: String, app: Fleet.App, links: List<StoreMesh.Link>, live: StoreMesh.Live?): Boolean = when (filter) {
        "reachable" -> live != null && app.id in live.peerViews
        "engine" -> links.any { it.from == app.id || it.engine == app.id }
        "running" -> live != null && app.id in live.reachable
        "stopped" -> live != null && app.id in live.installed && app.id in live.peers && app.id !in live.reachable
        else -> true
    }

    fun counts(fleet: List<Fleet.App>, links: List<StoreMesh.Link>, live: StoreMesh.Live?): Map<String, Int> =
        FILTERS.associateWith { f -> fleet.count { matches(f, it, links, live) } }

    /** While a member's probe is still out, show what the cache last said of it,
     *  not "unknown": [fresh] for everyone done, [cached] for [pending]. */
    fun overlay(fresh: StoreMesh.Live, cached: StoreMesh.Live?, pending: Set<String>): StoreMesh.Live {
        if (cached == null || pending.isEmpty()) return fresh
        fun <V> mix(f: Map<String, V>, c: Map<String, V>) =
            f.filterKeys { it !in pending } + c.filterKeys { it in pending }
        fun mixSet(f: Set<String>, c: Set<String>) = (f - pending) + (c intersect pending)
        return StoreMesh.Live(fresh.installed, mix(fresh.reachable, cached.reachable), fresh.peers, fresh.contracts,
            fresh.shares, fresh.granted, mix(fresh.peerViews, cached.peerViews), mixSet(fresh.woken, cached.woken),
            fresh.docs, mixSet(fresh.asleep, cached.asleep))
    }

    fun withDocs(l: StoreMesh.Live, docs: Map<String, String>) = StoreMesh.Live(l.installed, l.reachable, l.peers,
        l.contracts, l.shares, l.granted, l.peerViews, l.woken, l.docs + docs, l.asleep)

    // ── #793 the cache: the last probe, shown at once on the next open ───────

    const val CACHE = "apps-mesh-cache.json"
    private const val PREFS = "apps_mesh"
    private const val PREF_FILTER = "filter"

    class Cached(val at: Long, val live: StoreMesh.Live, val docsAt: Map<String, Long>)

    fun readCache(ctx: Context): Cached? = runCatching {
        val o = JSONObject(java.io.File(ctx.cacheDir, CACHE).readText())
        val d = o.optJSONObject("docs_at") ?: JSONObject()
        Cached(o.getLong("at"), StoreMesh.fromJson(o.getJSONObject("live")),
            d.keys().asSequence().associateWith { d.optLong(it) })
    }.getOrNull()

    fun writeCache(ctx: Context, c: Cached) {
        runCatching {
            java.io.File(ctx.cacheDir, CACHE).writeText(JSONObject().put("at", c.at)
                .put("live", StoreMesh.toJson(c.live)).put("docs_at", JSONObject(c.docsAt)).toString())
        }
    }

    /** "3 min ago" — how old a cached answer is. */
    fun age(at: Long, now: Long = System.currentTimeMillis()): String {
        val s = ((now - at) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s ago"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} d ago"
        }
    }

    // ── the page ─────────────────────────────────────────────────────────────

    private fun fleetJson(): JSONObject = runCatching {
        JSONObject(String(Base64.decode(BuildConfig.CONSTELLATION_FLEET_B64, Base64.DEFAULT)))
    }.getOrElse { JSONObject() }

    /** #793 what a member's row buttons reach on the page they sit on. */
    class Row(
        val panel: TextView,
        /** member id -> its /api/docs body, fetched once and kept (and cached). */
        val docs: MutableMap<String, String>,
        val docsAt: MutableMap<String, Long>,
        /** re-probe this one member, waking it if it does not answer. */
        val reprobe: (Fleet.App) -> Unit,
        val saved: () -> Unit,
        /** #809 open this member's Details sub-page (a page, not a dialog). */
        val details: (Fleet.App) -> Unit = {},
    )

    /**
     * Draw the page into [into] and start its probe. [onStore] is the host's
     * way to a member's Store row — null where there is none (Configs).
     *
     * #793 LAZY: everything static is drawn before anything is probed — the
     * roster, each member's assigned port, its declared engine links, and the
     * last probe's answer from [readCache] with its age. Then
     * [StoreMesh.probeEach] fills each member's card the moment that member
     * answers (⟳ until then, showing what the cache said), never waiting on the
     * slowest app. The probe only LOOKS: Wake / Wake all start what is stopped.
     */
    fun page(host: Fragment, into: LinearLayout, onStore: ((Fleet.App) -> Unit)? = null) {
        val ctx = host.requireContext()
        val appCtx = ctx.applicationContext
        val decl = load(ctx)
        val looks = StoreControls.load(ctx)
        val fleetJson = fleetJson()
        val fleet = Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64)
        val byId = fleet.associateBy { it.id }
        val links = StoreMesh.links(fleetJson)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cached = readCache(ctx)
        val docs = java.util.concurrent.ConcurrentHashMap(cached?.live?.docs.orEmpty())
        val docsAt = java.util.concurrent.ConcurrentHashMap(cached?.docsAt.orEmpty())
        var live: StoreMesh.Live? = cached?.live
        var at = cached?.at ?: 0L
        var pending: Set<String> = fleet.map { it.id }.toSet()
        var probing = false
        var filter = prefs.getString(PREF_FILTER, null)?.takeIf { f -> decl.filters.any { it.id == f } }
            ?: decl.filters.firstOrNull()?.id ?: "all"
        val mesh = meshAddresses()

        // #809 the root page and the one sub-page slot; a page button swaps them
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val sub = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        into.addView(root); into.addView(sub)
        root.addView(text(ctx, exposure(decl, mesh), 11f, DIM))
        val ageView = text(ctx, "", 11f, DIM).apply { tag = TAG_AGE }
        val meshBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        var drawn: StoreMesh.Drawn? = null
        val rows = HashMap<String, View>()
        val chipViews = LinkedHashMap<String, TextView>()
        val pageViews = HashMap<String, TextView>()
        val onLink = { app: Fleet.App -> onStore?.invoke(app); Unit }
        fun shown() = live?.let { withDocs(it, docs) }
        fun saved() { live?.let { writeCache(appCtx, Cached(at, withDocs(it, docs), HashMap(docsAt))) } }
        val waitWord = "${decl.label("root", "reprobe")}…"

        fun ageLine() = when {
            at == 0L && probing -> "⟳ first probe…"
            at == 0L -> ""
            probing -> "last probe ${age(at)} · ⟳ refreshing ${pending.size} member(s)"
            else -> "last probe ${age(at)}"
        }
        fun applyFilter() {
            val d = drawn ?: return
            for ((id, card) in d.cards)
                card.visibility = if (matches(filter, byId.getValue(id), links, live)) View.VISIBLE else View.GONE
            for ((h, ids) in d.headings)
                h.visibility = if (ids.any { d.cards[it]?.visibility == View.VISIBLE }) View.VISIBLE else View.GONE
        }
        fun paintChips() {
            val n = counts(fleet, links, live)
            for (f in decl.filters) chipViews[f.id]?.let { c ->
                c.text = "${f.label} (${n[f.id] ?: 0})"
                StoreBar.paint(c, f.id == filter)
            }
        }
        fun paintPages() {
            val gaps = live?.let { gaps(decl, fleet, links, it).size }
            pageViews["gaps"]?.let { v ->
                val c = decl.of("root", "page").first { it.id == "gaps" }
                v.text = listOf(c.icon, c.label + (gaps?.let { " ($it)" } ?: ""), looks.page.chevron)
                    .filter { it.isNotEmpty() }.joinToString("  ")
            }
        }
        lateinit var reprobe: (Fleet.App) -> Unit
        lateinit var detailsOf: (Fleet.App) -> Unit
        fun rowOf(app: Fleet.App): View = rows.getOrPut(app.id) {
            val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val panel = text(ctx, "", 11f, DIM).apply {
                typeface = Typeface.MONOSPACE; visibility = View.GONE; setTextIsSelectable(true)
                tag = TAG_DOCS + app.id
            }
            val row = Row(panel, docs, docsAt, { reprobe(it) }, { saved() }, { detailsOf(it) })
            // #809 a member's actions in one row, its pages in another: two kinds, two groups
            val mine = actionsFor(decl, onStore != null)
            for (type in listOf("action", "page")) {
                val cs = mine.filter { it.type == type }
                if (cs.isEmpty()) continue
                val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; tag = "${TAG_GROUP}member:$type" }
                for (a in cs) {
                    val go = { act(host, a.id, app, links, live, onStore, row) }
                    line.addView((if (type == "page") StoreBar.page(ctx, looks.page, a.icon, a.label, go)
                        else StoreBar.button(ctx, looks.action, a.label, a.color, go))
                        .apply { tag = "$TAG_ACTION${a.id}:${app.id}" })
                }
                box.addView(line)
            }
            box.addView(panel)
            box
        }
        fun refresh(ids: Collection<String>?) {
            val d = drawn
            if (d == null || ids == null) {
                meshBox.removeAllViews()
                drawn = StoreMesh.render(ctx, meshBox, fleetJson, fleet, links, live, pending, ::rowOf, onLink)
            } else {
                for (id in ids) d.cards[id]?.let { StoreMesh.fill(ctx, it, byId.getValue(id), live, id in pending, links, byId, ::rowOf, onLink) }
                d.summary.text = StoreMesh.summaryLine(fleet, links, live, pending)
            }
            paintChips(); paintPages(); applyFilter(); ageView.text = ageLine()
        }

        /** [only] null = every member; [wake] starts the ones that do not answer. */
        fun probe(wake: Boolean, only: Set<String>? = null) {
            if (probing) return toast(ctx, "${decl.label("root", "reprobe")}: already probing")
            probing = true
            val base = live
            var first = only == null
            thread(name = "apps-mesh-probe") {
                runCatching {
                    StoreMesh.probeEach(appCtx, fleet, links, wake, only, base) { snap, left ->
                        into.post {
                            if (!into.isAttachedToWindow) return@post
                            val landed = pending - left
                            pending = left
                            live = overlay(snap, base, left)
                            if (first) { first = false; refresh(null) } else refresh(landed + (only ?: emptySet()))
                        }
                    }
                }
                into.post {
                    probing = false; pending = emptySet()
                    if (live != null) { at = System.currentTimeMillis(); saved() }
                    if (into.isAttachedToWindow) refresh(emptyList())
                }
            }
            ageView.text = ageLine()
        }
        reprobe = { app -> probe(wake = true, only = setOf(app.id)) }

        // ── #809 sub-pages: each carries its own actions, and a way back ──
        fun back() { sub.removeAllViews(); sub.visibility = View.GONE; root.visibility = View.VISIBLE; refresh(emptyList()) }
        fun open(id: String, title: String = decl.label("root", id), build: (LinearLayout) -> Unit) {
            sub.removeAllViews(); sub.tag = TAG_SUB + id
            controls(ctx, decl, looks, sub, "sub", mapOf("back" to ::back))
            sub.addView(text(ctx, title, 14f, BLUE, bold = true))
            build(sub)
            root.visibility = View.GONE; sub.visibility = View.VISIBLE
        }
        fun gapsPage() = open("gaps") { page ->
            val l = shown() ?: return@open page.addView(text(ctx, waitWord, 12f, DIM))
            fun report() = report(decl, fleet, links, l)
            controls(ctx, decl, looks, page, "gaps", mapOf(
                "export" to { share(host, "Apps Mesh", "text/plain", report()) },
                "copy" to { copy(ctx, report()) }))
            val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            renderGaps(ctx, decl, box, gaps(decl, fleet, links, l)); page.addView(box)
        }
        // #809 a member's Details: a sub-page like App API Endpoints, carrying
        // its own Copy, filled off the main thread (it may ask the member's
        // /api/system/info), never a dialog over the page.
        fun detailsPage(app: Fleet.App) = open("details", "${app.label} · ${decl.label("member", "details")}") { page ->
            var body = ""
            controls(ctx, decl, looks, page, "details", mapOf("copy" to { copy(ctx, body) }))
            val out = text(ctx, "⟳", 12f, DIM).apply {
                typeface = Typeface.MONOSPACE; setTextIsSelectable(true); tag = TAG_DETAILS + app.id
            }
            page.addView(out)
            val l = live
            thread(name = "apps-mesh-details") {
                val port = l?.reachable?.get(app.id)
                val info = port?.let { StoreMesh.get(it, "/api/system/info", FleetToken.get(appCtx)) }
                val t = details(appCtx, app, links, l, info)
                out.post { body = t; out.text = t }
            }
        }
        detailsOf = { app -> detailsPage(app) }
        fun endpointsPage() = open("endpoints") { page ->
            val l = shown() ?: return@open page.addView(text(ctx, waitWord, 12f, DIM))
            allEndpoints(host, page, decl, looks, fleet, links, l, docs, docsAt, mesh) { saved() }
        }

        controls(ctx, decl, looks, root, "root", mapOf(
            "endpoints" to ::endpointsPage,
            "gaps" to ::gapsPage,
            "reprobe" to { probe(wake = false) },
            "wake" to { probe(wake = true) },
        ), pageViews)
        // the filter group: the Store's own chips, with counts, remembered
        val chips = group(ctx, decl, root, "root", "filter")
        for ((i, f) in decl.filters.withIndex())
            chipViews[f.id] = StoreBar.chip(ctx, looks.filter, f.label, i == 0) {
                if (filter != f.id) { filter = f.id; prefs.edit().putString(PREF_FILTER, f.id).apply(); paintChips(); applyFilter() }
            }.apply { tag = TAG_FILTER + f.id }.also { chips.addView(it) }
        root.addView(ageView); root.addView(meshBox)

        refresh(null)
        probe(wake = false)
    }

    /** #809 one captioned group holding one [type] of control of [scope]; returns its row. */
    private fun group(ctx: Context, decl: Decl, into: LinearLayout, scope: String, type: String): LinearLayout {
        val g = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; tag = "$TAG_GROUP$scope:$type"
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 2))
        }
        g.addView(text(ctx, decl.group(type), 10f, DIM, bold = true))
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        g.addView(row); into.addView(g)
        return row
    }

    /** #809 draw [scope]'s page and action controls, a group per type (pages
     *  first), each with its handler; a declared id with no handler here is
     *  not drawn. Filters are drawn by the page that owns the list. */
    private fun controls(
        ctx: Context, decl: Decl, looks: StoreControls.Decl, into: LinearLayout, scope: String,
        handlers: Map<String, () -> Unit>, pageViews: MutableMap<String, TextView>? = null,
    ) {
        for (type in listOf("page", "action")) {
            val cs = decl.of(scope, type).filter { it.id in handlers }
            if (cs.isEmpty()) continue
            val row = group(ctx, decl, into, scope, type)
            for (c in cs) {
                val h = handlers.getValue(c.id)
                val v = if (type == "page") StoreBar.page(ctx, looks.page, c.icon, c.label, h)
                    else StoreBar.button(ctx, looks.action, c.label, c.color, h)
                v.tag = "$TAG_TOOL$scope:${c.id}"
                if (type == "page") pageViews?.put(c.id, v)
                row.addView(v)
            }
        }
    }

    /** #793 every reachable member's /api/docs not yet in [docs], fetched on a
     *  bounded pool; [onEach] after each lands. Blocking; off the main thread. */
    fun fillDocs(
        ctx: Context, fleet: List<Fleet.App>, live: StoreMesh.Live, docs: MutableMap<String, String>,
        docsAt: MutableMap<String, Long>, onEach: (String) -> Unit,
    ) {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        try {
            live.reachable.filterKeys { it !in docs }.map { (id, port) ->
                pool.submit(java.util.concurrent.Callable {
                    val self = fleet.firstOrNull { it.id == id }?.let { Fleet.installedId(ctx, it) } == ctx.packageName
                    StoreMesh.docs(ctx, port, self)?.let { docs[id] = it; docsAt[id] = System.currentTimeMillis() }
                    onEach(id)
                })
            }.forEach { runCatching { it.get() } }
        } finally { pool.shutdownNow() }
    }

    /**
     * #793 ALL ENDPOINTS — one list of every member's API, grouped by app (its
     * address, then each route: path, params, description), searchable,
     * collapsible per app, with Copy and Export JSON / Markdown. Drawn at once
     * from what is known and filled per app as each member's /api/docs lands.
     */
    private fun allEndpoints(
        host: Fragment, col: LinearLayout, decl: Decl, looks: StoreControls.Decl, fleet: List<Fleet.App>,
        links: List<StoreMesh.Link>, live: StoreMesh.Live, docs: MutableMap<String, String>,
        docsAt: MutableMap<String, Long>, mesh: List<String>, saved: () -> Unit,
    ) {
        val ctx = col.context
        val search = android.widget.EditText(ctx).apply {
            hint = decl.word("search"); textSize = 13f; setSingleLine()
        }
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fun now() = withDocs(live, docs)
        // #809 this page's actions live on this page, not on the Apps Mesh root
        controls(ctx, decl, looks, col, "endpoints", mapOf(
            "json" to { share(host, "Apps Mesh endpoints", "application/json",
                catalogue(fleet, now(), exposure(decl, mesh), mesh, links).toString(2)) },
            "markdown" to { share(host, "Apps Mesh endpoints", "text/markdown", markdown(fleet, now())) },
            "copy" to { copy(ctx, markdown(fleet, now())) }))
        col.addView(search); col.addView(list)
        val members = fleet.filter { it.id in live.installed }
        val bodies = HashMap<String, LinearLayout>()
        val heads = HashMap<String, TextView>()
        val open = HashSet(members.map { it.id })
        val fetching = HashSet<String>()

        fun fill(app: Fleet.App) {
            val body = bodies[app.id] ?: return
            val head = heads.getValue(app.id)
            val q = search.text.toString().trim().lowercase()
            val port = live.reachable[app.id]
            val eps = docs[app.id]?.let { endpointList(it) }
            val hit = eps.orEmpty().filter { e ->
                q.isEmpty() || app.label.lowercase().contains(q) ||
                    listOf("path", "params", "description", "group").any { e.optString(it).lowercase().contains(q) }
            }
            head.text = (if (app.id in open) "▾ " else "▸ ") + app.label + "  ·  " +
                (port?.let { "http://127.0.0.1:$it" } ?: decl.word("not_serving")) +
                (eps?.let { "  ·  ${if (q.isEmpty()) it.size else hit.size} endpoints" } ?: "")
            val sectionVisible = q.isEmpty() || hit.isNotEmpty() || app.label.lowercase().contains(q)
            (head.parent as? View)?.visibility = if (sectionVisible) View.VISIBLE else View.GONE
            body.removeAllViews()
            body.visibility = if (app.id in open) View.VISIBLE else View.GONE
            when {
                port == null -> {}
                eps == null -> body.addView(text(ctx, if (app.id in fetching) "⟳ " + decl.word("fetching")
                    else "127.0.0.1:$port " + decl.word("no_answer"), 11f, DIM))
                else -> for (e in hit) body.addView(text(ctx, buildString {
                    e.optString("group").takeIf { it.isNotEmpty() }?.let { append("[$it] ") }
                    append(e.optString("path"))
                    e.optString("params").takeIf { it.isNotEmpty() }?.let { append("  ($it)") }
                    e.optString("description").takeIf { it.isNotEmpty() }?.let { append("\n    $it") }
                }, 11f, 0xFFE2E8F0.toInt()).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) })
            }
        }
        for (app in members) {
            val section = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; tag = TAG_DOCS + "all:" + app.id }
            val head = text(ctx, app.label, 13f, BLUE, bold = true).apply {
                isClickable = true
                setOnClickListener { if (!open.remove(app.id)) open.add(app.id); fill(app) }
            }
            val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 8), 0, 0, dp(ctx, 6)) }
            section.addView(head); section.addView(body); list.addView(section)
            heads[app.id] = head; bodies[app.id] = body
            if (app.id in live.reachable && app.id !in docs) fetching.add(app.id)
            fill(app)
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { members.forEach { fill(it) } }
        })
        if (fetching.isEmpty()) return
        thread(name = "apps-mesh-all-endpoints") {
            fillDocs(ctx.applicationContext, fleet, live, docs, docsAt) { id ->
                list.post { fetching.remove(id); if (list.isAttachedToWindow) fleet.firstOrNull { it.id == id }?.let { fill(it) } }
            }
            list.post { if (list.isAttachedToWindow) { fetching.clear(); members.forEach { fill(it) } }; saved() }
        }
    }

    private fun share(host: Fragment, subject: String, mime: String, text: String) {
        runCatching {
            host.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime)
                .putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text), null))
        }.onFailure { host.context?.let { c -> toast(c, it.message ?: "export failed") } }
    }

    /** One member action. Everything that touches a socket, a provider or the
     *  shell channel runs off the main thread and posts its answer back. */
    fun act(
        host: Fragment, id: String, app: Fleet.App, links: List<StoreMesh.Link>,
        live: StoreMesh.Live?, onStore: ((Fleet.App) -> Unit)?, row: Row,
    ) {
        val ctx = host.requireContext()
        val appCtx = ctx.applicationContext
        val pkg = Fleet.installedId(ctx, app)
        fun ui(block: () -> Unit) = host.view?.post { if (host.isAdded) block() }
        when (id) {
            "store" -> onStore?.invoke(app)
            "open" -> {
                val i = pkg?.let { ctx.packageManager.getLaunchIntentForPackage(it) }
                if (i != null) host.startActivity(i) else toast(ctx, "${app.label}: nothing to open")
            }
            "start" -> {
                pkg ?: return toast(ctx, "${app.label}: not installed")
                thread(name = "apps-mesh-start") {
                    val ok = FleetPeers.wake(appCtx, pkg)
                    ui {
                        toast(ctx, if (ok) "${app.label}: started through its fleet provider"
                            else "${app.label}: no fleet provider answered — not a mesh member, or force-stopped (open it once)")
                        // #793 then look again at this one member, so its row says whether it came up
                        row.reprobe(app)
                    }
                }
            }
            "stop" -> {
                pkg ?: return toast(ctx, "${app.label}: not installed")
                if (SelfStop.isSelf(ctx, pkg)) return SelfStop.stop(ctx, host.activity)
                thread(name = "apps-mesh-stop") {
                    val out = PhoneAppActions.forceStop(appCtx, pkg)
                    ui {
                        when {
                            out == null -> AlertDialog.Builder(ctx).setTitle(app.label)
                                .setMessage(ctx.getString(R.string.store_fleet_stop_no_channel))
                                .setPositiveButton(load(ctx).word("close"), null).show()
                            out.contains("OK") -> toast(ctx, ctx.getString(R.string.store_phone_stopped, app.label))
                            else -> toast(ctx, ctx.getString(R.string.store_phone_failed, app.label, out.trim()))
                        }
                    }
                }
            }
            // #793 Docs unfolds the member's own /api/docs IN its card — fetched
            // with the fleet bearer the first time, the cached copy after.
            "api" -> {
                val panel = row.panel
                if (panel.visibility == View.VISIBLE) { panel.visibility = View.GONE; return }
                panel.visibility = View.VISIBLE
                val port = live?.reachable?.get(app.id)
                    ?: return run { panel.text = "${app.label} is not serving a debug API right now. Wake it, then Docs again." }
                row.docs[app.id]?.let { body ->
                    panel.text = endpoints(body, port) + (row.docsAt[app.id]?.let { "fetched ${age(it)}" } ?: "")
                    return
                }
                panel.text = "⟳ fetching /api/docs…"
                thread(name = "apps-mesh-api") {
                    val body = StoreMesh.docs(appCtx, port, pkg == ctx.packageName)
                    ui {
                        if (body != null) { row.docs[app.id] = body; row.docsAt[app.id] = System.currentTimeMillis(); row.saved() }
                        panel.text = body?.let { endpoints(it, port) }
                            ?: "127.0.0.1:$port did not answer /api/docs with the fleet token."
                    }
                }
            }
            // #809 Details is a page: the host swaps to the member's Details sub-page
            "details" -> row.details(app)
        }
    }

    /** /api/docs → one line per endpoint, the universal ones then each group. */
    fun endpoints(docs: String, port: Int): String = runCatching {
        val o = JSONObject(docs)
        buildString {
            appendLine("base http://127.0.0.1:$port · Bearer <fleet token> · loopback only")
            fun list(arr: org.json.JSONArray?) {
                for (i in 0 until (arr?.length() ?: 0)) {
                    val e = arr!!.getJSONObject(i)
                    append("  ${e.optString("path")}")
                    e.optString("params").takeIf { it.isNotEmpty() }?.let { append("  ($it)") }
                    appendLine()
                    e.optString("description").takeIf { it.isNotEmpty() }?.let { appendLine("      $it") }
                }
            }
            list(o.optJSONArray("endpoints"))
            val groups = o.optJSONArray("groups")
            for (i in 0 until (groups?.length() ?: 0)) {
                val g = groups!!.getJSONObject(i)
                appendLine("[${g.optString("group")}]")
                list(g.optJSONArray("endpoints"))
            }
        }
    }.getOrDefault(docs)

    /** Everything known about one member, as text — what Details shows and Copy all copies. */
    fun details(ctx: Context, app: Fleet.App, links: List<StoreMesh.Link>, live: StoreMesh.Live?, info: String?): String =
        buildString {
            val d = Fleet.installedDetails(ctx, app)
            val fmt = DateFormat.getDateTimeInstance()
            appendLine("${app.label} (${app.id}, ${app.kind})")
            appendLine("package: ${d?.pkg ?: app.pkg}" + if (d == null) " — not installed" else "")
            appendLine("declared: v${app.declaredVersionName ?: "—"} (code ${app.declaredVersionCode})")
            if (d != null) {
                appendLine("installed: ${d.versionName} (versionCode ${d.versionCode})")
                appendLine("APK sha256: ${d.sha256.ifEmpty { "—" }}")
                appendLine("signing cert sha256: ${d.signingCertSha256 ?: "—"}")
                appendLine("installer: ${d.installerPackage ?: "—"}")
                appendLine("first install: ${fmt.format(Date(d.firstInstallAtMs))} · updated: ${fmt.format(Date(d.lastUpdateAtMs))}")
                appendLine("sdk: min ${d.minSdk} · target ${d.targetSdk} · abis ${d.abis.joinToString().ifEmpty { "none" }}")
            }
            if (live != null) {
                appendLine("debug API: " + (live.reachable[app.id]?.let { StoreMesh.address(app, it) } ?: "not answering") +
                    " · assigned :" + (com.diegonmarcos.superapp.devtools.AppDebugServer.portOf(app.pkg)?.toString() ?: "none"))
                appendLine("mesh member: " + if (app.id in live.peers) "yes (${d?.pkg ?: app.pkg}.fleet provider)" else "no")
                appendLine("CONSTELLATION_DATA: " + if (app.id in live.granted) "granted" else "not held")
                live.peerViews[app.id]?.let { appendLine("sees: ${it.size} of ${live.peers.size} mesh members") }
            }
            val out = links.filter { it.from == app.id }
            val inn = links.filter { it.engine == app.id }
            appendLine("engines bound: " + if (out.isEmpty()) "none" else "")
            for (l in out) appendLine("  → ${l.name}: ${l.engine} (contract ≥ ${l.minContract}" +
                (live?.let { ", ${StoreMesh.state(l, it).name}" } ?: "") + ")")
            if (inn.isNotEmpty()) appendLine("bound by: " + inn.joinToString(", ") { it.from })
            live?.shares?.get(app.id)?.let { appendLine("serves: " + it.joinToString(" · ")) }
            d?.let { permissions(ctx, it.pkg) }?.let { perms ->
                appendLine("permissions (${perms.size} requested):")
                perms.forEach { appendLine("  $it") }
            }
            appendLine("links:")
            listOf("repo" to app.repoUrl, "release" to app.releaseUrl, "ghcr" to app.ghcrPage)
                .filter { it.second.isNotBlank() }.forEach { (k, v) -> appendLine("  $k: $v") }
            info?.let { appendLine("/api/system/info: $it") }
        }

    @Suppress("DEPRECATION")
    private fun permissions(ctx: Context, pkg: String): List<String>? = runCatching {
        val pi: PackageInfo = ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        val names = pi.requestedPermissions.orEmpty()
        val flags = pi.requestedPermissionsFlags ?: IntArray(names.size)
        names.mapIndexed { i, n ->
            val granted = (flags.getOrElse(i) { 0 } and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            (if (granted) "✓ " else "✕ ") + n
        }
    }.getOrNull()

    // ── views ────────────────────────────────────────────────────────────────

    private const val RED = 0xFFF56565.toInt()
    private const val GREEN = 0xFF48BB78.toInt()
    private const val DIM = 0x99FFFFFF.toInt()
    private const val BLUE = 0xFF63B3ED.toInt()

    private fun renderGaps(ctx: Context, decl: Decl, box: LinearLayout, gaps: List<Gap>) {
        box.addView(text(ctx, "Missing membership (${gaps.size})", 13f, if (gaps.isEmpty()) GREEN else RED, bold = true))
        if (gaps.isEmpty()) box.addView(text(ctx, decl.word("missing_none"), 12f, GREEN))
        for (g in gaps) box.addView(text(ctx, "✕ ${g.app.label} — ${g.label}\n    fix: ${g.fix}", 12f, RED).apply {
            tag = "$TAG_GAP${g.app.id}:${g.kind.name}"
        })
    }

    private fun copy(ctx: Context, body: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText("Apps Mesh", body))
        toast(ctx, "copied ${body.lines().size} lines")
    }

    private fun text(ctx: Context, t: String, size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
    }

    private fun toast(ctx: Context, m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
}
