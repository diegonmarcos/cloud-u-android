package com.diegonmarcos.superapp.wifi

import kotlin.math.abs
import kotlin.math.sqrt

/** One ping reply or timeout (#1263). */
data class PingReply(val seq: Int, val ttl: Int, val ms: Double?, val from: String)

/** Latency summary: loss, min / avg / max, jitter (mean absolute difference of consecutive replies, RFC 3550 style), stddev. */
data class PingStats(val sent: Int, val received: Int, val lossPct: Double, val min: Double, val avg: Double, val max: Double, val jitter: Double, val stddev: Double) {
    companion object {
        fun of(sent: Int, rtts: List<Double>): PingStats {
            if (rtts.isEmpty()) return PingStats(sent, 0, if (sent > 0) 100.0 else 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            val avg = rtts.average()
            val jitter = if (rtts.size < 2) 0.0 else rtts.zipWithNext { a, b -> abs(b - a) }.average()
            val sd = sqrt(rtts.sumOf { (it - avg) * (it - avg) } / rtts.size)
            val loss = if (sent <= 0) 0.0 else ((sent - rtts.size).coerceAtLeast(0) * 1000.0 / sent).toInt() / 10.0
            return PingStats(sent, rtts.size, loss, rtts.min(), avg, rtts.max(), jitter, sd)
        }
    }
}

/** A traceroute hop from `ping -t <ttl>`: the router that answered "Time to live exceeded", or the target's own reply. */
data class Hop(val ttl: Int, val ip: String?, val ms: Double?, val reached: Boolean, val name: String = "")

/** Parsers for toybox / iputils `ping` output (the only ICMP an unprivileged Android app may use). Pure: JVM-tested. */
object PingParser {
    private val REPLY = Regex("""bytes from ([^\s:]+)(?: \(([^)]+)\))?:? .*icmp_seq=(\d+).*ttl=(\d+).*time=([\d.]+) ?ms""")
    private val EXCEEDED = Regex("""From ([^\s:]+)(?: \(([^)]+)\))?:? .*icmp_seq=(\d+).*(Time to live exceeded|TTL expired)""", RegexOption.IGNORE_CASE)
    private val SUMMARY = Regex("""(\d+) packets transmitted, (\d+) (?:packets )?received""")

    fun reply(line: String): PingReply? = REPLY.find(line)?.let { m ->
        PingReply(m.groupValues[3].toInt(), m.groupValues[4].toInt(), m.groupValues[5].toDouble(), m.groupValues[2].ifEmpty { m.groupValues[1] })
    }

    /** The router that dropped a TTL-limited probe, or null. */
    fun exceeded(line: String): String? = EXCEEDED.find(line)?.let { it.groupValues[2].ifEmpty { it.groupValues[1] } }

    fun summary(text: String): Pair<Int, Int>? = SUMMARY.find(text)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

    /** A whole `ping` run to stats: replies are the RTTs; transmitted comes from the summary, else from the highest sequence. */
    fun stats(text: String): PingStats {
        val replies = text.lineSequence().mapNotNull { reply(it) }.toList()
        val sent = summary(text)?.first ?: (replies.maxOfOrNull { it.seq } ?: 0)
        return PingStats.of(sent, replies.mapNotNull { it.ms })
    }

    /** One hop of a TTL-limited probe's output. [wallMs] is used when the router's reply carries no time (it never does). */
    fun hop(ttl: Int, text: String, wallMs: Double?): Hop {
        text.lineSequence().forEach { l ->
            reply(l)?.let { return Hop(ttl, it.from, it.ms, true) }
            exceeded(l)?.let { return Hop(ttl, it, wallMs, false) }
        }
        return Hop(ttl, null, null, false)
    }

    /** Hostnames and IP literals only: the argument goes to a process, so nothing else is accepted. */
    fun safeHost(h: String): Boolean = h.length in 1..253 && h.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == ':' || it == '_' } && !h.startsWith("-")
}

/** IPv4 helpers for the sweep (#1263). Pure: JVM-tested. */
object Ipv4 {
    fun toInt(ip: String): Int? {
        val p = ip.trim().split('.'); if (p.size != 4) return null
        var v = 0
        for (s in p) { val n = s.toIntOrNull() ?: return null; if (n !in 0..255) return null; v = (v shl 8) or n }
        return v
    }

    fun fromInt(v: Int): String = "${v ushr 24 and 255}.${v ushr 16 and 255}.${v ushr 8 and 255}.${v and 255}"

    /** DhcpInfo / WifiManager hand IPv4 as a little-endian int. */
    fun fromLittleEndian(v: Int): String = "${v and 255}.${v ushr 8 and 255}.${v ushr 16 and 255}.${v ushr 24 and 255}"

    fun prefixToMask(prefix: Int): String = fromInt(if (prefix <= 0) 0 else (-1 shl (32 - prefix.coerceAtMost(32))))

    /**
     * The hosts to sweep around [ip]: its subnet, but never more than a /24 (254 hosts) — a /16 would be 65k probes. Network and
     * broadcast addresses and [ip] itself are left out.
     */
    fun sweepHosts(ip: String, prefix: Int): List<String> {
        val a = toInt(ip) ?: return emptyList()
        val p = prefix.coerceIn(24, 30)
        val mask = -1 shl (32 - p)
        val net = a and mask
        val size = 1 shl (32 - p)
        return (1 until size - 1).map { net + it }.filter { it != a }.map { fromInt(it) }
    }

    fun isPrivate(ip: String): Boolean {
        val a = toInt(ip) ?: return false
        val b0 = a ushr 24 and 255; val b1 = a ushr 16 and 255
        return b0 == 10 || (b0 == 172 && b1 in 16..31) || (b0 == 192 && b1 == 168) || (b0 == 100 && b1 in 64..127) || (b0 == 169 && b1 == 254)
    }
}

/** `22,80,8000-8010` to a sorted port list, at most [max] ports; null on a malformed spec. Pure: JVM-tested. */
object PortSpec {
    fun parse(spec: String, max: Int = 1024): List<Int>? {
        val out = sortedSetOf<Int>()
        for (part in spec.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }) {
            val r = part.split('-')
            val lo = r[0].toIntOrNull() ?: return null
            val hi = if (r.size == 2) r[1].toIntOrNull() ?: return null else if (r.size == 1) lo else return null
            if (lo !in 1..65535 || hi !in 1..65535 || hi < lo) return null
            for (p in lo..hi) { out += p; if (out.size > max) return null }
        }
        return out.toList().ifEmpty { null }
    }
}

/** SSDP (UPnP discovery) request and response. Pure: JVM-tested. */
object Ssdp {
    const val ADDR = "239.255.255.250"
    const val PORT = 1900

    fun mSearch(st: String = "ssdp:all", mx: Int = 2): String =
        "M-SEARCH * HTTP/1.1\r\nHOST: $ADDR:$PORT\r\nMAN: \"ssdp:discover\"\r\nMX: $mx\r\nST: $st\r\nUSER-AGENT: Android UPnP/1.1 CloudWifi/1.0\r\n\r\n"

    /** Header name (upper case) to value of a response; empty when it is not an HTTP 200 / NOTIFY. */
    fun headers(resp: String): Map<String, String> {
        val lines = resp.split("\r\n", "\n")
        if (lines.isEmpty() || !(lines[0].startsWith("HTTP/1.1 200") || lines[0].startsWith("NOTIFY"))) return emptyMap()
        return lines.drop(1).mapNotNull { l -> val i = l.indexOf(':'); if (i <= 0) null else l.substring(0, i).trim().uppercase() to l.substring(i + 1).trim() }.toMap()
    }
}

/** NetBIOS node status (NBSTAT, RFC 1002 4.2.17) for a host's Windows / Samba name. Pure: JVM-tested. */
object NetBios {
    fun nodeStatusRequest(id: Int = 0x1263): ByteArray {
        val name = ByteArray(32)
        // "*" padded with NULs, first-level encoded: each nibble + 'A'.
        val raw = ByteArray(16).also { it[0] = '*'.code.toByte() }
        for (i in 0 until 16) { val b = raw[i].toInt() and 0xff; name[2 * i] = ('A'.code + (b shr 4)).toByte(); name[2 * i + 1] = ('A'.code + (b and 15)).toByte() }
        val h = byteArrayOf((id shr 8).toByte(), id.toByte(), 0, 0, 0, 1, 0, 0, 0, 0, 0, 0)
        return h + byteArrayOf(32) + name + byteArrayOf(0, 0, 0x21, 0, 1)
    }

    /** The unique workstation name (suffix 0x00, not a group) from a node status response; null when there is none. */
    fun parseName(resp: ByteArray): String? {
        if (resp.size < 57) return null
        var i = 12
        // Skip the answer name (length-prefixed labels, or a pointer).
        while (i < resp.size && resp[i].toInt() != 0) { if (resp[i].toInt() and 0xC0 == 0xC0) { i++; break }; i += (resp[i].toInt() and 0xff) + 1 }
        i += 1 + 2 + 2 + 4 + 2 // terminator, type, class, ttl, rdlength
        if (i >= resp.size) return null
        val n = resp[i].toInt() and 0xff; i++
        val names = ArrayList<Pair<String, Int>>()
        repeat(n) {
            if (i + 18 > resp.size) return@repeat
            val s = String(resp, i, 15, Charsets.US_ASCII).trimEnd(' ', '\u0000')
            val suffix = resp[i + 15].toInt() and 0xff
            val group = (resp[i + 16].toInt() and 0x80) != 0
            if (!group) names += s to suffix
            i += 18
        }
        return names.firstOrNull { it.second == 0 }?.first ?: names.firstOrNull()?.first
    }
}

/** A connected-network change between two link snapshots: a roam (BSSID changed, same SSID) or a reconnect. */
data class RoamEvent(val t: Long, val ssid: String, val fromBssid: String, val toBssid: String, val fromRssi: Int, val toRssi: Int, val fromFreq: Int, val toFreq: Int) {
    val kind: String get() = if (fromBssid.isEmpty()) "connect" else if (toBssid.isEmpty()) "disconnect" else "roam"
}

object Roaming {
    fun detect(t: Long, prev: LinkSnapshot?, now: LinkSnapshot?): RoamEvent? {
        val a = prev?.bssid.orEmpty(); val b = now?.bssid.orEmpty()
        if (a == b) return null
        return RoamEvent(t, (now?.ssid ?: prev?.ssid).orEmpty(), a, b, prev?.rssi ?: 0, now?.rssi ?: 0, prev?.freqMhz ?: 0, now?.freqMhz ?: 0)
    }
}

/** The manual-position heatmap: a [rows] x [cols] floor grid; each tapped cell keeps the RSSI samples taken standing there. */
data class HeatCell(val row: Int, val col: Int, val samples: List<Int>) { val mean: Double get() = if (samples.isEmpty()) Double.NaN else samples.average() }

data class Heatmap(val rows: Int, val cols: Int, val target: String, val cells: Map<Int, HeatCell> = emptyMap()) {
    fun key(r: Int, c: Int) = r * cols + c
    fun record(r: Int, c: Int, rssi: Int): Heatmap {
        val k = key(r, c); val old = cells[k]
        return copy(cells = cells + (k to HeatCell(r, c, (old?.samples ?: emptyList()) + rssi)))
    }
    fun clear(): Heatmap = copy(cells = emptyMap())
    val best: HeatCell? get() = cells.values.filter { it.samples.isNotEmpty() }.maxByOrNull { it.mean }
}

/** Hot / cold locator pacing: the stronger the signal, the faster and higher the beep. Pure: JVM-tested. */
object Locator {
    fun intervalMs(rssi: Double, minMs: Long = 80, maxMs: Long = 1400): Long {
        val f = ((rssi + 90.0) / 60.0).coerceIn(0.0, 1.0)
        return (maxMs - f * (maxMs - minMs)).toLong()
    }

    /** Trend over the last samples: +1 warmer, -1 colder, 0 steady (within 1.5 dB). */
    fun trend(samples: List<Double>): Int {
        if (samples.size < 4) return 0
        val h = samples.size / 2
        val d = samples.drop(h).average() - samples.take(h).average()
        return if (d > 1.5) 1 else if (d < -1.5) -1 else 0
    }
}
