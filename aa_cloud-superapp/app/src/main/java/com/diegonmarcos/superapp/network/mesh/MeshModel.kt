package com.diegonmarcos.superapp.network.mesh

import java.util.Locale
import java.util.TimeZone

/*
 * #877 The Cloud Mesh page's PURE model: what one poll of the engine means. Nothing here touches
 * Android, the engine or a clock - a [Sample] and a [ConfigView] go in, a [MeshSnapshot] and the
 * journal lines it implies come out - so every rule the page shows (rates, handshake age, the peer
 * light, the route table, the profile diff) is asserted on the JVM in MeshModelTest.
 */

/** What the readout formats, shared so a count never reads two ways on two tabs. */
object MeshFormat {
    private val UNITS = arrayOf("B", "KiB", "MiB", "GiB", "TiB")

    /** 0 -> "0 B", 1536 -> "1.5 KiB", 5 MiB -> "5.00 MiB". */
    fun bytes(n: Long): String {
        if (n < 1024) return "${n.coerceAtLeast(0)} B"
        var v = n.toDouble()
        var u = 0
        while (v >= 1024 && u < UNITS.size - 1) { v /= 1024; u++ }
        return String.format(Locale.ROOT, if (v < 10) "%.2f %s" else "%.1f %s", v, UNITS[u])
    }

    /** Bytes per second; anything under one byte a second reads "0 B/s". */
    fun rate(bytesPerSecond: Double): String =
        if (bytesPerSecond < 1.0) "0 B/s" else bytes(bytesPerSecond.toLong()) + "/s"

    /** Seconds as the shortest honest span: 12s, 3m 05s, 2h 10m, 3d 04h; null is "-". */
    fun age(seconds: Long?): String = when {
        seconds == null -> "-"
        seconds < 60 -> "${seconds.coerceAtLeast(0)}s"
        seconds < 3600 -> String.format(Locale.ROOT, "%dm %02ds", seconds / 60, seconds % 60)
        seconds < 86_400 -> String.format(Locale.ROOT, "%dh %02dm", seconds / 3600, seconds % 3600 / 60)
        else -> String.format(Locale.ROOT, "%dd %02dh", seconds / 86_400, seconds % 86_400 / 3600)
    }

    fun latency(ms: Int?): String = if (ms == null) "-" else "$ms ms"

    /** HH:mm:ss in [tz] (the journal's stamp). */
    fun clock(epochMs: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val s = Math.floorMod((epochMs + tz.getOffset(epochMs)) / 1000, 86_400L)
        return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
    }

    /** A base64 key as `abcd1234...wxyz=`; short strings are returned whole. */
    fun shortKey(k: String): String = if (k.length <= 16) k else k.take(8) + "..." + k.takeLast(5)

    /** "a, b  c" -> [a, b, c]. */
    fun tokens(csv: String): List<String> = csv.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }
}

// ── configuration as the page sees it ─────────────────────────────────────

data class PeerCfg(
    val name: String,
    val publicKey: String,
    val presharedKey: String,
    val endpoint: String,
    val allowedIps: String,
    val keepalive: String,
)

data class ConfigView(
    val tunnelName: String,
    val address: String,
    /** The interface public key derived from the stored private one; "" with no key. */
    val publicKey: String,
    val dns: String,
    val mtu: String,
    val listenPort: String,
    val activeProfile: String,
    /** What Android resolves with now, as FleetDns reports it ("" until read). */
    val dnsInEffect: String,
    val peers: List<PeerCfg>,
)

// ── one poll ──────────────────────────────────────────────────────────────

data class PeerCounters(val publicKey: String, val rx: Long, val tx: Long, val handshakeEpochMs: Long)

/** Round-trip probes: [hubMs] to the mesh resolver (the hub), [peerMs] per peer public key. null = no answer. */
data class LatencyReading(val atMs: Long, val hubMs: Int?, val peerMs: Map<String, Int?>)

data class Sample(
    val atMs: Long,
    val engineInstalled: Boolean,
    val up: Boolean,
    val peers: List<PeerCounters>,
    /** null = not probed this tick; the last reading is carried. */
    val latency: LatencyReading? = null,
)

/** The handshake bounds of the peer light ([MeshDecl.Decl.handshakeFreshS] / `handshakeStaleS`). */
data class Thresholds(val freshS: Long, val staleS: Long)

enum class Health { FRESH, AGING, STALE, NONE, DOWN, UNKNOWN }

enum class Light { UP, WARN, DOWN, UNKNOWN }

data class PeerLive(
    val cfg: PeerCfg,
    val rx: Long,
    val tx: Long,
    val rxRate: Double,
    val txRate: Double,
    val handshakeAgeS: Long?,
    val health: Health,
    val latencyMs: Int?,
    /** The engine's own handshake stamp (epoch ms; 0 = none yet), kept so a NEW handshake is a comparison, not a guess. */
    val handshakeEpochMs: Long = 0L,
)

data class MeshSnapshot(
    val atMs: Long,
    val engineInstalled: Boolean,
    val up: Boolean,
    val light: Light,
    val cfg: ConfigView,
    val peers: List<PeerLive>,
    val rx: Long,
    val tx: Long,
    val rxRate: Double,
    val txRate: Double,
    val newestHandshakeAgeS: Long?,
    val hubLatencyMs: Int?,
    val latencyAtMs: Long,
)

data class LogEvent(val atMs: Long, val kind: String, val text: String)

object MeshReducer {

    /** Smaller gaps than this between two polls give a rate dominated by timer jitter: read as 0. */
    const val MIN_DT_MS = 200L

    /** Bytes per second between two counter reads; a counter that went DOWN (tunnel restarted) is 0, never negative. */
    fun rate(prev: Long?, cur: Long, dtMs: Long): Double =
        if (prev == null || dtMs < MIN_DT_MS || cur < prev) 0.0 else (cur - prev) * 1000.0 / dtMs

    /** Whole seconds since [handshakeEpochMs]; null when there has been none (0) or the clock is behind it. */
    fun handshakeAgeS(nowMs: Long, handshakeEpochMs: Long): Long? =
        if (handshakeEpochMs <= 0L) null else ((nowMs - handshakeEpochMs) / 1000).coerceAtLeast(0)

    fun health(engine: Boolean, up: Boolean, ageS: Long?, th: Thresholds): Health = when {
        !engine -> Health.UNKNOWN
        !up -> Health.DOWN
        ageS == null -> Health.NONE
        ageS <= th.freshS -> Health.FRESH
        ageS <= th.staleS -> Health.AGING
        else -> Health.STALE
    }

    fun light(engine: Boolean, up: Boolean, peers: List<PeerLive>): Light = when {
        !engine -> Light.UNKNOWN
        !up -> Light.DOWN
        peers.any { it.health == Health.FRESH || it.health == Health.AGING } -> Light.UP
        else -> Light.WARN
    }

    fun reduce(prev: MeshSnapshot?, cfg: ConfigView, s: Sample, th: Thresholds): Pair<MeshSnapshot, List<LogEvent>> {
        val dt = if (prev == null) 0L else s.atMs - prev.atMs
        val counters = s.peers.associateBy { it.publicKey }
        val lat = s.latency
        val live = cfg.peers.map { p ->
            val c = if (s.up) counters[p.publicKey] else null
            val before = prev?.peers?.firstOrNull { it.cfg.publicKey == p.publicKey }
            val age = if (c == null) null else handshakeAgeS(s.atMs, c.handshakeEpochMs)
            PeerLive(
                cfg = p,
                rx = c?.rx ?: 0L,
                tx = c?.tx ?: 0L,
                rxRate = if (c == null) 0.0 else rate(before?.rx.takeIf { prev?.up == true }, c.rx, dt),
                txRate = if (c == null) 0.0 else rate(before?.tx.takeIf { prev?.up == true }, c.tx, dt),
                handshakeAgeS = age,
                health = health(s.engineInstalled, s.up, age, th),
                handshakeEpochMs = c?.handshakeEpochMs ?: 0L,
                latencyMs = when {
                    !s.up -> null
                    lat != null -> lat.peerMs[p.publicKey]
                    else -> before?.latencyMs
                },
            )
        }
        val snap = MeshSnapshot(
            atMs = s.atMs,
            engineInstalled = s.engineInstalled,
            up = s.up,
            light = light(s.engineInstalled, s.up, live),
            cfg = cfg,
            peers = live,
            rx = live.sumOf { it.rx },
            tx = live.sumOf { it.tx },
            rxRate = live.sumOf { it.rxRate },
            txRate = live.sumOf { it.txRate },
            newestHandshakeAgeS = live.mapNotNull { it.handshakeAgeS }.minOrNull(),
            hubLatencyMs = when {
                !s.up -> null
                lat != null -> lat.hubMs
                else -> prev?.hubLatencyMs
            },
            latencyAtMs = lat?.atMs ?: prev?.latencyAtMs ?: 0L,
        )
        return snap to events(prev, snap)
    }

    /** The journal lines one poll implies: engine presence, tunnel state, handshakes, a peer going stale. */
    fun events(prev: MeshSnapshot?, now: MeshSnapshot): List<LogEvent> {
        val out = mutableListOf<LogEvent>()
        fun add(kind: String, text: String) { out += LogEvent(now.atMs, kind, text) }
        if (prev == null) {
            add("observe", when {
                !now.engineInstalled -> "engine not installed - state unknown"
                now.up -> "tunnel ${now.cfg.tunnelName} is up"
                else -> "tunnel ${now.cfg.tunnelName} is down"
            })
        } else {
            if (prev.engineInstalled != now.engineInstalled) {
                add("engine", if (now.engineInstalled) "engine installed" else "engine not installed - state unknown")
            }
            if (now.engineInstalled && prev.engineInstalled && prev.up != now.up) {
                add("state", if (now.up) "tunnel ${now.cfg.tunnelName} UP" else "tunnel ${now.cfg.tunnelName} DOWN")
            }
        }
        for (p in now.peers) {
            val before = prev?.peers?.firstOrNull { it.cfg.publicKey == p.cfg.publicKey }
            val label = p.cfg.name.ifBlank { MeshFormat.shortKey(p.cfg.publicKey) }
            val hsBefore = before?.handshakeEpochMs ?: 0L
            if (now.up && p.handshakeEpochMs > hsBefore) add("handshake", when {
                before == null && prev == null -> "last handshake with $label ${MeshFormat.age(p.handshakeAgeS)} ago"
                hsBefore == 0L && before != null -> "first handshake with $label"
                else -> "handshake with $label"
            })
            if (before != null && p.health == Health.STALE &&
                (before.health == Health.FRESH || before.health == Health.AGING)) {
                add("stale", "$label stale: no handshake for ${MeshFormat.age(p.handshakeAgeS)}")
            }
        }
        return out
    }
}

/** The journal: newest last, capped; the page prints it, copies it, clears it. */
class MeshJournal(private val cap: Int) {
    private val lines = ArrayDeque<LogEvent>()

    @Synchronized fun add(e: LogEvent) { lines.addLast(e); while (lines.size > cap) lines.removeFirst() }
    @Synchronized fun addAll(es: List<LogEvent>) { es.forEach(::add) }
    @Synchronized fun clear() = lines.clear()
    @Synchronized fun list(): List<LogEvent> = lines.toList()
    fun text(tz: TimeZone = TimeZone.getDefault()): String =
        list().joinToString("\n") { "${MeshFormat.clock(it.atMs, tz)} ${it.kind.padEnd(9)} ${it.text}" }
}

// ── routes ────────────────────────────────────────────────────────────────

enum class RouteKind { DEFAULT, NAT64, HOST, SUBNET }
enum class RouteMode { NONE, SPLIT, FULL }

data class RouteRow(val peer: String, val cidr: String, val family: Int, val kind: RouteKind)

data class RouteTable(
    val rows: List<RouteRow>,
    val mode: RouteMode,
    /** The share of the IPv4 space the allowed IPs cover, 0..1. */
    val v4Coverage: Double,
    /** The NAT64 prefixes among the allowed IPs (see `nat64_marker`). */
    val nat64: List<String>,
)

object Routes {

    private fun v4Range(cidr: String): LongRange? {
        val (addr, len) = cidr.split('/').let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 32) }
        val o = addr.split('.')
        if (o.size != 4 || len !in 0..32) return null
        val ip = o.fold(0L) { acc, part -> (acc shl 8) or (part.toLongOrNull()?.takeIf { it in 0..255 } ?: return null) }
        val size = 1L shl (32 - len)
        val start = ip and (size - 1).inv()
        return start until start + size
    }

    /** The share of IPv4 the [cidrs] cover (a union, so overlapping entries never count twice). */
    fun v4Coverage(cidrs: List<String>): Double {
        val ranges = cidrs.mapNotNull { if (it.contains(':')) null else v4Range(it) }.sortedBy { it.first }
        var covered = 0L
        var end = -1L
        for (r in ranges) {
            val from = maxOf(r.first, end + 1)
            if (r.last >= from) covered += r.last - from + 1
            end = maxOf(end, r.last)
        }
        return covered.toDouble() / (1L shl 32)
    }

    private fun kindOf(cidr: String, family: Int, marker: String): RouteKind {
        val len = cidr.substringAfter('/', if (family == 6) "128" else "32").toIntOrNull() ?: if (family == 6) 128 else 32
        return when {
            len == 0 -> RouteKind.DEFAULT
            family == 6 && len == 96 && marker.isNotBlank() &&
                cidr.substringBefore('/').split(':').any { it.equals(marker, ignoreCase = true) } -> RouteKind.NAT64
            len == (if (family == 6) 128 else 32) -> RouteKind.HOST
            else -> RouteKind.SUBNET
        }
    }

    fun derive(peers: List<PeerCfg>, nat64Marker: String): RouteTable {
        val rows = peers.flatMap { p ->
            MeshFormat.tokens(p.allowedIps).map { c ->
                val fam = if (c.contains(':')) 6 else 4
                RouteRow(p.name.ifBlank { MeshFormat.shortKey(p.publicKey) }, c, fam, kindOf(c, fam, nat64Marker))
            }
        }
        val cov = v4Coverage(rows.filter { it.family == 4 }.map { it.cidr })
        val v6Default = rows.any { it.family == 6 && it.kind == RouteKind.DEFAULT }
        val mode = when {
            rows.isEmpty() -> RouteMode.NONE
            v6Default || cov >= 0.75 -> RouteMode.FULL
            else -> RouteMode.SPLIT
        }
        return RouteTable(rows, mode, cov, rows.filter { it.kind == RouteKind.NAT64 }.map { it.cidr }.distinct())
    }

    fun filter(rows: List<RouteRow>, f: String): List<RouteRow> = when (f) {
        "default" -> rows.filter { it.kind == RouteKind.DEFAULT }
        "subnet" -> rows.filter { it.kind == RouteKind.SUBNET }
        "host" -> rows.filter { it.kind == RouteKind.HOST }
        "nat64" -> rows.filter { it.kind == RouteKind.NAT64 }
        else -> rows
    }
}

// ── peers: ordering ───────────────────────────────────────────────────────

object PeerOrder {
    /** "config" keeps the stored order; "handshake" the freshest first (none last); "traffic" the busiest first. */
    fun sort(peers: List<PeerLive>, mode: String): List<PeerLive> = when (mode) {
        "handshake" -> peers.sortedBy { it.handshakeAgeS ?: Long.MAX_VALUE }
        "traffic" -> peers.sortedByDescending { it.rx + it.tx }
        else -> peers
    }
}

// ── profiles: wg-quick text and the diff against the declaration ──────────

/** Just enough wg-quick to compare two profiles: the [Interface] fields and each [Peer]'s. Never a key. */
object WgText {
    data class Parsed(val iface: Map<String, String>, val peers: List<Map<String, String>>)

    fun parse(text: String): Parsed {
        val iface = linkedMapOf<String, String>()
        val peers = mutableListOf<MutableMap<String, String>>()
        var cur: MutableMap<String, String>? = null
        for (raw in text.lines()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            when {
                line.equals("[Interface]", true) -> cur = iface
                line.equals("[Peer]", true) -> { cur = linkedMapOf(); peers += cur }
                else -> {
                    val k = line.substringBefore('=', "").trim()
                    if (k.isNotEmpty() && !k.equals("PrivateKey", true)) cur?.put(k.lowercase(Locale.ROOT), line.substringAfter('=').trim())
                }
            }
        }
        return Parsed(iface, peers)
    }
}

object ProfileDiff {
    enum class State { SAME, DIFFERENT, MISSING, EXTRA }

    data class Line(val field: String, val declared: String, val stored: String, val state: State)

    /** An ordered list the way WireGuard reads it: order matters for Address (the first IPv4 is the source). */
    private fun norm(csv: String?) = MeshFormat.tokens(csv.orEmpty()).joinToString(", ")

    /** The same, order-free: AllowedIPs is a set. */
    private fun set(csv: String?) = MeshFormat.tokens(csv.orEmpty()).sorted().joinToString(", ")

    /**
     * [declared] is the profile's key-free wg-quick text (WireGuardProfiles.render), [stored] the text this
     * phone holds for it, or null when it holds none. A phone with no copy answers one MISSING line.
     */
    fun diff(declared: String, stored: String?): List<Line> {
        val d = WgText.parse(declared)
        if (stored == null) return listOf(Line("profile", "declared", "", State.MISSING))
        val s = WgText.parse(stored)
        val out = mutableListOf<Line>()
        fun cmp(field: String, a: String, b: String) =
            out.add(Line(field, a, b, if (a == b) State.SAME else State.DIFFERENT))
        cmp("Address", norm(d.iface["address"]), norm(s.iface["address"]))
        cmp("DNS", norm(d.iface["dns"]), norm(s.iface["dns"]))
        cmp("MTU", d.iface["mtu"].orEmpty(), s.iface["mtu"].orEmpty())
        val sBy = s.peers.associateBy { it["publickey"].orEmpty() }
        for (p in d.peers) {
            val key = p["publickey"].orEmpty()
            val tag = MeshFormat.shortKey(key)
            val q = sBy[key]
            if (q == null) { out += Line("Peer $tag", key, "", State.MISSING); continue }
            cmp("Peer $tag Endpoint", p["endpoint"].orEmpty(), q["endpoint"].orEmpty())
            cmp("Peer $tag AllowedIPs", set(p["allowedips"]), set(q["allowedips"]))
            cmp("Peer $tag Keepalive", p["persistentkeepalive"].orEmpty(), q["persistentkeepalive"].orEmpty())
        }
        val dKeys = d.peers.map { it["publickey"].orEmpty() }.toSet()
        for (q in s.peers) {
            val key = q["publickey"].orEmpty()
            if (key !in dKeys) out += Line("Peer ${MeshFormat.shortKey(key)}", "", key, State.EXTRA)
        }
        return out
    }

    fun clean(lines: List<Line>): Boolean = lines.isNotEmpty() && lines.all { it.state == State.SAME }
}

// ── latency targets ───────────────────────────────────────────────────────

/**
 * Who each probe asks. A peer is the hub of its own mesh and its mesh resolver is the one address that
 * is certain to answer there, so a peer's latency is the round trip to the fleet resolver its AllowedIPs
 * cover (10.0.0.1 for the mesh whose subnet is 10.0.0.0/24), and the hub's is the first resolver.
 */
object LatencyTargets {

    /** True when IPv4 [ip] lies inside IPv4 [cidr]. */
    fun covers(cidr: String, ip: String): Boolean {
        if (cidr.contains(':') || ip.contains(':')) return false
        fun v(a: String): Long? = a.split('.').takeIf { it.size == 4 }?.fold(0L) { acc, x -> (acc shl 8) or (x.toLongOrNull()?.takeIf { it in 0..255 } ?: return null) }
        val len = cidr.substringAfter('/', "32").toIntOrNull()?.takeIf { it in 0..32 } ?: return false
        val net = v(cidr.substringBefore('/')) ?: return false
        val addr = v(ip) ?: return false
        val mask = if (len == 0) 0L else (0xFFFFFFFFL shl (32 - len)) and 0xFFFFFFFFL
        return (net and mask) == (addr and mask)
    }

    /** Public key -> the resolver address to probe for that peer (absent when none of its routes holds one). */
    fun forPeers(peers: List<PeerCfg>, resolvers: List<String>): Map<String, String> =
        peers.mapNotNull { p ->
            val cidrs = MeshFormat.tokens(p.allowedIps)
            resolvers.firstOrNull { r -> cidrs.any { covers(it, r) } }?.let { p.publicKey to it }
        }.toMap()
}

// ── text a copy button hands to the clipboard ─────────────────────────────

object MeshText {
    /** One peer as a wg-quick [Peer] block. The pre-shared key is a secret and is never written out. */
    fun peerBlock(p: PeerCfg): String = buildString {
        appendLine("[Peer]")
        if (p.name.isNotBlank()) appendLine("# ${p.name}")
        appendLine("PublicKey = ${p.publicKey}")
        if (p.presharedKey.isNotBlank()) appendLine("# PresharedKey withheld - it is a secret and stays on this device")
        if (p.endpoint.isNotBlank()) appendLine("Endpoint = ${p.endpoint}")
        appendLine("AllowedIPs = ${p.allowedIps}")
        if (p.keepalive.isNotBlank()) appendLine("PersistentKeepalive = ${p.keepalive}")
    }.trimEnd()
}
