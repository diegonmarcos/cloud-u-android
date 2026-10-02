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
import android.view.Gravity
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
 *
 * Every word on it is the asset's; this file names no member, package or caption.
 */
object AppsMesh {

    const val TAG_GAP = "apps-mesh-gap:"
    const val TAG_TOOL = "apps-mesh-tool:"

    /** The action ids this file implements; anything else declared is dropped,
     *  so a misspelt id cannot ship as a button that does nothing. */
    val HANDLED = listOf("api", "start", "stop", "open", "details", "store")

    class Action(val id: String, val label: String)

    class Decl(val actions: List<Action>, private val tools: Map<String, String>,
               val gapWords: Map<String, Pair<String, String>>) {
        fun tool(id: String) = tools[id]?.takeIf { it.isNotEmpty() } ?: id
    }

    fun decl(controls: JSONObject): Decl {
        val m = controls.optJSONObject("apps_mesh") ?: JSONObject()
        val a = m.optJSONArray("member_actions")
        val actions = (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) }
            .map { Action(it.optString("id"), it.optString("label", it.optString("id"))) }
            .filter { it.id in HANDLED }
        val t = m.optJSONObject("tools") ?: JSONObject()
        val g = m.optJSONObject("gaps") ?: JSONObject()
        return Decl(actions,
            t.keys().asSequence().filterNot { it.startsWith("_") }.associateWith { t.optString(it) },
            g.keys().asSequence().filterNot { it.startsWith("_") }.associateWith { k ->
                g.getJSONObject(k).let { it.optString("label", k) to it.optString("fix") } })
    }

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
            else if (app.id !in live.reachable) add(GapKind.NO_DEBUG_API)
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
                append(live.reachable[app.id]?.let { " · :$it" } ?: " · no debug API")
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

    // ── the page ─────────────────────────────────────────────────────────────

    private fun fleetJson(): JSONObject = runCatching {
        JSONObject(String(Base64.decode(BuildConfig.CONSTELLATION_FLEET_B64, Base64.DEFAULT)))
    }.getOrElse { JSONObject() }

    /**
     * Draw the page into [into] and start its probe. [onStore] is the host's
     * way to a member's Store row — null where there is none (Configs).
     */
    fun page(host: Fragment, into: LinearLayout, onStore: ((Fleet.App) -> Unit)? = null) {
        val ctx = host.requireContext()
        val decl = load(ctx)
        val fleetJson = fleetJson()
        val fleet = Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64)
        val links = StoreMesh.links(fleetJson)
        var live: StoreMesh.Live? = null

        val tools = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val gapBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val meshBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        into.addView(tools); into.addView(gapBox); into.addView(meshBox)

        val open = { app: Fleet.App -> showActions(host, decl, app, links, live, onStore) }
        fun draw() {
            gapBox.removeAllViews(); meshBox.removeAllViews()
            live?.let { renderGaps(ctx, gapBox, gaps(decl, fleet, links, it)) }
            StoreMesh.render(ctx, meshBox, fleetJson, fleet, links, live, open)
        }
        fun probe() {
            live = null; draw()
            val app = ctx.applicationContext
            thread(name = "apps-mesh-probe") {
                val r = StoreMesh.probe(app, fleet, links)
                into.post { if (into.isAttachedToWindow) { live = r; draw() } }
            }
        }
        fun withReport(then: (String) -> Unit) {
            val l = live ?: return toast(ctx, "${decl.tool("reprobe")}…")
            then(report(decl, fleet, links, l))
        }
        tools.addView(tool(ctx, "reprobe", decl.tool("reprobe")) { probe() })
        tools.addView(tool(ctx, "export", decl.tool("export")) {
            withReport { text ->
                runCatching {
                    host.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "Apps Mesh").putExtra(Intent.EXTRA_TEXT, text), null))
                }.onFailure { toast(ctx, it.message ?: "export failed") }
            }
        })
        tools.addView(tool(ctx, "copy", decl.tool("copy")) { withReport { copy(ctx, it) } })
        probe()
    }

    private fun showActions(
        host: Fragment, decl: Decl, app: Fleet.App, links: List<StoreMesh.Link>,
        live: StoreMesh.Live?, onStore: ((Fleet.App) -> Unit)?,
    ) {
        val ctx = host.context ?: return
        val items = actionsFor(decl, onStore != null)
        AlertDialog.Builder(ctx).setTitle(app.label)
            .setItems(items.map { it.label }.toTypedArray()) { _, i ->
                act(host, items[i].id, app, links, live, onStore)
            }.show()
    }

    /** One member action. Everything that touches a socket, a provider or the
     *  shell channel runs off the main thread and posts its answer back. */
    fun act(
        host: Fragment, id: String, app: Fleet.App, links: List<StoreMesh.Link>,
        live: StoreMesh.Live?, onStore: ((Fleet.App) -> Unit)?,
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
                    ui { toast(ctx, if (ok) "${app.label}: started through its fleet provider"
                        else "${app.label}: no fleet provider answered — not a mesh member, or force-stopped (open it once)") }
                }
            }
            "stop" -> {
                pkg ?: return toast(ctx, "${app.label}: not installed")
                if (pkg == ctx.packageName) return toast(ctx, ctx.getString(R.string.store_phone_why_self))
                thread(name = "apps-mesh-stop") {
                    val out = PhoneAppActions.forceStop(appCtx, pkg)
                    ui {
                        when {
                            out == null -> AlertDialog.Builder(ctx).setTitle(app.label)
                                .setMessage(ctx.getString(R.string.store_fleet_stop_no_channel))
                                .setPositiveButton(load(ctx).tool("close"), null).show()
                            out.contains("OK") -> toast(ctx, ctx.getString(R.string.store_phone_stopped, app.label))
                            else -> toast(ctx, ctx.getString(R.string.store_phone_failed, app.label, out.trim()))
                        }
                    }
                }
            }
            "api" -> {
                val port = live?.reachable?.get(app.id)
                    ?: return textDialog(ctx, app.label, "${app.label} is not serving a debug API right now. " +
                        "Start it, then Re-probe.")
                thread(name = "apps-mesh-api") {
                    val body = StoreMesh.get(port, "/api/docs", FleetToken.get(appCtx))
                    val text = body?.let { endpoints(it, port) } ?: "127.0.0.1:$port did not answer /api/docs with the fleet token."
                    ui { textDialog(ctx, "${app.label} · API", text) }
                }
            }
            "details" -> thread(name = "apps-mesh-details") {
                val port = live?.reachable?.get(app.id)
                val info = port?.let { StoreMesh.get(it, "/api/system/info", FleetToken.get(appCtx)) }
                val text = details(appCtx, app, links, live, info)
                ui { textDialog(ctx, "${app.label} · Details", text) }
            }
        }
    }

    /** /api/docs → one line per endpoint, the universal ones then each group. */
    fun endpoints(docs: String, port: Int): String = runCatching {
        val o = JSONObject(docs)
        buildString {
            appendLine("base http://127.0.0.1:$port · Bearer <fleet token>")
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
                appendLine("debug API: " + (live.reachable[app.id]?.let { "127.0.0.1:$it" } ?: "not answering"))
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

    private fun renderGaps(ctx: Context, box: LinearLayout, gaps: List<Gap>) {
        box.addView(text(ctx, "Missing membership (${gaps.size})", 13f, if (gaps.isEmpty()) GREEN else RED, bold = true))
        if (gaps.isEmpty()) box.addView(text(ctx, "every installed app is a full mesh member", 12f, GREEN))
        for (g in gaps) box.addView(text(ctx, "✕ ${g.app.label} — ${g.label}\n    fix: ${g.fix}", 12f, RED).apply {
            tag = "$TAG_GAP${g.app.id}:${g.kind.name}"
        })
    }

    private fun textDialog(ctx: Context, title: String, body: String) {
        AlertDialog.Builder(ctx).setTitle(title)
            .setView(android.widget.ScrollView(ctx).apply {
                addView(TextView(ctx).apply {
                    text = body; textSize = 12f; setTextIsSelectable(true); typeface = Typeface.MONOSPACE
                    val p = dp(ctx, 16); setPadding(p, p, p, p)
                })
            })
            .setPositiveButton(load(ctx).tool("copy")) { _, _ -> copy(ctx, body) }
            .setNegativeButton(load(ctx).tool("close"), null)
            .show()
    }

    private fun copy(ctx: Context, body: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText("Apps Mesh", body))
        toast(ctx, "copied ${body.lines().size} lines")
    }

    private fun tool(ctx: Context, id: String, label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; tag = TAG_TOOL + id; gravity = Gravity.CENTER; textSize = 12f
        typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFFFFFFFF.toInt())
        val style = StoreControls.load(ctx).action
        background = StoreControls.background(ctx, style, false, 0xFF2A2A33.toInt())
        setPadding(dp(ctx, 8), dp(ctx, 7), dp(ctx, 8), dp(ctx, 7))
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { setMargins(dp(ctx, 3), dp(ctx, 4), dp(ctx, 3), dp(ctx, 6)) }
        isClickable = true; setOnClickListener { onClick() }
    }

    private fun text(ctx: Context, t: String, size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
    }

    private fun toast(ctx: Context, m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
}
