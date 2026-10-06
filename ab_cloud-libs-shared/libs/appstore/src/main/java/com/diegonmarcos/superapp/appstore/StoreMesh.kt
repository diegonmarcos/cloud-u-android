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
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
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
 *     `/api/system/ping` with `pong <applicationId>` on its assigned port
 *     ([AppDebugServer.portOf], #792), or — for a build from before #792 or a
 *     member that fell back — somewhere in
 *     [AppDebugServer.PORT_FIRST]..[AppDebugServer.PORT_LAST]
 *   - endpoints: each reachable member's own `/api/docs` (#792)
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
        /** #792 fleet id -> that member's own /api/docs body (the fleet token
         *  authorises the read; the body names no secret). */
        val docs: Map<String, String> = emptyMap(),
        /** #793 members that did not answer and were NOT woken (the page's
         *  probe only looks; Wake / Wake all act). Not running, not a gap. */
        val asleep: Set<String> = emptySet(),
    )

    // ── #793 the probe result as JSON: the Apps Mesh cache ────────────────────

    fun toJson(l: Live): JSONObject = JSONObject()
        .put("installed", JSONObject(l.installed)).put("reachable", JSONObject(l.reachable))
        .put("peers", JSONArray(l.peers)).put("contracts", JSONObject(l.contracts))
        .put("shares", JSONObject(l.shares.mapValues { JSONArray(it.value) }))
        .put("granted", JSONArray(l.granted))
        .put("peerViews", JSONObject(l.peerViews.mapValues { JSONArray(it.value) }))
        .put("woken", JSONArray(l.woken)).put("docs", JSONObject(l.docs)).put("asleep", JSONArray(l.asleep))

    fun fromJson(o: JSONObject): Live {
        fun obj(k: String) = o.optJSONObject(k) ?: JSONObject()
        fun strs(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.optString(it) }.toSet()
        fun <T> field(k: String, f: (JSONObject, String) -> T) = obj(k).let { m -> m.keys().asSequence().associateWith { f(m, it) } }
        return Live(
            installed = field("installed") { m, k -> m.optString(k) },
            reachable = field("reachable") { m, k -> m.optInt(k) },
            peers = strs(o.optJSONArray("peers")),
            contracts = field("contracts") { m, k -> m.optInt(k) },
            shares = field("shares") { m, k -> strs(m.optJSONArray(k)).toList() },
            granted = strs(o.optJSONArray("granted")),
            peerViews = field("peerViews") { m, k -> strs(m.optJSONArray(k)) },
            woken = strs(o.optJSONArray("woken")),
            docs = field("docs") { m, k -> m.optString(k) },
            asleep = strs(o.optJSONArray("asleep")),
        )
    }

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

    /** The blocking whole-fleet probe (/api/fleet/endpoints): [probeEach]
     *  with stopped members woken, then every reachable member's /api/docs. */
    fun probe(ctx: Context, fleet: List<Fleet.App>, links: List<Link>, wake: Boolean = true): Live {
        val live = probeEach(ctx, fleet, links, wake)
        val selfId = fleet.firstOrNull { Fleet.installedId(ctx, it) == ctx.packageName }?.id
        return Live(live.installed, live.reachable, live.peers, live.contracts, live.shares, live.granted,
            live.peerViews, live.woken, docsOf(ctx, live.reachable, selfId), live.asleep)
    }

    /** #793 how many members are probed at once, and the per-member bound is
     *  the socket timeouts plus, when waking, [WAKE_TIMEOUT_MS]. */
    const val POOL = 12

    /**
     * #793 THE probe, member by member, so a page never waits on its slowest
     * app. First everything the PackageManager answers (installed, providers
     * and services, grants, engine handshakes, mesh membership) — emitted at
     * once through [onUpdate] with every member still pending. Then each
     * member on a bounded pool: pinged on its assigned port, its own
     * /api/fleet/peers read, and emitted the moment it lands. Members that did
     * not answer there get ONE range sweep (a build from before #792, or a
     * member that fell back). Those still silent are woken and re-pinged until
     * [WAKE_TIMEOUT_MS] when [wake], else recorded [Live.asleep].
     *
     * [only] re-probes just those members on top of [base] (a row's Wake);
     * everyone else keeps what [base] says. [onUpdate] gets the snapshot and
     * the ids still pending; it is called from pool threads.
     */
    fun probeEach(
        ctx: Context, fleet: List<Fleet.App>, links: List<Link>, wake: Boolean,
        only: Set<String>? = null, base: Live? = null,
        onUpdate: (Live, Set<String>) -> Unit = { _, _ -> },
    ): Live {
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
        val todo = peers.filter { only == null || it in only }.toSet()
        fun keep(m: Map<String, *>?) = m.orEmpty().keys.filter { it !in todo }
        val reachable = ConcurrentHashMap<String, Int>()
        val peerViews = ConcurrentHashMap<String, Set<String>>()
        val woken = ConcurrentHashMap.newKeySet<String>()
        val asleep = ConcurrentHashMap.newKeySet<String>()
        base?.let { b ->
            keep(b.reachable).forEach { reachable[it] = b.reachable.getValue(it) }
            keep(b.peerViews).forEach { peerViews[it] = b.peerViews.getValue(it) }
            woken.addAll(b.woken - todo); asleep.addAll(b.asleep - todo)
        }
        val cachedDocs = base?.docs.orEmpty()
        val pending = ConcurrentHashMap.newKeySet<String>().apply { addAll(todo) }
        fun snapshot() = Live(HashMap(installed), HashMap(reachable), peers, contracts, shares, granted,
            HashMap(peerViews), HashSet(woken), cachedDocs, HashSet(asleep))
        val lock = Any()
        fun emit(done: Collection<String>) = synchronized(lock) {
            pending.removeAll(done.toSet()); onUpdate(snapshot(), HashSet(pending))
        }
        emit(emptyList())

        val token = FleetToken.get(ctx)
        // #792 this process answers for itself without a socket: probed from
        // the debug server's own accept thread (/api/fleet/endpoints), a
        // loopback ping of our own port would wait on the thread sending it.
        val self = ctx.packageName
        fun found(id: String, port: Int) {
            reachable[id] = port
            if (pkgOf[id] == self) { peerViews[id] = peers; return }
            val body = get(port, "/api/fleet/peers", token) ?: return
            runCatching {
                val arr = JSONObject(body).getJSONArray("peers")
                peerViews[id] = (0 until arr.length()).mapNotNull { idOf[arr.getJSONObject(it).optString("pkg")] }.toSet()
            }
        }
        fun onAssigned(pkg: String): Int? =
            if (pkg == self) AppDebugServer.boundPort().takeIf { it > 0 }
            else AppDebugServer.portOf(pkg)?.takeIf { ping(it) == pkg }

        val pool = Executors.newFixedThreadPool(POOL)
        try {
            fun eachOn(ids: Collection<String>, work: (String) -> Unit) =
                ids.map { id -> pool.submit(Callable { runCatching { work(id) }; Unit }) }.forEach { runCatching { it.get() } }
            eachOn(todo) { id ->
                val port = pkgOf[id]?.let { onAssigned(it) }
                if (port != null) { found(id, port); emit(listOf(id)) }
            }
            var silent = todo - reachable.keys
            if (silent.isNotEmpty()) {
                val swept = locateIn(silent.mapNotNull { pkgOf[it] }, AppDebugServer.PORT_FIRST..AppDebugServer.PORT_LAST)
                val hits = swept.mapNotNull { (pkg, port) -> idOf[pkg]?.let { it to port } }
                eachOn(hits.map { it.first }) { id -> found(id, hits.first { it.first == id }.second) }
                if (hits.isNotEmpty()) emit(hits.map { it.first })
                silent = silent - reachable.keys
            }
            // #733 "no debug API" must mean the member CANNOT serve one, not
            // that it was merely asleep: wake what did not answer, then re-ping
            // until it does or [WAKE_TIMEOUT_MS] is spent (#762). A
            // force-stopped app stays stopped, which is the gap the page names.
            if (wake) eachOn(silent) { id ->
                val pkg = pkgOf.getValue(id)
                FleetPeers.wake(ctx, pkg)
                val sweep = { onAssigned(pkg)?.let { mapOf(id to it) } ?: locate(listOf(pkg))[pkg]?.let { mapOf(id to it) }.orEmpty() }
                awaitWoken(emptyMap(), setOf(id), WAKE_TIMEOUT_MS, WAKE_POLL_MS, sweep, Thread::sleep)[id]
                    ?.let { found(id, it); woken.add(id) }
                emit(listOf(id))
            } else asleep.addAll(silent)
        } finally { pool.shutdownNow() }
        emit(todo)
        return snapshot()
    }

    /** #792 one member's own /api/docs; this process answers from memory. */
    fun docs(ctx: Context, port: Int, self: Boolean): String? =
        if (self) AppDebugServer.docs(ctx) else get(port, "/api/docs", FleetToken.get(ctx))

    /** #792 every reachable member's /api/docs, fetched in parallel. */
    private fun docsOf(ctx: Context, reachable: Map<String, Int>, selfId: String?): Map<String, String> {
        val pool = Executors.newFixedThreadPool(8)
        return try {
            reachable.map { (id, port) ->
                pool.submit(Callable { docs(ctx, port, id == selfId)?.let { id to it } })
            }.mapNotNull { runCatching { it.get() }.getOrNull() }.toMap()
        } finally { pool.shutdownNow() }
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

    /**
     * #792 package -> port for [expected]. Each is pinged on its ASSIGNED port
     * first; only when one of them did not answer there (a build from before
     * #792, or a member that fell back off a held port) is the whole range
     * swept. Raw socket, not HttpURLConnection: loopback cleartext is then not
     * subject to the host's network-security policy. Public for the SuperApp's
     * DNS page (#794), which asks every member the same way.
     */
    fun locate(expected: Collection<String>): Map<String, Int> {
        val direct = locateIn(expected, expected.mapNotNull { AppDebugServer.portOf(it) }.distinct())
        return if (direct.keys.containsAll(expected)) direct
        else locateIn(expected, AppDebugServer.PORT_FIRST..AppDebugServer.PORT_LAST)
    }

    /** package -> port for whichever of [expected] answers on [ports]. */
    private fun locateIn(expected: Collection<String>, ports: Iterable<Int>): Map<String, Int> {
        val pool = Executors.newFixedThreadPool(16)
        return try {
            ports.map { port -> pool.submit(Callable { ping(port)?.let { it to port } }) }
                .mapNotNull { runCatching { it.get() }.getOrNull() }
                .toMap().filterKeys { it in expected }
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

    /** #793 what [render] drew, so a page can refill one member's card as its
     *  probe lands and hide what a filter excludes, without redrawing the rest. */
    class Drawn(val summary: TextView, val cards: Map<String, LinearLayout>, val headings: List<Pair<View, List<String>>>)

    /**
     * Draw the mesh into [into]. [live] null = nothing known yet: every node
     * is drawn, statuses read "probing". [pending] are members whose probe is
     * still running — drawn from what [live] already says, marked ⟳.
     * [onOpen] receives a tapped link's engine. [extras] is the host's own
     * per-member block (Apps Mesh: the row buttons and the endpoints panel),
     * appended to each card.
     */
    fun render(
        ctx: Context, into: LinearLayout, fleetJson: JSONObject, fleet: List<Fleet.App>,
        links: List<Link>, live: Live?, pending: Set<String> = emptySet(),
        extras: ((Fleet.App) -> View?)? = null, onOpen: (Fleet.App) -> Unit,
    ): Drawn {
        val byId = fleet.associateBy { it.id }
        val label = { id: String -> byId[id]?.label ?: id }
        val broken = if (live == null) emptyList()
            else links.filter { state(it, live) == State.ENGINE_MISSING || state(it, live) == State.ENGINE_OLD }

        val summary = text(ctx, summaryLine(fleet, links, live, pending), StoreDensity.T_META, DIM)
        into.addView(summary)

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

        val cards = LinkedHashMap<String, LinearLayout>()
        val headings = ArrayList<Pair<View, List<String>>>()
        for ((title, rows) in runs) {
            val h = heading(ctx, title, AMBER)
            into.addView(h); headings.add(h to rows.map { it.id })
            for (app in rows) {
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    tag = TAG_NODE + app.id
                    setBackgroundColor(0xFF1C1C24.toInt())
                    setPadding(dp(ctx, StoreDensity.S12), dp(ctx, StoreDensity.S6), dp(ctx, StoreDensity.S12), dp(ctx, StoreDensity.S6))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(ctx, StoreDensity.S2), 0, dp(ctx, StoreDensity.S2)) }
                }
                fill(ctx, card, app, live, app.id in pending, links, byId, extras, onOpen)
                into.addView(card); cards[app.id] = card
            }
        }
        return Drawn(summary, cards, headings)
    }

    /** The line over the mesh: counts of what is known, and how many are still probing. */
    fun summaryLine(fleet: List<Fleet.App>, links: List<Link>, live: Live?, pending: Set<String>): String {
        if (live == null) return "${fleet.size} members · probing…"
        val broken = links.count { state(it, live) == State.ENGINE_MISSING || state(it, live) == State.ENGINE_OLD }
        return "${fleet.size} members · ${live.installed.size} installed · ${live.reachable.size} reachable · " +
            "${links.size} engine links, $broken broken" + if (pending.isEmpty()) "" else " · ⟳ ${pending.size} probing"
    }

    const val TAG_STATUS = "store-mesh-status:"

    /** (Re)fill one member's card from [live]; [probing] marks it still loading. */
    fun fill(
        ctx: Context, card: LinearLayout, app: Fleet.App, live: Live?, probing: Boolean, links: List<Link>,
        byId: Map<String, Fleet.App>, extras: ((Fleet.App) -> View?)?, onOpen: (Fleet.App) -> Unit,
    ) {
        card.removeAllViews()
        val label = { id: String -> byId[id]?.label ?: id }
        val out = links.filter { it.from == app.id }
        val inn = links.filter { it.engine == app.id }
        val version = live?.installed?.get(app.id)
        val port = live?.reachable?.get(app.id)
        val (dot, status) = when {
            live == null -> DIM to "probing…"
            version == null -> DIM to "not installed"
            port != null -> GREEN to "${version.ifEmpty { "?" }} · ${address(app, port)}" +
                if (app.id in live.woken) " · was stopped, woke ok" else ""
            app.id in live.asleep -> BLUE to "${version.ifEmpty { "?" }} · mesh member, not running — Wake starts it"
            app.id in live.peers -> BLUE to "${version.ifEmpty { "?" }} · mesh member, not running"
            else -> BLUE to "${version.ifEmpty { "?" }} · installed, no debug API answered"
        }
        card.addView(text(ctx, app.label + (if (app.kind == "lib") "  · lib" else ""), StoreDensity.T_TITLE, WHITE, bold = true))
        card.addView(text(ctx, (if (probing && live != null) "⟳ " else "● ") + status, StoreDensity.T_CAPTION, dot).apply {
            tag = TAG_STATUS + app.id + if (probing) ":probing" else ""
        })
        if (live != null) for (l in out) card.addView(linkRow(ctx, l, live, label, byId, onOpen))
        if (inn.isNotEmpty())
            card.addView(text(ctx, "← bound by " + inn.joinToString(", ") { l ->
                label(l.from) + (live?.let { " " + state(l, it).name } ?: "") }, StoreDensity.T_CAPTION, DIM))
        live?.shares?.get(app.id)?.let { card.addView(text(ctx, "⇄ serves " + it.joinToString(" · "), StoreDensity.T_CAPTION, DIM)) }
        if (live != null && version != null)
            card.addView(text(ctx, if (app.id in live.granted) "⇄ reads constellation data (CONSTELLATION_DATA granted)"
                else "✕ CONSTELLATION_DATA not granted — reinstall from our release", StoreDensity.T_CAPTION,
                if (app.id in live.granted) DIM else RED))
        extras?.invoke(app)?.let { v -> (v.parent as? android.view.ViewGroup)?.removeView(v); card.addView(v) }
    }

    /** #792 a member's full loopback address, and — when it is not on the port
     *  debug-ports.json gives it — the port it should have had, so a fallback
     *  (or a build from before #792) is visible rather than silently fine. */
    fun address(app: Fleet.App, port: Int): String {
        val assigned = AppDebugServer.portOf(app.pkg)
        return "http://127.0.0.1:$port" + when {
            assigned == null -> " (no assigned port)"
            assigned != port -> " (assigned :$assigned — not bound there)"
            else -> ""
        }
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
        return text(ctx, line, StoreDensity.T_META, when (s) { State.OK -> GREEN; State.APP_ABSENT -> DIM; else -> RED }).apply {
            tag = "$TAG_LINK${l.from}>${l.engine}:${s.name}"
            // A broken link opens the ENGINE's Store row — that is where the fix is.
            byId[l.engine]?.let { e -> isClickable = true; setOnClickListener { onOpen(e) } }
        }
    }

    private fun heading(ctx: Context, t: String, color: Int) =
        text(ctx, t, StoreDensity.T_META, color).apply { setPadding(0, dp(ctx, StoreDensity.S8), 0, dp(ctx, StoreDensity.S4)) }

    private fun text(ctx: Context, t: String, size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(ctx, StoreDensity.S1), 0, dp(ctx, StoreDensity.S1))
    }

    private fun dp(ctx: Context, v: Int) = StoreDensity.dp(ctx, v)
}
