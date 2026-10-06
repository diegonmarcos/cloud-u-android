package com.diegonmarcos.superapp.devtools

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The constellation-wide debug API: a loopback HTTP surface that EVERY app
 * gets, not just the ones someone remembered to wire it into.
 *
 * Why this exists
 * ---------------
 * An app can always read its OWN logcat with no permission at all — Android
 * filters logcat by uid, and READ_LOGS (needed to see anyone else's) is
 * signature|privileged against the PLATFORM key, which our shared Cloud
 * signing key is not. So no amount of constellation signing lets the SuperApp
 * read cloud-news's logs from outside. The only way to get an app's logs is
 * for that app to serve them itself. Hence: this ships in libs:devtools, which
 * libs:core exposes via `api`, so every app that links core answers on
 * loopback with zero per-app code.
 *
 * Deliberately NOT the same thing as the app-owned DevControlServer
 * ----------------------------------------------------------------
 * SuperApp and cloud-nav each own a big DevControlServer on port 38080 with
 * dozens of app-specific routes under nav, battery and adb. Those stay exactly
 * where they are — this binds a different port range and only serves the
 * handful of endpoints that need no app-specific types whatsoever. Extracting
 * those two servers is a separate job; this one had to be safe to drop into
 * eight apps at once.
 *
 * Auth: one [FleetToken] for the whole fleet, as `Authorization: Bearer <t>`,
 * on every route except /api/system/ping. Loopback alone is not a boundary
 * here — it is device-wide on Android, so any installed app holding INTERNET
 * can reach these ports — and it stopped being defensible entirely once app
 * data started riding these routes beside the logs. One token rather than one
 * per app because fifteen secrets to read one log is how a debug facility goes
 * unused: SuperApp mints it, siblings adopt it over a signature-guarded
 * provider, and the human reads it once from Configs → About.
 *
 * Port: #792 each package's OWN fixed port from the fleet table
 * 1_cicd/src/data/debug-ports.json, so port→app is known before anything is
 * probed. It used to be the first free port in a 50-port range, and with sixty
 * mesh members the last ones to wake found nothing free and served nowhere.
 * Only when a foreign process holds the assigned port does a member scan, and
 * then only [FALLBACK_FIRST]..[FALLBACK_LAST], a sub-range NO package is ever
 * assigned, so one collision cannot cascade into the next member's slot.
 * #796: build.gradle bakes this root's SLICE of the table ([PORTS]: its own
 * package(s); the SuperApp, which probes everyone, bakes it whole), so a new
 * member's port no longer rebuilds every app.
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

    /** #792 the ports a member may take when its own is held: [first]..[last]
     *  minus [owned] — called with the fallback sub-range, which no package
     *  owns, so a fallback never lands in a sibling's slot and pushes THAT
     *  member into a fallback of its own. */
    internal fun fallbackPorts(first: Int, last: Int, owned: Collection<Int>): List<Int> {
        val taken = owned.toHashSet()
        return (first..last).filter { it !in taken }
    }

    /** A client that opens a socket and never sends a request line would
     *  otherwise park the single accept thread forever and take the whole
     *  debug API down with it. */
    private const val SOCKET_TIMEOUT_MS = 5_000

    /** logcat -t is cheap but not free, and `n` arrives from the query
     *  string. Without a ceiling one request can pull the entire ring buffer. */
    private const val MAX_LINES = 20_000
    private const val DEFAULT_LINES = 300

    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var thread: Thread? = null

    @Volatile
    private var port: Int = -1

    /** Actual bound port, or -1 if not running. Not a constant: which port an
     *  app lands on depends on how many constellation apps started first. */
    fun boundPort(): Int = port

    fun isRunning(): Boolean = running.get()

    /**
     * Reachable without the fleet token: liveness plus the applicationId, and
     * nothing else. Everything that returns state or data, /api/docs included,
     * needs the token.
     *
     * The package name is deliberately in the open half. A member that fell
     * back off its assigned port ([bindOwn]) is somewhere in
     * [PORT_FIRST]..[PORT_LAST], so port→app is the one fact you need before
     * you can ask anything useful, and gating it makes the
     * facility undebuggable in exactly the case you reach for it — a member
     * that cannot adopt the token answers 401 everywhere and you cannot even
     * tell which app is stuck. It gives an attacker nothing: any app can
     * already enumerate installed packages and portscan loopback, so this only
     * saves them the join.
     */
    private val OPEN_OPS = setOf("system/ping")

    /** App-specific data groups added via [route], keyed by first path segment. */
    private val routes =
        ConcurrentHashMap<String, (String, Map<String, String>) -> String?>()

    /**
     * One entry of an app group's catalog, as it appears in /api/docs.
     *
     * Same three fields the universal endpoints already publish, so a client
     * reads one list and does not care which half an entry came from.
     */
    data class Op(
        val op: String,
        val params: String = "",
        val description: String = "",
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

    /** `Authorization: Bearer <t>` → `<t>`; null for any other header. Case and
     *  spacing are the client's to get wrong, not ours — curl, OkHttp and a
     *  browser each format this line a little differently, and a parse that only
     *  handled one of them would read as "the token is broken". */
    internal fun bearerOf(header: String): String? {
        if (!header.startsWith("Authorization:", ignoreCase = true)) return null
        val v = header.substringAfter(':').trim()
        if (!v.startsWith("Bearer", ignoreCase = true)) return null
        return v.substring("Bearer".length).trim().ifEmpty { null }
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

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (!running.compareAndSet(false, true)) return
        val sock = bindOwn(app.packageName)
        if (sock == null) {
            Log.w(TAG, "no port bound: own ${portOf(app.packageName) ?: "unassigned"} and fallback $FALLBACK_FIRST..$FALLBACK_LAST all held — not starting (last error: $lastBindError)")
            running.set(false)
            return
        }
        server = sock
        port = sock.localPort
        Log.i(TAG, "listening on 127.0.0.1:$port (${app.packageName})")
        thread = Thread({ runServer(app, sock) }, "AppDebug-$port").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        thread?.interrupt()
        server = null
        thread = null
        port = -1
    }

    /** #762 why the last bind failed — "EACCES" means no INTERNET permission
     *  (devtools declares it; an app manifest that removes it lands here), not
     *  busy ports. */
    @Volatile private var lastBindError: String? = null

    /** #792 why this member is NOT on its assigned port, in words; null when it
     *  is. Served in /api/system/info so Apps Mesh can say it, not just log it. */
    @Volatile var bindNote: String? = null
        private set

    private fun bindOwn(pkg: String): ServerSocket? {
        val loopback = InetAddress.getByName("127.0.0.1")
        fun tryBind(p: Int) = runCatching { ServerSocket(p, 4, loopback) }
            .onFailure { lastBindError = it.toString() }.getOrNull()
        val own = portOf(pkg)
        var ownError: String? = null
        if (own != null) {
            tryBind(own)?.let { bindNote = null; return it }
            ownError = lastBindError
            Log.w(TAG, "assigned port $own for $pkg is held by another process ($ownError) — falling back to an unowned port")
        } else {
            Log.w(TAG, "$pkg has no port in debug-ports.json — falling back to an unowned port")
        }
        for (p in fallbackPorts(FALLBACK_FIRST, FALLBACK_LAST, PORTS.values)) {
            val s = tryBind(p) ?: continue
            bindNote = if (own == null) "no assigned port for $pkg in debug-ports.json; took unowned :$p"
                else "assigned :$own was held ($ownError); took unowned :$p"
            return s
        }
        return null
    }

    private fun runServer(ctx: Context, sock: ServerSocket) {
        try {
            while (running.get()) {
                val s = sock.accept()
                runCatching { handle(ctx, s) }.onFailure { Log.w(TAG, "handle: $it") }
            }
        } catch (t: Throwable) {
            if (running.get()) Log.w(TAG, "server died: $t")
        } finally {
            runCatching { sock.close() }
            running.set(false)
            port = -1
        }
    }

    private fun handle(ctx: Context, s: Socket) {
        s.soTimeout = SOCKET_TIMEOUT_MS
        s.use { sock ->
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            val writer = PrintWriter(sock.getOutputStream())
            val line = reader.readLine() ?: return
            val parts = line.split(' ')
            if (parts.size < 2) {
                reply(writer, "400 Bad Request", "bad request line\n")
                return
            }
            val rawPath = parts[1]
            val qIdx = rawPath.indexOf('?')
            val path = if (qIdx < 0) rawPath else rawPath.substring(0, qIdx)
            val query =
                if (qIdx < 0) emptyMap<String, String>()
                else parseQuery(rawPath.substring(qIdx + 1))

            // Drain headers so the client sees a clean response rather than a
            // reset while it is still writing, picking the credential up on the
            // way past.
            var bearer: String? = null
            var length: Int? = null
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isEmpty()) break
                bearerOf(h)?.let { bearer = it }
                contentLengthOf(h)?.let { length = it }
            }
            // #802 a write carries its payload in the body (a profile import is
            // kilobytes, past any sane query string). Refused before reading,
            // so an oversize claim never ties up the one accept thread.
            if ((length ?: 0) > MAX_BODY_BYTES) {
                reply(writer, "413 Payload Too Large", "body over $MAX_BODY_BYTES bytes\n")
                return
            }
            val body = readBody(reader, length ?: 0)

            val op = canonicalOp(path)
            // Everything but liveness is fleet-only. The loopback bind stopped
            // being a sufficient boundary the moment app data started riding
            // these routes next to the logs: loopback is device-wide on Android,
            // so any installed app with INTERNET could otherwise read all of it.
            if (op !in OPEN_OPS && !FleetToken.matches(ctx, bearer)) {
                reply(writer, "401 Unauthorized", "unauthorized — Bearer <fleet token>\n")
                return
            }

            when (op) {
                "docs" -> reply(writer, "200 OK", docsJson(ctx), "application/json")
                "system/ping" -> reply(writer, "200 OK", "pong ${ctx.packageName}\n")
                "system/info" -> reply(writer, "200 OK", infoJson(ctx), "application/json")
                "diagnostics/logcat" -> {
                    val n = (query["n"]?.toIntOrNull() ?: DEFAULT_LINES).coerceIn(1, MAX_LINES)
                    reply(writer, "200 OK", readLogcat(n))
                }
                "diagnostics/crashes" -> reply(writer, "200 OK", readCrashes(ctx))
                "fleet/peers" -> reply(writer, "200 OK", peersJson(ctx), "application/json")
                "net/dns" -> reply(writer, "200 OK", "{${dnsFields(ctx)}}", "application/json")
                "net/resolve" -> reply(writer, "200 OK", resolveJson(ctx, query["host"].orEmpty()), "application/json")
                "fleet/wake" -> {
                    val pkg = query["pkg"].orEmpty()
                    if (pkg.isBlank()) {
                        reply(writer, "400 Bad Request", "need ?pkg=<applicationId>\n")
                    } else {
                        val ok = FleetPeers.wake(ctx, pkg)
                        reply(
                            writer,
                            if (ok) "200 OK" else "502 Bad Gateway",
                            """{"pkg":"${esc(pkg)}","woken":$ok}""",
                            "application/json",
                        )
                    }
                }
                else -> {
                    val out = appRoute(op, withBody(query, body))
                    if (out != null) reply(writer, "200 OK", out, "application/json")
                    else reply(writer, "404 Not Found", "not found — see /api/docs\n")
                }
            }
        }
    }

    /** #802 a request may carry at most this many body bytes; past it, 413. */
    internal const val MAX_BODY_BYTES = 256 * 1024

    /** `Content-Length: n` → n; null for any other header or a value that is not a count. */
    internal fun contentLengthOf(header: String): Int? {
        if (!header.startsWith("Content-Length:", ignoreCase = true)) return null
        return header.substringAfter(':').trim().toIntOrNull()?.takeIf { it >= 0 }
    }

    /** Exactly [bytes] UTF-8 bytes off [reader] (which already holds what followed
     *  the headers), or null when there is no body. Counted in bytes, not chars:
     *  Content-Length is bytes, and reading that many chars would wait on a
     *  non-ASCII body until the socket timed out. */
    internal fun readBody(reader: java.io.Reader, bytes: Int): String? {
        if (bytes <= 0) return null
        val sb = StringBuilder()
        var n = 0
        while (n < bytes) {
            val c = reader.read()
            if (c < 0) break
            sb.append(c.toChar())
            n += when {
                c < 0x80 -> 1
                c < 0x800 || Character.isSurrogate(c.toChar()) -> 2
                else -> 3
            }
        }
        return sb.toString()
    }

    /** The query a route sees: `_body` only ever comes from the body, never the
     *  query string, so a handler can trust where it came from. */
    internal fun withBody(query: Map<String, String>, body: String?): Map<String, String> =
        if (body == null) query - "_body" else query - "_body" + ("_body" to body)

    /** Accepts /api/{group}/{op} and the short /{op} aliases, matching the
     *  path styles the app-owned DevControlServer already documents. */
    private fun canonicalOp(path: String): String {
        val stripped = path.removePrefix("/api/").removePrefix("/")
        return when (stripped) {
            "ping" -> "system/ping"
            "info" -> "system/info"
            "logcat" -> "diagnostics/logcat"
            "crashes" -> "diagnostics/crashes"
            "peers" -> "fleet/peers"
            "wake" -> "fleet/wake"
            else -> stripped
        }
    }

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
            append(""""port":$port,""")
            append(""""assignedPort":${portOf(pkg) ?: -1},""")
            append(""""bindNote":${jsonStr(bindNote)},""")
            append(""""device":"${esc("${Build.MANUFACTURER} ${Build.MODEL}")}",""")
            append(""""android":"${esc(Build.VERSION.RELEASE)}",""")
            append(""""sdk":${Build.VERSION.SDK_INT}""")
            append("}")
        }
    }

    /** This process's own /api/docs body, without a socket — for a caller on
     *  the server's own accept thread, where a loopback GET would wait on itself. */
    fun docs(ctx: Context): String = docsJson(ctx)

    private fun docsJson(ctx: Context): String = buildString {
        append("{")
        append(""""applicationId":"${esc(ctx.packageName)}",""")
        append(""""port":$port,""")
        append(""""base":"http://127.0.0.1:$port",""")
        append(""""auth":"Bearer <fleet token> — one token for the whole fleet, """)
        append("""shown in SuperApp under Configs → About. /api/system/ping is open.",""")
        append(""""scan":"each package has a fixed port (libs:devtools debug-ports.json); a member whose """)
        append("""port was held falls back into $FALLBACK_FIRST..$FALLBACK_LAST, where /api/system/ping answers """)
        append("""'pong <applicationId>' unauthenticated",""")
        append(""""body":"a POST body (Content-Length, max $MAX_BODY_BYTES bytes, else 413) reaches an app route """)
        append("""as query param _body; _body in the query string is dropped",""")
        append(""""endpoints":[""")
        append("""{"path":"/api/docs","description":"this catalog"},""")
        append("""{"path":"/api/system/ping","description":"liveness + applicationId, """)
        append("""no token needed — map a port to its app"},""")
        append("""{"path":"/api/system/info","description":"applicationId, label, version, bound port, device"},""")
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
        return dnsFields(
            network = if (net == null) null else if (vpn) "vpn" else "direct",
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

    private fun reply(
        w: PrintWriter,
        status: String,
        body: String,
        contentType: String = "text/plain",
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        w.print("HTTP/1.1 $status\r\n")
        w.print("Content-Type: $contentType; charset=utf-8\r\n")
        w.print("Content-Length: ${bytes.size}\r\n")
        w.print("Connection: close\r\n\r\n")
        w.print(body)
        w.flush()
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        return raw.split('&').mapNotNull {
            val eq = it.indexOf('=')
            if (eq < 0) return@mapNotNull null
            val k = URLDecoder.decode(it.substring(0, eq), "UTF-8")
            val v = URLDecoder.decode(it.substring(eq + 1), "UTF-8")
            k to v
        }.toMap()
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
