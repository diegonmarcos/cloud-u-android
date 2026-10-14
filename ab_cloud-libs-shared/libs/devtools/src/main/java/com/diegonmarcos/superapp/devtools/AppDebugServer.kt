package com.diegonmarcos.superapp.devtools

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import java.io.File
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The constellation-wide debug API: the route table EVERY app serves, not just
 * the ones someone remembered to wire it into.
 *
 * #ipc ONE DOOR PER PHONE (a0_docs/eng-specs/fleet-ipc.md). No app binds a port
 * of its own any more. A sibling on the phone asks over the fleet IPC layer
 * ([FleetIpc]: content://<pkg>.fleet, signature-guarded); a caller OFF the phone
 * (a PC, the MCP, an agent, CI) asks the SuperApp's gateway, the one fleet
 * listener (127.0.0.1:38080, the fleet token), at /app/<pkg>/api/<op>, and the
 * gateway forwards it over the same IPC door. Both land in [dispatch]. The port
 * table below survives as the VERSION-SKEW map: a phone still running a member
 * built before this listens on its assigned port, and the gateway and Apps Mesh
 * ask it there.
 *
 * Why this exists
 * ---------------
 * An app can always read its OWN logcat with no permission at all — Android
 * filters logcat by uid, and READ_LOGS (needed to see anyone else's) is
 * signature|privileged against the PLATFORM key, which our shared Cloud
 * signing key is not. So no amount of constellation signing lets the SuperApp
 * read cloud-news's logs from outside. The only way to get an app's logs is
 * for that app to serve them itself. Hence: this ships in libs:devtools, which
 * libs:core exposes via `api`, so every app that links core answers with zero
 * per-app code.
 *
 * Auth is the door's: the signature permission on the IPC door, the fleet
 * token at the gateway. Port: #792 each package's fixed port in
 * 1_cicd/src/data/debug-ports.json, now only where an OLD build of it listens.
 */
object AppDebugServer {
    private const val TAG = "AppDebugServer"

    /** The facility's whole range, from debug-ports.json. 38080 is outside it
     *  on purpose — it belongs to the app-owned DevControlServer in SuperApp
     *  and cloud-nav. Engine APKs count as members too (Cloud-Lib-News.apk
     *  serves its own NewsEngine logs), which is how fifty ports ran out. */
    val PORT_FIRST: Int = BuildConfig.DEBUG_PORT_FIRST
    val PORT_LAST: Int = BuildConfig.DEBUG_PORT_LAST

    /** #796 the sub-range a member scans when its own port is held: inside the
     *  range, never assigned to any package. Declared in the table so a build
     *  that knows only its own port still cannot land on a sibling's. */
    val FALLBACK_FIRST: Int = BuildConfig.DEBUG_FALLBACK_FIRST
    val FALLBACK_LAST: Int = BuildConfig.DEBUG_FALLBACK_LAST

    /** #792 applicationId → its assigned port: this build's slice of the table
     *  (#796) — its own package(s), or the whole fleet in the SuperApp. */
    val PORTS: Map<String, Int> by lazy { parsePorts(BuildConfig.DEBUG_PORTS) }

    /** `pkg=port,pkg=port` (BuildConfig cannot hold a map) → map. */
    internal fun parsePorts(raw: String): Map<String, Int> = raw.split(',').mapNotNull {
        val pkg = it.substringBefore('=', "").trim()
        val port = it.substringAfter('=', "").trim().toIntOrNull()
        if (pkg.isEmpty() || port == null) null else pkg to port
    }.toMap()

    /** The port [pkg] binds, or null for a package the table does not name. */
    fun portOf(pkg: String): Int? = PORTS[pkg]

    /** logcat -t is cheap but not free, and `n` arrives from the query
     *  string. Without a ceiling one request can pull the entire ring buffer. */
    private const val MAX_LINES = 20_000
    private const val DEFAULT_LINES = 300

    /** The app's own on/off (Cloud Account Settings > Debug API, `debug.api`): off, its door
     *  answers system/ping and nothing else. On unless switched off. */
    private val served = AtomicBoolean(true)

    /** #ipc no app listens any more: always -1. Kept so callers from the port era compile. */
    fun boundPort(): Int = -1

    /** Whether this app serves its routes (through its IPC door and the gateway). */
    fun isRunning(): Boolean = served.get()

    /** App-specific data groups added via [route], keyed by first path segment. */
    private val routes =
        ConcurrentHashMap<String, (String, Map<String, String>) -> String?>()

    /**
     * One entry of an app group's catalog, as it appears in /api/docs.
     *
     * Same three fields the universal endpoints already publish, so a client
     * reads one list and does not care which half an entry came from.
     */
    // @JvmOverloads: the terminals' TerminalDebugApi.java calls Op(op, params, description); a
    // Kotlin default (maxBody, #802) is invisible to Java without the generated overloads.
    data class Op @JvmOverloads constructor(
        val op: String,
        val params: String = "",
        val description: String = "",
        /** #802 the most body bytes this op accepts from a fleet-authenticated caller (a vault
         *  bundle is ~2 MB); every other request keeps [MAX_BODY_BYTES]. */
        val maxBody: Int = MAX_BODY_BYTES,
    )

    /** Catalogs supplied via the documenting [route] overload, keyed like
     *  [routes]. A group registered without one still serves — it is simply
     *  undiscoverable, which is the pre-existing behaviour. */
    private val routeDocs = ConcurrentHashMap<String, List<Op>>()

    /**
     * Register an app's own data group: `route("news") { op, q -> ... }` serves
     * `/api/news/<op>`, returning null for 404. Call it from Application.onCreate.
     *
     * This library deliberately knows nothing about articles, events or
     * contacts. The transport, the fleet auth and the diagnostics are universal;
     * the payloads are not. Handlers run on a socket thread and sit behind the
     * token check above, so an app never authenticates anything itself.
     */
    fun route(group: String, handler: (op: String, query: Map<String, String>) -> String?) {
        routes[group.trim('/')] = handler
    }

    /**
     * Same registration, plus the catalog /api/docs should advertise for it.
     *
     * Without this an app's own routes are reachable but invisible: /api/docs
     * listed only the six universal endpoints, so anything reading the catalog
     * to find out what an app serves — cloud-superapp-mcp's `superapp_docs`,
     * or a human with curl — concluded every app served the same six. The
     * alternative was a route table mirrored outside the app, and this
     * constellation already has enough hand-kept mirrors that drift.
     *
     * `route("news", listOf(Op("latest", "n=count", "newest headlines"))) { op, q -> ... }`
     */
    fun route(
        group: String,
        ops: List<Op>,
        handler: (op: String, query: Map<String, String>) -> String?,
    ) {
        val g = group.trim('/')
        routes[g] = handler
        routeDocs[g] = ops
    }

    private fun peersJson(ctx: Context): String {
        val me = ctx.packageName
        val peers = FleetPeers.list(ctx)
        return buildString {
            append("""{"self":"${esc(me)}","count":${peers.size},"peers":[""")
            peers.forEachIndexed { i, p ->
                if (i > 0) append(',')
                append("""{"pkg":"${esc(p)}","self":${p == me}}""")
            }
            append("]}")
        }
    }

    private fun appRoute(op: String, query: Map<String, String>): String? {
        val group = op.substringBefore('/')
        val rest = op.substringAfter('/', "")
        return runCatching { routes[group]?.invoke(rest, query) }
            .onFailure { Log.w(TAG, "route $group: $it") }
            .getOrNull()
    }

    /** #ipc serve this app's routes (through its IPC door and the gateway). Binds nothing. */
    fun start(ctx: Context) {
        served.set(true)
        Log.i(TAG, "serving ${ctx.packageName} over content://${ctx.packageName}.fleet (no port)")
    }

    fun stop() { served.set(false) }

    /**
     * #ipc THE route table, one answer for every way in: a sibling's IPC call and the SuperApp
     * gateway both arrive through [FleetIpc.serve], which has already checked the caller.
     * [query] carries `_body` when the request had one. Switched off ([stop]): ping only.
     */
    fun dispatch(ctx: Context, op: String, query: Map<String, String>): FleetIpcWire.Reply {
        fun ok(body: String, type: String = "text/plain") = FleetIpcWire.Reply(200, body, type, FleetIpcWire.Via.SELF)
        val json = "application/json"
        if (!served.get() && op != "system/ping") return FleetIpcWire.Reply(503, "debug API switched off in this app (Cloud Account > Settings > Debug API)\n")
        return when (op) {
            "docs" -> ok(docsJson(ctx), json)
            "system/ping" -> ok("pong ${ctx.packageName}\n")
            "system/info" -> ok(infoJson(ctx), json)
            "diagnostics/logcat" -> ok(readLogcat((query["n"]?.toIntOrNull() ?: DEFAULT_LINES).coerceIn(1, MAX_LINES)))
            "diagnostics/crashes" -> ok(readCrashes(ctx))
            "fleet/peers" -> ok(peersJson(ctx), json)
            "net/dns" -> ok("{${dnsFields(ctx)}}", json)
            "net/resolve" -> ok(resolveJson(ctx, query["host"].orEmpty()), json)
            "fleet/wake" -> {
                val pkg = query["pkg"].orEmpty()
                if (pkg.isBlank()) FleetIpcWire.Reply(400, "need ?pkg=<applicationId>\n")
                else FleetPeers.wake(ctx, pkg).let { woke ->
                    FleetIpcWire.Reply(if (woke) 200 else 502, """{"pkg":"${esc(pkg)}","woken":$woke}""", json)
                }
            }
            else -> appRoute(op, query)?.let { ok(it, json) }
                ?: FleetIpcWire.Reply(404, "not found — see /api/docs\n")
        }
    }

    /** #802 a request may carry at most this many body bytes; past it, 413. */
    const val MAX_BODY_BYTES = 256 * 1024

    /** The body ceiling of an app op: what its [Op.maxBody] declares, else [MAX_BODY_BYTES]. */
    fun bodyLimit(op: String): Int =
        routeDocs[op.substringBefore('/')]?.firstOrNull { it.op == op.substringAfter('/', "") }?.maxBody ?: MAX_BODY_BYTES

    /** The query a route sees: `_body` only ever comes from the body, never the
     *  query string, so a handler can trust where it came from. */
    fun withBody(query: Map<String, String>, body: String?): Map<String, String> =
        if (body == null) query - "_body" else query - "_body" + ("_body" to body)

    /** Identity WITHOUT the host app's BuildConfig. A library module's own
     *  BuildConfig cannot see the app's, and reading it through an interface
     *  the app must implement would defeat the point of zero per-app wiring —
     *  PackageManager already knows all of this at runtime. */
    private fun infoJson(ctx: Context): String {
        val pm = ctx.packageManager
        val pkg = ctx.packageName
        val pi = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
        val label = runCatching { pm.getApplicationLabel(ctx.applicationInfo).toString() }
            .getOrDefault(pkg)
        val versionCode = when {
            pi == null -> -1L
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> pi.longVersionCode
            // Braces are required: an annotation cannot sit on a bare `else ->`
            // arm — the parser reads it as a new element, the `when` loses its
            // else branch, and the next line fails to parse.
            else -> {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }
        }
        return buildString {
            append("{")
            append(""""applicationId":"${esc(pkg)}",""")
            append(""""label":"${esc(label)}",""")
            append(""""versionName":"${esc(pi?.versionName ?: "")}",""")
            append(""""versionCode":$versionCode,""")
            append(""""lastUpdateTime":${pi?.lastUpdateTime ?: 0},""")
            append(""""door":"content://${esc(pkg)}.fleet",""")
            append(""""gateway":"${esc(FleetIpc.gatewayBase(pkg))}",""")
            append(""""legacyPort":${portOf(pkg) ?: -1},""")
            append(""""device":"${esc("${Build.MANUFACTURER} ${Build.MODEL}")}",""")
            append(""""android":"${esc(Build.VERSION.RELEASE)}",""")
            append(""""sdk":${Build.VERSION.SDK_INT}""")
            append("}")
        }
    }

    /** This process's own /api/docs body, without asking itself over IPC. */
    fun docs(ctx: Context): String = docsJson(ctx)

    private fun docsJson(ctx: Context): String = buildString {
        append("{")
        append(""""applicationId":"${esc(ctx.packageName)}",""")
        append(""""door":"content://${esc(ctx.packageName)}.fleet",""")
        append(""""base":"${esc(FleetIpc.gatewayBase(ctx.packageName))}",""")
        append(""""auth":"off the phone: the SuperApp gateway, Bearer <fleet token> (Configs > About), one token """)
        append("""for the whole fleet; on the phone: the IPC door, signature permission CONSTELLATION_DATA",""")
        append(""""body":"a POST body (max $MAX_BODY_BYTES bytes unless the op declares a larger max_body, else 413) reaches an app route """)
        append("""as query param _body; _body in the query string is dropped",""")
        append(""""endpoints":[""")
        append("""{"path":"/api/docs","description":"this catalog"},""")
        append("""{"path":"/api/system/ping","description":"liveness + applicationId"},""")
        append("""{"path":"/api/system/info","description":"applicationId, label, version, IPC door, gateway route, device"},""")
        append("""{"path":"/api/diagnostics/logcat","params":"n=lines (default $DEFAULT_LINES, max $MAX_LINES)","description":"this app's own logcat, threadtime format"},""")
        append("""{"path":"/api/diagnostics/crashes","description":"stored crash reports, newest first"},""")
        append("""{"path":"/api/fleet/peers","description":"installed mesh members"},""")
        append("""{"path":"/api/fleet/wake","params":"pkg=<applicationId>",""")
        append(""""description":"start a member's process via its provider — no activity """)
        append("""launch, so Android background-start restrictions do not apply"},""")
        append("""{"path":"/api/net/dns","description":"#741 the DNS this app inherits: whether its """)
        append("""default network is the VPN, that network's DNS servers (under the SuperApp's VPN, the """)
        append("""Configs > Mesh > DNS upstreams) and Android's Private DNS state"},""")
        append("""{"path":"/api/net/resolve","params":"host=<name>","description":"#741 resolve <name> """)
        append("""with Android's resolver for this app's uid: addresses + ms, or the failure in words, """)
        append("""plus the /api/net/dns view it was answered under"}""")
        append("],")
        append(""""groups":[""")
        routeDocs.entries.sortedBy { it.key }.forEachIndexed { gi, (group, ops) ->
            if (gi > 0) append(',')
            append("""{"group":"${esc(group)}","endpoints":[""")
            ops.forEachIndexed { i, o ->
                if (i > 0) append(',')
                append("""{"path":"/api/${esc(group)}/${esc(o.op)}",""")
                append(""""params":"${esc(o.params)}",""")
                if (o.maxBody != MAX_BODY_BYTES) append(""""max_body":${o.maxBody},""")
                append(""""description":"${esc(o.description)}"}""")
            }
            append("]}")
        }
        append("]}")
    }

    /**
     * #741 the DNS this app's uid inherits, as JSON fields (no braces).
     *
     * Every app reads it from its own process because that is the only place
     * the answer is true: the default network is per uid (an app the VPN
     * excludes sees the underlying one), and under the SuperApp's VPN that
     * network's DNS servers ARE the upstreams the Configs > Mesh > DNS menu
     * put in the tunnel, while "Mirror Android" leaves them empty and Private
     * DNS answers instead. Nothing here picks a resolver; it reports Android's.
     */
    /** #874 What this app's own code resolves through, when it is a bridge ("bridge 127.0.0.1:2053"); null = Android's resolver directly. */
    @Volatile var dnsVia: () -> String? = { null }

    private fun dnsFields(ctx: Context): String {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val net = runCatching { cm?.activeNetwork }.getOrNull()
        val lp = runCatching { net?.let { cm?.getLinkProperties(it) } }.getOrNull()
        val vpn = runCatching { net?.let { cm?.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } }
            .getOrNull() == true
        val p28 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        // #889 netpolicy (doze / Android 15 background restriction) hides the default network from a
        // blocked uid: activeNetwork is null exactly as with no network at all, and only the legacy
        // NetworkInfo says BLOCKED. Reported apart, or the page reads it as "network DNS none".
        @Suppress("DEPRECATION")
        val blocked = net == null &&
            runCatching { cm?.activeNetworkInfo?.detailedState == android.net.NetworkInfo.DetailedState.BLOCKED }.getOrNull() == true
        return dnsFields(
            network = if (blocked) "blocked" else if (net == null) null else if (vpn) "vpn" else "direct",
            servers = lp?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty(),
            privateActive = if (p28) lp?.isPrivateDnsActive else null,
            privateServer = if (p28) lp?.privateDnsServerName else null,
            via = runCatching { dnsVia() }.getOrNull(),
        )
    }

    internal fun dnsFields(network: String?, servers: List<String>, privateActive: Boolean?, privateServer: String?, via: String? = null): String =
        """"resolver":"${if (via == null) "android" else "bridge"}","network":${jsonStr(network)},""" +
            """"upstreams":[${servers.joinToString(",") { jsonStr(it) }}],""" +
            """"private_dns":{"active":$privateActive,"server":${jsonStr(privateServer)}}""" +
            (via?.let { ""","via":${jsonStr(it)}""" } ?: "")

    /** #741 Android's answer for [host] as this uid gets it — the lookup every OkHttp,
     *  HttpURLConnection and WebView request here makes, and the one libs:sysdns'
     *  tunnel makes for a bundled binary. No second resolver: the point is to show
     *  what the menu's choice gives the app. Moved here from cloud-drive (#758) so
     *  every app that links libs:core answers it, not just the one that had it. */
    private fun resolveJson(ctx: Context, host: String): String {
        if (host.isBlank()) return """{"ok":false,"why":"host= is required: the name to resolve",${dnsFields(ctx)}}"""
        val start = System.nanoTime()
        return runCatching { InetAddress.getAllByName(host).mapNotNull { it.hostAddress } }.fold(
            { found ->
                val ms = (System.nanoTime() - start) / 1_000_000
                """{"ok":true,"host":"${esc(host)}","addresses":[${found.joinToString(",") { jsonStr(it) }}],"ms":$ms,${dnsFields(ctx)}}"""
            },
            { e ->
                val why = "DNS: no address for $host from Android's resolver (${e.javaClass.simpleName}: ${e.message})"
                """{"ok":false,"host":"${esc(host)}","why":"${esc(why)}",${dnsFields(ctx)}}"""
            },
        )
    }

    private fun jsonStr(s: String?): String = if (s == null) "null" else "\"${esc(s)}\""

    /**
     * THE own-process logcat reader. Public because it is also what the
     * install-failure Diagnose screen embeds — there is one logcat reader in
     * the constellation and this is it. The About page's live viewer and the
     * /api/diagnostics/logcat route are the other two callers.
     *
     * Reads whatever the host app's uid is entitled to and nothing more, which
     * for every app in the fleet means its OWN lines (logd's SimpleLogBuffer
     * drops entries from other uids for a process without the `log` gid).
     *
     * Served from [LogPipe] rather than its own `logcat -d`, because in an app
     * that DOES hold READ_LOGS every separate exec is a separate consent
     * prompt. One stream per process, one prompt per process; see LogPipe.
     */
    fun readLogcat(n: Int): String = LogPipe.tail(n)

    /** Reads what AppCrashLogger wrote. The directory name is the only
     *  contract between writer and reader. */
    private fun readCrashes(ctx: Context): String = runCatching {
        val dir = File(ctx.getExternalFilesDir(null), "crashes")
        if (!dir.exists()) return@runCatching "no crashes directory yet\n"
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) return@runCatching "no crash files\n"
        files.joinToString("\n\n──────────────────────────\n\n") {
            "[${it.name}]\n" + it.readText()
        }
    }.getOrElse { "crash read failed: $it\n" }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
