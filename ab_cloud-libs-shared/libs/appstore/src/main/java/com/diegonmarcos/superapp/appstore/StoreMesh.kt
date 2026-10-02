package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.diegonmarcos.superapp.devtools.AppDebugServer
import com.diegonmarcos.superapp.devtools.FleetPeers
import com.diegonmarcos.superapp.devtools.FleetToken
import com.diegonmarcos.superapp.updater.Fleet
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * #728 Store ▸ Mesh — the constellation drawn as what it is on THIS phone: a
 * mesh of same-signature APKs, the engines each app binds, and who serves data
 * to whom.
 *
 * Nothing here is a list. The NODES are every row of the fleet manifest
 * (`apps[]`, grouped by its declared `groups`); the LINKS are each row's
 * `engines`, which data/regen.sh copies from that app's own
 * build.json::engines (#705: Store row, action, min_contract). A new app, lib
 * or binding appears here by being declared, and this file names none of them.
 *
 * The LIVE half reuses what the fleet already serves, no new protocol:
 *   - installed + version: the PackageManager, through [Fleet.installedId]
 *   - reachable: every member's AppDebugServer answers the open
 *     `/api/system/ping` with `pong <applicationId>` somewhere in
 *     [AppDebugServer.PORT_FIRST]..[AppDebugServer.PORT_LAST]
 *   - mesh member: [FleetPeers.list], the same answer `/api/fleet/peers` serves
 *   - link health: the handshake every engine client does before binding —
 *     resolve the declared action in the engine package and read its CONTRACT
 *     meta-data (GhEngine / CalBridge / NewsBridge / RemoteFeed)
 *   - shared data: providers and services a member guards with
 *     CONSTELLATION_DATA, and which members hold that grant
 *
 * [render] takes the probe result as plain data, so a test hands it a phone it
 * invented and reads back what was drawn (every node and link row is tagged).
 */
object StoreMesh {

    const val CONSTELLATION_PERM = "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"
    /** The engine CONTRACT meta-data key every engine service declares. */
    const val CONTRACT_KEY = "com.diegonmarcos.cloud.engine.CONTRACT"

    const val TAG_NODE = "store-mesh-node:"
    const val TAG_LINK = "store-mesh-link:"

    /** One app -> engine edge, exactly as the app's build.json::engines declares it. */
    class Link(val from: String, val name: String, val engine: String, val action: String, val minContract: Int) {
        val key get() = "$engine|$action"
    }

    /** Every declared link, read off the fleet rows. */
    fun links(fleetJson: JSONObject): List<Link> {
        val apps = fleetJson.optJSONArray("apps") ?: return emptyList()
        return (0 until apps.length()).flatMap { i ->
            val app = apps.getJSONObject(i)
            val engines = app.optJSONArray("engines") ?: return@flatMap emptyList<Link>()
            (0 until engines.length()).map { j ->
                val e = engines.getJSONObject(j)
                Link(app.getString("id"), e.optString("binding"), e.optString("fleet"),
                    e.optString("action"), e.optInt("min_contract", 1))
            }
        }
    }

    /** What the probes found, as plain data. */
    class Live(
        /** fleet id -> installed versionName; a missing key is "not installed". */
        val installed: Map<String, String>,
        /** fleet id -> loopback port whose ping answered with its package. */
        val reachable: Map<String, Int>,
        /** fleet ids that ship the fleet provider (FleetPeers / /api/fleet/peers). */
        val peers: Set<String>,
        /** [Link.key] -> the CONTRACT the engine's service declares; 0 = no service. */
        val contracts: Map<String, Int>,
        /** fleet id -> what it guards with CONSTELLATION_DATA (providers, services). */
        val shares: Map<String, List<String>>,
        /** fleet ids that hold the CONSTELLATION_DATA grant. */
        val granted: Set<String>,
        /** #733 fleet id -> the fleet ids its OWN /api/fleet/peers lists. Only
         *  members that answered are keys; a member it cannot see is a blind
         *  spot in its package visibility (AppsMesh.Gap PEER_BLIND). */
        val peerViews: Map<String, Set<String>> = emptyMap(),
        /** #762 fleet ids that were stopped, were woken by the probe and then
         *  answered: healthy, reported as "was stopped, woke ok", not a gap. */
        val woken: Set<String> = emptySet(),
    )

    enum class State { OK, ENGINE_MISSING, ENGINE_OLD, APP_ABSENT }

    /** A link is BROKEN only when its app is installed: an app that is not on
     *  this phone binds nothing, so its missing engine is not a fault. */
    fun state(link: Link, live: Live): State = when {
        link.from !in live.installed -> State.APP_ABSENT
        link.engine !in live.installed -> State.ENGINE_MISSING
        (live.contracts[link.key] ?: 0) < link.minContract -> State.ENGINE_OLD
        else -> State.OK
    }

    /** The one-line remedy for a broken link, naming the Store row to act on. */
    fun fix(link: Link, live: Live, engineLabel: String): String? = when (state(link, live)) {
        State.ENGINE_MISSING -> "install $engineLabel from the Store"
        State.ENGINE_OLD -> "update $engineLabel from the Store " +
            "(its engine answers contract ${live.contracts[link.key] ?: 0}, this app needs ${link.minContract})"
        else -> null
    }

    // ── live probes (blocking; call off the main thread) ─────────────────────

    fun probe(ctx: Context, fleet: List<Fleet.App>, links: List<Link>): Live {
        val pm = ctx.packageManager
        val byId = fleet.associateBy { it.id }
        val pkgOf = HashMap<String, String>()
        val installed = HashMap<String, String>()
        for (app in fleet) {
            if (app.pkg.isEmpty()) continue
            val pkg = Fleet.installedId(ctx, app) ?: continue
            pkgOf[app.id] = pkg
            installed[app.id] = runCatching { pm.getPackageInfo(pkg, 0).versionName }.getOrNull().orEmpty()
        }
        val idOf = pkgOf.entries.associate { (id, pkg) -> pkg to id }
        val contracts = links.associate { l ->
            val pkg = pkgOf[l.engine]
            // {package} is the engine's DECLARED id, substituted exactly as the
            // app's build does it, then resolved in whatever package is installed.
            val action = l.action.replace("{package}", byId[l.engine]?.pkg.orEmpty())
            l.key to (if (pkg == null) 0 else contractOf(pm, action, pkg))
        }
        val shares = HashMap<String, List<String>>()
        val granted = HashSet<String>()
        for ((id, pkg) in pkgOf) {
            guarded(pm, pkg).takeIf { it.isNotEmpty() }?.let { shares[id] = it }
            if (pm.checkPermission(CONSTELLATION_PERM, pkg) == PackageManager.PERMISSION_GRANTED) granted.add(id)
        }
        val peers = FleetPeers.list(ctx).mapNotNull { idOf[it] }.toSet()
        val sweepIds = { sweep().mapNotNull { (pkg, port) -> idOf[pkg]?.let { it to port } }.toMap() }
        val first = sweepIds()
        // #733 "no debug API" must mean the member CANNOT serve one, not that it
        // was merely asleep: wake every member that ships the provider and did
        // not answer, then sweep again. A force-stopped app stays stopped, which
        // is exactly the case the gap report then names.
        val asleep = peers - first.keys
        asleep.forEach { pkgOf[it]?.let { pkg -> FleetPeers.wake(ctx, pkg) } }
        val reachable = awaitWoken(first, asleep, WAKE_TIMEOUT_MS, WAKE_POLL_MS, sweepIds, Thread::sleep)
        val woken = asleep intersect reachable.keys
        val token = FleetToken.get(ctx)
        val peerViews = reachable.mapNotNull { (id, port) ->
            val body = get(port, "/api/fleet/peers", token) ?: return@mapNotNull null
            runCatching {
                val arr = JSONObject(body).getJSONArray("peers")
                id to (0 until arr.length()).mapNotNull { idOf[arr.getJSONObject(it).optString("pkg")] }.toSet()
            }.getOrNull()
        }.toMap()
        return Live(
            installed = installed,
            reachable = reachable,
            peers = peers,
            contracts = contracts,
            shares = shares,
            granted = granted,
            peerViews = peerViews,
            woken = woken,
        )
    }

    /** #762 a woken member answered after ~10 s on the phone (cold process,
     *  Application work before the first accept); the old single 1.5 s settle
     *  reported three healthy apps as gaps. Re-sweep until every woken member
     *  answers or this much time has passed. */
    const val WAKE_TIMEOUT_MS = 15_000L
    const val WAKE_POLL_MS = 1_000L

    /**
     * #762 re-[sweep] every [pollMs] until each of [asleep] answers or
     * [timeoutMs] is spent; the last sweep wins. [sleep] is injected so a test
     * runs the loop without waiting. Nothing asleep = [first], no extra sweep.
     */
    fun awaitWoken(
        first: Map<String, Int>, asleep: Set<String>, timeoutMs: Long, pollMs: Long,
        sweep: () -> Map<String, Int>, sleep: (Long) -> Unit,
    ): Map<String, Int> {
        var reachable = first
        var waited = 0L
        while (!reachable.keys.containsAll(asleep) && waited < timeoutMs) {
            sleep(pollMs); waited += pollMs
            reachable = sweep()
        }
        return reachable
    }

    /**
     * #733 One authenticated GET on a member's loopback debug API — the body,
     * or null when it did not answer 200. Raw socket for the same reason as
     * [ping]. Blocking; call off the main thread.
     */
    fun get(port: Int, path: String, token: String): String? = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 400)
            s.soTimeout = 3000
            s.getOutputStream().write(("GET $path HTTP/1.0\r\nHost: 127.0.0.1\r\n" +
                "Authorization: Bearer $token\r\n\r\n").toByteArray())
            val raw = s.getInputStream().bufferedReader().readText()
            if (!raw.startsWith("HTTP/1.0 200") && !raw.startsWith("HTTP/1.1 200")) null
            else raw.substringAfter("\r\n\r\n", "")
        }
    }.getOrNull()

    private fun contractOf(pm: PackageManager, action: String, pkg: String): Int =
        runCatching {
            pm.resolveService(Intent(action).setPackage(pkg), PackageManager.GET_META_DATA)
                ?.serviceInfo?.metaData?.getInt(CONTRACT_KEY, 0) ?: 0
        }.getOrDefault(0)

    @Suppress("DEPRECATION")
    private fun guarded(pm: PackageManager, pkg: String): List<String> = runCatching {
        val pi = pm.getPackageInfo(pkg, PackageManager.GET_PROVIDERS or PackageManager.GET_SERVICES)
        pi.providers.orEmpty()
            .filter { it.readPermission == CONSTELLATION_PERM || it.writePermission == CONSTELLATION_PERM }
            .map { "content://${it.authority}" } +
        pi.services.orEmpty()
            .filter { it.permission == CONSTELLATION_PERM }
            .map { "service ${it.name.substringAfterLast('.')}" }
    }.getOrDefault(emptyList())

    /** package -> port, for every port in the fleet's range that answers the
     *  open ping. Raw socket, not HttpURLConnection: loopback cleartext is then
     *  not subject to the host's network-security policy. */
    private fun sweep(): Map<String, Int> {
        val pool = Executors.newFixedThreadPool(16)
        return try {
            (AppDebugServer.PORT_FIRST..AppDebugServer.PORT_LAST)
                .map { port -> pool.submit(Callable { ping(port)?.let { it to port } }) }
                .mapNotNull { runCatching { it.get() }.getOrNull() }
                .toMap()
        } finally { pool.shutdownNow() }
    }

    private val PONG = Regex("pong (\\S+)")

    private fun ping(port: Int): String? = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 250)
            s.soTimeout = 800
            s.getOutputStream().write("GET /api/system/ping HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
            PONG.find(s.getInputStream().bufferedReader().readText())?.groupValues?.get(1)
        }
    }.getOrNull()

    // ── rendering ────────────────────────────────────────────────────────────

    private const val GREEN = 0xFF48BB78.toInt()
    private const val BLUE = 0xFF63B3ED.toInt()
    private const val RED = 0xFFF56565.toInt()
    private const val AMBER = 0xFFED8936.toInt()
    private const val DIM = 0x99FFFFFF.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    /**
     * Draw the mesh into [into]. [live] null = probes still running: every node
     * is drawn, statuses read "probing". [onOpen] receives a tapped node.
     */
    fun render(
        ctx: Context, into: LinearLayout, fleetJson: JSONObject, fleet: List<Fleet.App>,
        links: List<Link>, live: Live?, onOpen: (Fleet.App) -> Unit,
    ) {
        val byId = fleet.associateBy { it.id }
        val label = { id: String -> byId[id]?.label ?: id }
        val out = links.groupBy { it.from }
        val inn = links.groupBy { it.engine }
        val broken = if (live == null) emptyList()
            else links.filter { state(it, live) == State.ENGINE_MISSING || state(it, live) == State.ENGINE_OLD }

        into.addView(text(ctx, if (live == null) "${fleet.size} members · probing…" else
            "${fleet.size} members · ${live.installed.size} installed · ${live.reachable.size} reachable · " +
            "${links.size} engine links, ${broken.size} broken", 12f, DIM))

        if (broken.isNotEmpty()) {
            into.addView(heading(ctx, "Broken links", RED))
            for (l in broken) into.addView(linkRow(ctx, l, live!!, label, byId, onOpen))
        }

        // Nodes in the declared group order; a row no group lists still gets
        // drawn, under its own heading, so no fleet member can drop off the page.
        val groups = fleetJson.optJSONArray("groups")
        val placed = HashSet<String>()
        val runs = ArrayList<Pair<String, List<Fleet.App>>>()
        for (i in 0 until (groups?.length() ?: 0)) {
            val g = groups!!.getJSONObject(i)
            val members = g.optJSONArray("members")
            val rows = (0 until (members?.length() ?: 0)).mapNotNull { byId[members!!.optString(it)] }
                .filter { placed.add(it.id) }
            if (rows.isNotEmpty()) runs.add(g.optString("label", g.optString("id")) to rows)
        }
        fleet.filter { it.id !in placed }.takeIf { it.isNotEmpty() }?.let { runs.add("Other" to it) }

        for ((title, rows) in runs) {
            into.addView(heading(ctx, title, AMBER))
            for (app in rows) into.addView(node(ctx, app, live, out[app.id].orEmpty(), inn[app.id].orEmpty(),
                label, byId, onOpen))
        }
    }

    private fun node(
        ctx: Context, app: Fleet.App, live: Live?, out: List<Link>, inn: List<Link>,
        label: (String) -> String, byId: Map<String, Fleet.App>, onOpen: (Fleet.App) -> Unit,
    ): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            tag = TAG_NODE + app.id
            setBackgroundColor(0xFF1C1C24.toInt())
            setPadding(dp(ctx, 12), dp(ctx, 7), dp(ctx, 12), dp(ctx, 7))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(ctx, 2), 0, dp(ctx, 2)) }
            isClickable = true
            setOnClickListener { onOpen(app) }
        }
        val version = live?.installed?.get(app.id)
        val port = live?.reachable?.get(app.id)
        val (dot, status) = when {
            live == null -> DIM to "probing…"
            version == null -> DIM to "not installed"
            port != null -> GREEN to "${version.ifEmpty { "?" }} · reachable :$port" +
                if (app.id in live.woken) " · was stopped, woke ok" else ""
            app.id in live.peers -> BLUE to "${version.ifEmpty { "?" }} · mesh member, not running"
            else -> BLUE to "${version.ifEmpty { "?" }} · installed, no debug API answered"
        }
        card.addView(text(ctx, app.label + (if (app.kind == "lib") "  · lib" else ""), 14f, WHITE, bold = true))
        card.addView(text(ctx, "● $status", 11f, dot))
        if (live != null) for (l in out) card.addView(linkRow(ctx, l, live, label, byId, onOpen))
        if (inn.isNotEmpty())
            card.addView(text(ctx, "← bound by " + inn.joinToString(", ") { label(it.from) }, 11f, DIM))
        live?.shares?.get(app.id)?.let { card.addView(text(ctx, "⇄ serves " + it.joinToString(" · "), 11f, DIM)) }
        if (live != null && version != null)
            card.addView(text(ctx, if (app.id in live.granted) "⇄ reads constellation data (CONSTELLATION_DATA granted)"
                else "✕ CONSTELLATION_DATA not granted — reinstall from our release", 11f,
                if (app.id in live.granted) DIM else RED))
        return card
    }

    private fun linkRow(
        ctx: Context, l: Link, live: Live, label: (String) -> String,
        byId: Map<String, Fleet.App>, onOpen: (Fleet.App) -> Unit,
    ): View {
        val s = state(l, live)
        val engine = label(l.engine)
        val line = when (s) {
            State.OK -> "→ ${l.name}: $engine ✓ handshake ok (contract ${live.contracts[l.key]})"
            State.APP_ABSENT -> "→ ${l.name}: $engine (${label(l.from)} not installed)"
            else -> "✕ ${label(l.from)} → ${l.name}: ${fix(l, live, engine)}"
        }
        return text(ctx, line, 12f, when (s) { State.OK -> GREEN; State.APP_ABSENT -> DIM; else -> RED }).apply {
            tag = "$TAG_LINK${l.from}>${l.engine}:${s.name}"
            // A broken link opens the ENGINE's Store row — that is where the fix is.
            byId[l.engine]?.let { e -> isClickable = true; setOnClickListener { onOpen(e) } }
        }
    }

    private fun heading(ctx: Context, t: String, color: Int) =
        text(ctx, t, 12f, color).apply { setPadding(0, dp(ctx, 10), 0, dp(ctx, 4)) }

    private fun text(ctx: Context, t: String, size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(ctx, 1), 0, dp(ctx, 1))
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
}
